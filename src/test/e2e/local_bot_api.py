"""Loopback Bot API for the packaged-service tests (Python standard library)."""
import itertools
import json
import queue
import threading
import time
from contextlib import suppress
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import url2pathname


class LocalBotApi(ThreadingHTTPServer):
    def __init__(self, media, output_root):
        super().__init__(("127.0.0.1", 0), BotRequest)
        self.media = media
        self.output_root = output_root
        self.updates = []
        self.changed = threading.Condition()
        self.events = queue.Queue()
        self.message_ids = itertools.count(500)

    def enqueue(self, update):
        with self.changed:
            update["update_id"] = len(self.updates) + 1
            self.updates.append(update)
            self.changed.notify_all()

    def message(self, chat_id, **fields):
        self.enqueue({"message": {
            "message_id": next(self.message_ids),
            "chat": {"id": chat_id, "type": "private"}, **fields,
        }})

    def click(self, message, label):
        rows = message["reply_markup"]["inline_keyboard"]
        button = next((b for row in rows for b in row if label in b["text"]), None)
        if button is None:
            raise AssertionError(f"Missing button {label!r}")
        chat_id = int(message["chat_id"])
        self.enqueue({"callback_query": {
            "id": str(next(self.message_ids)), "from": {"id": chat_id},
            "data": button["callback_data"],
            "message": {"chat": {"id": chat_id, "type": "private"}, "from": {"is_bot": True},
                        "message_id": message["message_id"]},
        }})

    def attach(self, prompt, name):
        file = self.media[name]
        self.message(int(prompt["chat_id"]),
                     reply_to_message={"from": {"is_bot": True}, "message_id": prompt["message_id"],
                                       "text": prompt["text"]},
                     document={"file_id": name, "file_name": file.name, "file_size": file.stat().st_size})

    def wait_for(self, method, chat_id, text="", message_id=None):
        deadline = time.monotonic() + 25
        while True:
            try:
                name, event = self.events.get(timeout=max(0, deadline - time.monotonic()))
            except queue.Empty as exc:
                raise AssertionError(f"Timed out waiting for {method} in chat {chat_id}: {text!r}") from exc
            if (name == method and int(event["chat_id"]) == chat_id and text in event.get("text", "")
                    and (message_id is None or event["message_id"] == message_id)):
                return event

    def dispatch(self, method, body):
        if method == "getUpdates":
            offset = body.get("offset", 0)
            with self.changed:
                self.changed.wait_for(lambda: len(self.updates) >= max(offset, 1), timeout=0.2)
                return [u for u in self.updates if u["update_id"] >= offset][:1]
        if method == "getFile":
            file = self.media[body["file_id"]]
            return {"file_path": str(file), "file_size": file.stat().st_size}
        if method in ("sendMessage", "sendDocument", "sendVideo"):
            if method in ("sendDocument", "sendVideo"):
                # In local Bot API mode the JAR supplies a file URI. Read it before acknowledging delivery.
                field = "video" if method == "sendVideo" else "document"
                uri = urlsplit(body[field])
                if uri.scheme != "file" or uri.netloc:
                    raise ValueError(f"Expected a local file URI for {method}")
                path = Path(url2pathname(uri.path)).resolve(strict=True)
                path.relative_to(self.output_root)
                body[field] = path.read_bytes()
            result = {"message_id": next(self.message_ids)}
            self.events.put((method, {**body, **result}))
            return result
        if method in ("editMessageReplyMarkup", "editMessageText"):
            self.events.put((method, body))
        return True


class BotRequest(BaseHTTPRequestHandler):
    def read_body(self):
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            return self.rfile.read(int(self.headers["Content-Length"]))
        chunks = bytearray()
        while True:
            size = int(self.rfile.readline().split(b";", 1)[0], 16)
            if not size:
                self.rfile.readline()
                return chunks
            chunks.extend(self.rfile.read(size))
            self.rfile.read(2)

    def do_POST(self):
        prefix = "/bot999:TEST/"
        if not self.path.startswith(prefix):
            self.send_error(404)
            return
        payload = self.read_body()
        kind = self.headers["Content-Type"]
        if kind.startswith("multipart/form-data"):
            mime = BytesParser(policy=policy.default).parsebytes(
                f"Content-Type: {kind}\r\n\r\n".encode() + payload)
            # In local mode even the document field is text (a file URI).
            body = {part.get_param("name", header="content-disposition"): part.get_payload(decode=True).decode("utf-8")
                    for part in mime.iter_parts()}
            body["reply_markup"] = json.loads(body["reply_markup"])
        else:
            body = json.loads(payload) if payload else {}
        result = self.server.dispatch(self.path[len(prefix):], body)
        data = json.dumps({"ok": True, "result": result}).encode()
        # Stopping the JAR can close an outstanding long poll before the response.
        with suppress(BrokenPipeError, ConnectionResetError):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

    def log_message(self, *args):
        pass
