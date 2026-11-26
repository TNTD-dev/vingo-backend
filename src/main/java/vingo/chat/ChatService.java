package vingo.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;
import vingo.shared.security.AuthenticatedUser;

/** Proxy HTTP không mở transaction; chỉ gửi những header được dựng từ phiên đã xác thực. */
@Service
public class ChatService {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final String url;
    private final String key;
    private final JsonMapper json;
    public ChatService(@Value("${vingo.agent.url}") String url,
                       @Value("${vingo.agent.service-key}") String key, JsonMapper json) {
        this.url = url.replaceAll("/+$", "");
        this.key = key;
        this.json = json;
    }
    public HttpResponse<InputStream> request(String method, String path, byte[] body, String contentType,
                                             boolean stream, AuthenticatedUser user, String token) {
        if (key.isBlank()) throw unavailable();
        var builder = HttpRequest.newBuilder(URI.create(url + "/internal/v1/" + path))
                .timeout(Duration.ofSeconds(130))
                .header("X-Agent-Service-Key", key).header("X-Vingo-User-Id", user.id().toString())
                .header("X-Vingo-Role", user.role().name()).header("Authorization", "Bearer " + token)
                .header("Accept", stream ? "text/event-stream" : "application/json")
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        if (!contentType.isEmpty()) builder.header("Content-Type", contentType);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                try (var input = response.body()) {
                    if (response.statusCode() < 500) {
                        var problem = json.readTree(input.readNBytes(65536));
                        ErrorCode code;
                        try { code = ErrorCode.valueOf(problem.path("code").asText()); }
                        catch (IllegalArgumentException ex) { throw unavailable(); }
                        if (code.status().value() == response.statusCode())
                            throw new DomainException(code, code.title());
                    }
                    throw unavailable();
                }
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || !response.headers().firstValue("Content-Type").orElse("")
                        .startsWith(stream ? "text/event-stream" : "application/json")) {
                response.body().close();
                throw unavailable();
            }
            return response;
        } catch (IOException | tools.jackson.core.JacksonException ex) { throw unavailable(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
    }
    public byte[] encode(Object body) { return json.writeValueAsBytes(body); }
    @SuppressWarnings("unchecked")
    public Map<String, Object> read(HttpResponse<InputStream> response) {
        try (var input = response.body()) {
            Map<String, Object> result = json.readValue(input, Map.class);
            if (result == null) throw unavailable();
            return result;
        }
        catch (IOException | tools.jackson.core.JacksonException ex) { throw unavailable(); }
    }
    private static DomainException unavailable() {
        return new DomainException(ErrorCode.SERVICE_UNAVAILABLE, "Agent tạm thời không khả dụng");
    }
}
