package io.github.iskortsevsv.video.telegram;

import io.github.iskortsevsv.video.config.TelegramProperties;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(name = "telegram.enabled", havingValue = "true")
public class TelegramClient {
    public static final long MAX_INPUT = 20_000_000;
    public static final long MAX_OUTPUT = 50_000_000;
    private final RestClient http;
    private final String api;
    private final String files;
    private final JsonMapper mapper = new JsonMapper();
    @org.springframework.beans.factory.annotation.Autowired
    public TelegramClient(TelegramProperties properties) {
        this(properties, "https://api.telegram.org");
    }
    TelegramClient(TelegramProperties properties, String base) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(60));
        http = RestClient.builder().requestFactory(factory).build();
        api = base + "/bot" + properties.token() + "/";
        files = base + "/file/bot" + properties.token() + "/";
    }
    public JsonNode updates(long offset) {
        return call("getUpdates", Map.of("offset", offset, "timeout", 25, "limit", 1, "allowed_updates", new String[]{"message"}));
    }
    public void message(long chat, String text) {
        call("sendMessage", Map.of("chat_id", chat, "text", text));
    }
    private JsonNode call(String method, Object body) {
        try {
            return http.post().uri(api + method).contentType(MediaType.APPLICATION_JSON).body(body)
                    .exchange((request, response) -> decode(response.getBody().readNBytes(1_048_576)));
        } catch (TelegramApiException ex) { throw ex; }
        catch (Exception ex) { throw new TelegramApiException(5); }
    }
    private JsonNode decode(byte[] bytes) {
        var result = mapper.readTree(bytes);
        if (!result.path("ok").asBoolean())
            throw new TelegramApiException(result.path("parameters").path("retry_after").asInt(5));
        return result.path("result");
    }
    public void download(String fileId, Path destination) {
        var file = call("getFile", Map.of("file_id", fileId));
        String path = file.path("file_path").asText("");
        if (file.path("file_size").asLong(0) > MAX_INPUT || path.isBlank()
                || !path.matches("[A-Za-z0-9_./-]+") || path.contains(".."))
            throw new IllegalArgumentException("Файл недоступен или превышает 20 МБ.");
        try {
            http.get().uri(files + path).exchange((request, response) -> {
                if (!response.getStatusCode().is2xxSuccessful()) throw new TelegramApiException(5);
                try (var output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    int count;
                    while ((count = response.getBody().read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_INPUT) throw new IllegalArgumentException("Файл превышает 20 МБ.");
                        output.write(buffer, 0, count);
                    }
                }
                return null;
            });
        } catch (IllegalArgumentException | TelegramApiException ex) { throw ex; }
        catch (Exception ex) { throw new TelegramApiException(5); }
    }
    public void document(long chat, Path output, String caption) throws IOException {
        if (Files.size(output) > MAX_OUTPUT) throw new IllegalArgumentException("Результат превышает лимит Telegram 50 МБ.");
        var body = new LinkedMultiValueMap<String, Object>();
        body.add("chat_id", Long.toString(chat));
        body.add("caption", caption);
        body.add("document", new FileSystemResource(output));
        try {
            http.post().uri(api + "sendDocument").contentType(MediaType.MULTIPART_FORM_DATA).body(body)
                    .exchange((request, response) -> decode(response.getBody().readNBytes(1_048_576)));
        } catch (TelegramApiException ex) { throw ex; }
        catch (Exception ex) { throw new TelegramApiException(5); }
    }
}
