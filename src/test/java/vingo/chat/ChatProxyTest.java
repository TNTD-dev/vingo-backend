package vingo.chat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import static org.assertj.core.api.Assertions.assertThat;

class ChatProxyTest extends IntegrationTest {
    static final AtomicReference<String> mode = new AtomicReference<>("json");
    static final AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();
    static final AtomicReference<String> path = new AtomicReference<>();
    static final String SSE = "event: text.delta\r\nid: 1\r\ndata: {\"text\":\"Xin chào\",\"seq\":1}\r\n\r\n";
    static final HttpServer agent;
    static {
        try {
            agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            agent.createContext("/", exchange -> {
                headers.set(exchange.getRequestHeaders());
                path.set(exchange.getRequestURI().getPath());
                exchange.getRequestBody().readAllBytes();
                String state = mode.get();
                if (state.equals("disconnect")) { exchange.close(); return; }
                int status = state.startsWith("error:") ? Integer.parseInt(state.split(":")[1]) : state.equals("conflict") ? 409 : 200;
                String body = state.equals("sse") ? SSE : state.equals("conflict")
                        ? "{\"code\":\"TURN_IN_PROGRESS\"}" : "{\"state\":\"COMPLETED\"}";
                if (state.startsWith("error:")) body = "{\"code\":\"" + state.split(":")[2] + "\"}";
                exchange.getResponseHeaders().set("Content-Type", state.equals("sse") ? "text/event-stream" : "application/json");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            agent.start();
        } catch (java.io.IOException ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("vingo.agent.url", () -> "http://127.0.0.1:" + agent.getAddress().getPort());
        registry.add("vingo.agent.service-key", () -> "test-service");
        registry.add("vingo.agent.key", () -> "test-agent");
    }
    @LocalServerPort int port;
    @AfterAll static void stop() { agent.stop(0); }
    @Test void conversations_returns401_whenNotAuthenticated() {
        client.post().uri("/api/v1/chat/conversations").exchange().expectStatus().isUnauthorized();
    }
    @Test void proxy_rebuildsIdentityHeaders_fromSession() {
        mode.set("json");
        String token = tokenFor(Role.BUYER);
        client.post().uri("/api/v1/chat/conversations").header("Authorization", "Bearer " + token)
                .header("X-Vingo-User-Id", UUID.randomUUID().toString()).header("X-Vingo-Role", "ADMIN")
                .header("X-Vingo-Injected", "untrusted").exchange().expectStatus().isOk();
        assertThat(headers.get().getFirst("X-Vingo-Role")).isEqualTo("BUYER");
        assertThat(headers.get().getFirst("X-Vingo-Injected")).isNull();
        assertThat(headers.get().getFirst("Authorization")).isEqualTo("Bearer " + token);
        assertThat(headers.get().getFirst("X-Agent-Service-Key")).isEqualTo("test-service");
        assertThat(path.get()).isEqualTo("/internal/v1/conversations");
    }
    @Test void messages_preservesSseBytes() throws Exception {
        mode.set("sse");
        String token = tokenFor(Role.BUYER);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat/conversations/" + UUID.randomUUID() + "/messages"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"command_id\":\"" + UUID.randomUUID() + "\",\"text\":\"Chào\"}")).build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(SSE);
    }
    @Test void proxy_returns409_withAgentCode() {
        mode.set("conflict");
        client.post().uri("/api/v1/chat/conversations").header("Authorization", "Bearer " + tokenFor(Role.BUYER))
                .exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("TURN_IN_PROGRESS");
    }
    @Test void agentKey_rejectsInvalidKey_andStillRequiresSession() {
        client.get().uri("/api/v1/events").header("X-Vingo-Agent-Key", "wrong")
                .exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/v1/chat/conversations").header("X-Vingo-Agent-Key", "test-agent")
                .exchange().expectStatus().isUnauthorized();
    }
    @Test void cookieSession_forwardsAuthenticatedToken() {
        mode.set("json");
        String token = tokenFor(Role.BUYER);
        client.get().uri("/api/v1/chat/turns/" + UUID.randomUUID())
                .header("Cookie", "vingo_session=" + token).exchange().expectStatus().isOk();
        assertThat(headers.get().getFirst("Authorization")).isEqualTo("Bearer " + token);
    }
    @Test void adminAudit_marksRequestsViaAgent() {
        long before = jdbc.sql("SELECT count(*) FROM audit_log WHERE via_agent = true").query(Long.class).single();
        client.get().uri("/api/v1/admin/audit-logs").header("Authorization", "Bearer " + tokenFor(Role.ADMIN))
                .header("X-Vingo-Agent-Key", "test-agent").exchange().expectStatus().isOk();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE via_agent = true").query(Long.class).single()).isEqualTo(before + 1);
    }
    @Test void upload_rejectsOversizeAndUnsupportedFile() throws Exception {
        String token = tokenFor(Role.ADMIN);
        for (boolean oversize : java.util.List.of(false, true)) {
            String prefix = "--test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.svg\"\r\nContent-Type: "
                    + (oversize ? "image/svg+xml" : "text/plain") + "\r\n\r\n";
            String body = prefix + (oversize ? "a".repeat(10 * 1024 * 1024 + 1) : "invalid") + "\r\n--test--\r\n";
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat/uploads"))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "multipart/form-data; boundary=test")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(oversize ? 413 : 415);
            assertThat(response.body()).contains(oversize ? "PAYLOAD_TOO_LARGE" : "UNSUPPORTED_MEDIA_TYPE");
        }
    }
    @Test void proxy_returns503_whenAgentDisconnects() {
        mode.set("disconnect");
        client.post().uri("/api/v1/chat/conversations").header("Authorization", "Bearer " + tokenFor(Role.BUYER))
                .exchange().expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE");
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"create", "conversation", "message", "confirm", "reject", "turn", "upload"})
    void allRoutes_proxySuccess_andDeclaredErrors(String route) throws Exception {
        String token = tokenFor(Role.ADMIN);
        mode.set(route.equals("message") || route.equals("confirm") ? "sse" : "json");
        assertThat(call(route, token).statusCode()).isEqualTo(200);
        assertThat(call(route, null).statusCode()).isEqualTo(401);
        // Mỗi route chỉ kiểm các mã lỗi nó khai báo trong contract.
        var errors = switch (route) {
            case "create" -> java.util.List.of("401:UNAUTHORIZED", "403:FORBIDDEN", "429:RATE_LIMITED");
            case "conversation", "turn" -> java.util.List.of("400:VALIDATION_ERROR", "401:UNAUTHORIZED",
                    "404:NOT_FOUND", "429:RATE_LIMITED");
            case "message" -> java.util.List.of("400:VALIDATION_ERROR", "401:UNAUTHORIZED", "404:NOT_FOUND",
                    "409:TURN_IN_PROGRESS", "409:ACTION_NOT_PENDING", "413:PAYLOAD_TOO_LARGE", "429:RATE_LIMITED");
            case "confirm" -> java.util.List.of("400:VALIDATION_ERROR", "401:UNAUTHORIZED", "403:FORBIDDEN",
                    "404:NOT_FOUND", "409:TURN_IN_PROGRESS", "409:ACTION_NOT_PENDING", "429:RATE_LIMITED");
            case "reject" -> java.util.List.of("400:VALIDATION_ERROR", "401:UNAUTHORIZED", "404:NOT_FOUND",
                    "409:ACTION_NOT_PENDING", "429:RATE_LIMITED");
            default -> java.util.List.of("400:VALIDATION_ERROR", "401:UNAUTHORIZED", "413:PAYLOAD_TOO_LARGE",
                    "415:UNSUPPORTED_MEDIA_TYPE", "429:RATE_LIMITED");
        };
        for (String error : errors) {
            mode.set("error:" + error);
            var response = call(route, token);
            assertThat(response.statusCode()).as(route + " " + error).isEqualTo(Integer.parseInt(error.split(":")[0]));
            assertThat(response.body()).contains("\"code\":\"" + error.split(":")[1] + "\"");
        }
    }
    @Test void messages_validatesInput_beforeCallingAgent() throws Exception {
        String token = tokenFor(Role.BUYER);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat/conversations/" + UUID.randomUUID() + "/messages"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"\"}")).build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
    }
    private HttpResponse<String> call(String route, String token) throws Exception {
        String id = UUID.randomUUID().toString();
        String endpoint = switch (route) {
            case "create" -> "conversations";
            case "conversation" -> "conversations/" + id;
            case "message" -> "conversations/" + id + "/messages";
            case "confirm", "reject" -> "actions/" + id + "/" + route;
            case "turn" -> "turns/" + id;
            default -> "uploads";
        };
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/chat/" + endpoint));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (route.equals("message") || route.equals("confirm")) request.header("Accept", "text/event-stream");
        if (route.equals("conversation") || route.equals("turn")) request.GET();
        else if (route.equals("upload")) {
            request.header("Content-Type", "multipart/form-data; boundary=test")
                    .POST(HttpRequest.BodyPublishers.ofString("--test\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.svg\"\r\nContent-Type: image/svg+xml\r\n\r\n<svg/>\r\n--test--\r\n"));
        } else if (route.equals("create")) request.POST(HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(
                "{\"command_id\":\"" + UUID.randomUUID() + "\",\"version\":1,\"text\":\"Chào\"}"));
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void proxy_returns503_whenAgentUnavailable() throws Exception {
        var unavailable = new ChatService("http://127.0.0.1:1", "test", tools.jackson.databind.json.JsonMapper.builder().build());
        var user = new vingo.shared.security.AuthenticatedUser(UUID.randomUUID(), Role.BUYER, UUID.randomUUID(), java.time.OffsetDateTime.now(clock));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> unavailable.request("GET", "turns/" + UUID.randomUUID(), new byte[0], "", false, user, "test"))
                .isInstanceOfSatisfying(vingo.shared.error.DomainException.class, ex -> assertThat(ex.code()).isEqualTo(vingo.shared.error.ErrorCode.SERVICE_UNAVAILABLE));
    }
}
