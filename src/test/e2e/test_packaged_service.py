"""Exercise the actual Spring Boot JAR with synthetic media and an isolated Bot API."""
import hashlib
import http.client
import json
import os
import re
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from contextlib import closing
from pathlib import Path
from uuid import uuid4

from local_bot_api import LocalBotApi


FFMPEG = os.environ.get("FFMPEG_PATH", "ffmpeg")
FFPROBE = os.environ.get("FFPROBE_PATH", "ffprobe")


def make_video(file, size, *, mov=False):
    subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-f", "lavfi", "-i",
                    f"testsrc=size={size}:rate=30000/1001:duration=1", "-c:v", "libx264",
                    "-threads", "1", "-pix_fmt", "yuv444p" if mov else "yuv420p",
                    "-f", "mov" if mov else "mp4", file], check=True, timeout=20)


def probe(file):
    raw = subprocess.check_output([FFPROBE, "-v", "error", "-show_format", "-show_streams",
                                   "-of", "json", file], timeout=15)
    return json.loads(raw)


def stop_app(app):
    if app.poll() is None:
        app.terminate()
        try:
            app.wait(timeout=15)
        except subprocess.TimeoutExpired:
            app.kill()
            app.wait(timeout=10)


class PackagedServiceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        directory = tempfile.TemporaryDirectory(prefix="vuniq-e2e-")
        cls.addClassCleanup(directory.cleanup)
        cls.state = Path(directory.name).resolve()
        media = cls.state / "bot-api"
        media.mkdir()
        tmp = cls.state / "tmp"
        tmp.mkdir()
        cls.bot = LocalBotApi({name: media / name for name in ("copy.mp4", "source.mp4", "photo.png")}, tmp)
        cls.addClassCleanup(cls.bot.server_close)
        thread = threading.Thread(target=cls.bot.serve_forever, daemon=True)
        thread.start()
        cls.addClassCleanup(thread.join)
        cls.addClassCleanup(cls.bot.shutdown)

        log = cls.state / "app.log"
        with log.open("wb") as output:
            app = subprocess.Popen([
                JAVA, f"-Djava.io.tmpdir={tmp}", "-jar", JAR, "--server.address=127.0.0.1", "--server.port=0",
                "--spring.servlet.multipart.max-file-size=1MB", "--spring.servlet.multipart.max-request-size=2MB",
                "--telegram.enabled=true", "--telegram.token=999:TEST", "--telegram.allowed-chat-ids=",
                f"--telegram.offset-file={cls.state / 'offset'}", f"--telegram.local-api-files={media}",
                f"--telegram.local-api-port={cls.bot.server_port}", "--telegram.feedback-admin-ids=42,44",
                "--telegram.reference-enabled=true", "--telegram.feedback-uploads-enabled=false",
            ], cwd=cls.state, stdout=output, stderr=subprocess.STDOUT)
        cls.addClassCleanup(stop_app, app)
        # Tomcat reports the port after binding it. No reserve/release race between test runs.
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline and app.poll() is None:
            port = re.search(rb"Tomcat started on port (\d+)", log.read_bytes())
            if port:
                cls.port = int(port[1])
                return
            time.sleep(0.1)
        raise RuntimeError("Test JAR failed to start:\n" + log.read_text(encoding="utf-8", errors="replace")[-2000:])

    def upload(self, data):
        boundary = uuid4().hex
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.mov"\r\n'
                'Content-Type: video/quicktime\r\n\r\n').encode() + data + f'\r\n--{boundary}--\r\n'.encode()
        with closing(http.client.HTTPConnection("127.0.0.1", self.port, timeout=30)) as client:
            client.request("POST", "/api/videos/unique", body,
                           {"Content-Type": f"multipart/form-data; boundary={boundary}"})
            response = client.getresponse()
            return response.status, response.headers, response.read()

    def test_quicktime_upload_returns_playable_mp4_with_matching_hashes(self):
        source = self.state / "odd.mov"
        make_video(source, "321x241", mov=True)
        original = probe(source)
        self.assertEqual("qt", original["format"]["tags"]["major_brand"].strip())
        stream = original["streams"][0]
        self.assertEqual((321, 241, "30000/1001"), (stream["width"], stream["height"], stream["r_frame_rate"]))

        data = source.read_bytes()
        status, headers, result = self.upload(data)
        self.assertEqual(200, status)
        self.assertEqual("video/mp4", headers.get_content_type())
        self.assertEqual(hashlib.sha256(data).hexdigest(), headers["X-Hash-Before"])
        self.assertEqual(hashlib.sha256(result).hexdigest(), headers["X-Hash-After"])

        output = self.state / "result.mp4"
        output.write_bytes(result)
        encoded = probe(output)
        self.assertIn("mp4", encoded["format"]["format_name"].split(","))
        self.assertNotEqual("qt", encoded["format"]["tags"]["major_brand"].strip())
        self.assertEqual(["video"], [s["codec_type"] for s in encoded["streams"]])
        video = encoded["streams"][0]
        self.assertEqual(("h264", "yuv420p"), (video["codec_name"], video["pix_fmt"]))
        self.assertEqual((0, 0), (video["width"] % 2, video["height"] % 2))
        subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-xerror", "-i", output, "-f", "null", "-"],
                       check=True, timeout=20)

    def test_multipart_file_limit(self):
        limit = 1024 * 1024
        # At the limit, multipart accepts the file and media validation rejects the fake MOV.
        self.assertEqual(422, self.upload(bytes(limit))[0])
        self.assertEqual(413, self.upload(bytes(limit + 1))[0])

    def test_reference_pair_deletion_and_feedback_in_packaged_bot(self):
        bot = self.bot
        files = bot.media
        make_video(files["copy.mp4"], "96x160")
        make_video(files["source.mp4"], "112x160")
        subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-f", "lavfi", "-i", "color=s=40x24",
                        "-frames:v", "1", "-threads", "1", files["photo.png"]], check=True, timeout=20)

        archive = self.state / "feedback-materials"
        bot.message(43, text="/reference")
        bot.wait_for("sendMessage", 43, "Команда /reference доступна администратору")
        self.assertFalse(archive.exists())
        bot.message(44, text="/reference")
        bot.wait_for("sendMessage", 44, "Пример другого сервиса")
        bot.message(42, text="/reference")
        bot.click(bot.wait_for("sendMessage", 42, "Пример другого сервиса"), "согласен")
        bot.click(bot.wait_for("sendMessage", 42, "Сначала приложите"), "Обработанная копия")
        bot.attach(bot.wait_for("sendMessage", 42, "Отправьте один файл"), "copy.mp4")
        bot.click(bot.wait_for("sendMessage", 42, "96 × 160"), "Исходник")
        bot.attach(bot.wait_for("sendMessage", 42, "Отправьте один файл"), "source.mp4")
        summary = bot.wait_for("sendMessage", 42, "112 × 160 → 96 × 160")
        self.assertCountEqual(["copy.bin", "source.bin"], [f.name for f in archive.rglob("*.bin")])
        bot.click(summary, "Удалить материалы")
        bot.wait_for("sendMessage", 42, "Материалы обращения удалены")
        self.assertEqual([], list(archive.rglob("*.bin")))

        bot.message(42, text="/feedback_stats")
        bot.wait_for("sendMessage", 42, "Выданных копий пока нет")
        photo = files["photo.png"]
        bot.message(42, document={"file_id": photo.name, "file_name": photo.name, "file_size": photo.stat().st_size})
        copy = bot.wait_for("sendDocument", 42)
        self.assertTrue(copy["document"].startswith(b"\xff\xd8\xff"), "Expected a JPEG document")
        bot.click(copy, "Сообщить результат публикации")
        vote = bot.wait_for("editMessageReplyMarkup", 42, message_id=copy["message_id"])
        bot.click(vote, "Ещё не проверял")
        saved = bot.wait_for("editMessageReplyMarkup", 42, message_id=copy["message_id"])
        rows = saved["reply_markup"]["inline_keyboard"]
        self.assertEqual(1, len(rows))
        self.assertIn("изменить", rows[0][0]["text"])

        bot.message(42, text="/feedback_stats")
        report = bot.wait_for("sendMessage", 42, "не проверяли 1")
        self.assertIn("Проверено 0", report["text"])
        bot.message(43, text="/feedback_stats")
        bot.wait_for("sendMessage", 43, "Команда доступна администратору")


if __name__ == "__main__":
    JAR = Path(sys.argv[1]).resolve(strict=True)
    JAVA = Path(sys.argv[2], "bin", "java.exe" if os.name == "nt" else "java").resolve(strict=True)
    unittest.main(argv=[sys.argv[0]], verbosity=2)
