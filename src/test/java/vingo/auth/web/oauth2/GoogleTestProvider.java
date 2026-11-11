package vingo.auth.web.oauth2;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/** Token endpoint và JWK Set giả để chạy callback thật mà không gọi Google. */
final class GoogleTestProvider implements AutoCloseable {

    private final JsonMapper json = JsonMapper.builder().build();
    private final RSAKey key = new RSAKeyGenerator(2048).keyID("google-test").generate();
    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final AtomicReference<String> idToken = new AtomicReference<>();

    /** Khởi động máy chủ HTTP loopback với cổng riêng cho test. */
    GoogleTestProvider() throws IOException, JOSEException {
        server.createContext("/token", this::tokenResponse);
        server.createContext("/keys", exchange -> write(exchange, 200, new JWKSet(key.toPublicJWK()).toJSONObject()));
        server.start();
    }

    /** Trả URL loopback để thay endpoint Google trong client registration của test. */
    String endpoint(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    /** Ký ID token với nonce của lượt đăng nhập đang được kiểm thử. */
    void issueIdToken(String audience, String nonce, String subject, String email, String avatar) throws JOSEException {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder().issuer("https://accounts.google.com").audience(audience)
                .subject(subject).issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce).claim("email", email).claim("email_verified", true)
                .claim("name", "Google Test").claim("picture", avatar).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key));
        idToken.set(jwt.serialize());
    }

    /** Cấp token đúng một lần khi Spring gửi code và PKCE verifier đến token endpoint. */
    private void tokenResponse(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String token = idToken.getAndSet(null);
        if (!"POST".equals(exchange.getRequestMethod()) || !request.contains("code=google-test-code")
                || !request.contains("code_verifier=") || token == null) {
            write(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        write(exchange, 200, Map.of("access_token", "google-test-access", "token_type", "Bearer",
                "expires_in", 300, "scope", "openid", "id_token", token));
    }

    /** Gửi JSON và đóng exchange sau khi trả response. */
    private void write(HttpExchange exchange, int status, Map<String, ?> body) throws IOException {
        try (exchange) {
            byte[] bytes = json.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    /** Dừng máy chủ giả khi Spring đóng test context. */
    @Override
    public void close() {
        server.stop(0);
    }
}
