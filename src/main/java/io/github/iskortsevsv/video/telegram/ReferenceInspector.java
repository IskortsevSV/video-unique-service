package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.VideoProperties;
import io.github.iskortsevsv.video.service.ProcessRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Извлекает технические параметры присланного примера без его перекодирования. */
@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
class ReferenceInspector {
    private final ProcessRunner runner;
    private final VideoProperties config;
    private final JsonMapper mapper = new JsonMapper();

    ReferenceInspector(ProcessRunner runner, VideoProperties config) {
        this.runner = runner;
        this.config = config;
    }

    record Info(boolean photo, Map<String, String> fields) {
        String summary(Info source) {
            var text = new StringBuilder();
            fields.forEach((key, value) -> {
                String before = source == null ? value : source.fields().getOrDefault(key, "—");
                text.append('\n').append(key).append(" — ");
                if (!before.equals(value)) {
                    text.append(before).append(" → ");
                }
                text.append(value);
            });
            return text.toString();
        }
    }

    Info inspect(Path file) throws IOException {
        var fields = new LinkedHashMap<String, String>();
        fields.put("Размер файла, байт", Long.toString(Files.size(file)));
        try (var stream = ImageIO.createImageInputStream(file.toFile())) {
            var readers = ImageIO.getImageReaders(stream);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                    reader.setInput(stream);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if ((!format.equals("JPEG") && !format.equals("PNG"))
                            || width < 2 || height < 2 || (long) width * height > 20_000_000) {
                        throw new IllegalArgumentException("Нужно фото JPEG/PNG до 20 мегапикселей.");
                    }
                    fields.put("Формат", format);
                    fields.put("Размер изображения", width + " × " + height);
                    return new Info(true, fields);
                } finally {
                    reader.dispose();
                }
            }
        }

        JsonNode probe;
        try {
            probe = mapper.readTree(runner.run(List.of(config.ffprobe(), "-v", "error",
                    "-protocol_whitelist", "file", "-show_entries",
                    "format=format_name,duration,bit_rate:stream=codec_type,codec_name,width,height,avg_frame_rate",
                    "-of", "json", file.toString()), config.timeout()));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Не удалось прочитать файл. Нужен JPEG, PNG, MP4 или MOV.");
        }
        var format = probe.path("format");
        JsonNode video = null;
        JsonNode audio = null;
        for (var stream : probe.path("streams")) {
            String type = stream.path("codec_type").asText();
            if ("video".equals(type) && video == null) {
                video = stream;
            } else if ("audio".equals(type) && audio == null) {
                audio = stream;
            }
        }
        if (video == null || !List.of(format.path("format_name").asText().split(",")).contains("mp4")) {
            throw new IllegalArgumentException("Нужен MP4/MOV с видеодорожкой или фото JPEG/PNG.");
        }
        fields.put("Формат", "MP4/MOV");
        fields.put("Кадр", video.path("width").asText("?") + " × " + video.path("height").asText("?"));
        fields.put("Длительность, с", format.path("duration").asText("—"));
        fields.put("Частота кадров", video.path("avg_frame_rate").asText("—"));
        fields.put("Битрейт, бит/с", format.path("bit_rate").asText("—"));
        fields.put("Видеокодек", video.path("codec_name").asText("—"));
        fields.put("Аудиокодек", audio == null ? "Нет аудио" : audio.path("codec_name").asText("—"));
        return new Info(false, fields);
    }
}
