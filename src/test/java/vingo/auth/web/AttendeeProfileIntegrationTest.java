package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import vingo.api.model.AttendeeProfile;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;

class AttendeeProfileIntegrationTest extends IntegrationTest {
    private static final String BOOK = "/api/v1/me/attendees";
    private static final Map<String, String> NAME = Map.of("full_name", "Guest");

    @Test
    void crud_normalizesContactsAndPreservesCreationOrderAcrossReplacement() {
        String token = tokenFor(Role.BUYER);
        var first = create(token, Map.of("full_name", "  Nguyễn An  ", "email", "An@Example.COM",
                "phone", "0912 345 678"));
        assertThat(first.getFullName()).isEqualTo("Nguyễn An");
        assertThat(first.getEmail()).isEqualTo("an@example.com");
        assertThat(first.getPhone()).isEqualTo("+84912345678");
        var second = create(token, Map.of("full_name", " " + "A".repeat(120) + " ", "phone", "+12025550123"));
        assertThat(second.getFullName()).hasSize(120);
        assertThat(second.getPhone()).isEqualTo("+12025550123");

        String path = BOOK + "/" + first.getId();
        var changed = client.put().uri(path).headers(h -> h.setBearerAuth(token)).body(NAME)
                .exchange().expectStatus().isOk().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(AttendeeProfile.class).returnResult().getResponseBody();
        assertThat(changed.getId()).isEqualTo(first.getId());
        assertThat(changed.getCreatedAt()).isEqualTo(first.getCreatedAt());
        assertThat(changed.getUpdatedAt()).isAfterOrEqualTo(first.getUpdatedAt());
        assertThat(changed.getFullName()).isEqualTo("Guest");
        assertThat(changed.getEmail()).isNull();
        assertThat(changed.getPhone()).isNull();
        var read = client.get().uri(path).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(AttendeeProfile.class).returnResult().getResponseBody();
        assertThat(read).isEqualTo(changed);
        assertThat(list(token)).containsExactly(changed, second);
        client.delete().uri(path).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isNoContent()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store").expectBody().isEmpty();
        assertThat(list(token)).containsExactly(second);
        assertThat(jdbc.sql("SELECT count(*) FROM attendee_profile WHERE id = ?").param(first.getId())
                .query(Integer.class).single()).isZero();
    }

    @Test
    void foreignAndMissingIds_returnTheSame404ForReadReplaceAndDelete() {
        String owner = tokenFor(Role.BUYER);
        var profile = create(owner, NAME);
        String other = tokenFor(Role.BUYER);
        assertThat(list(other)).isEmpty();
        for (UUID id : List.of(profile.getId(), UUID.randomUUID())) {
            for (var method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
                var request = client.method(method).uri(BOOK + "/" + id).headers(h -> h.setBearerAuth(other));
                if (method == HttpMethod.PUT) request.body(NAME);
                request.exchange().expectStatus().isNotFound().expectBody().jsonPath("$.code").isEqualTo("NOT_FOUND");
            }
        }
        assertThat(list(owner)).containsExactly(profile);
    }

    @Test
    void invalidInput_returns400AndLeavesSavedProfileUntouched() {
        String token = tokenFor(Role.BUYER);
        var saved = create(token, NAME);
        for (var body : List.of(Map.of(), Map.of("full_name", "  "), Map.of("full_name", "a".repeat(121)),
                Map.of("full_name", "A", "email", "invalid"), Map.of("full_name", "A", "email", ""),
                Map.of("full_name", "A", "phone", "letters"))) {
            post(token, body).expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
            client.put().uri(BOOK + "/" + saved.getId()).headers(h -> h.setBearerAuth(token)).body(body)
                    .exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }
        for (var method : List.of(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE)) {
            var request = client.method(method).uri(BOOK + "/invalid").headers(h -> h.setBearerAuth(token));
            if (method == HttpMethod.PUT) request.body(NAME);
            request.exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("VALIDATION_ERROR");
        }
        assertThat(list(token)).containsExactly(saved);
    }

    @Test
    void concurrentCreatesAtNineteen_allowExactlyOneMoreProfile() throws Exception {
        String token = tokenFor(Role.BUYER);
        for (int i = 0; i < 19; i++) create(token, NAME);
        var start = new CyclicBarrier(2);
        Callable<Integer> attempt = () -> {
            start.await(10, TimeUnit.SECONDS);
            return post(token, NAME).returnResult(String.class).getStatus().value();
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
        }
        post(token, NAME).expectStatus().isEqualTo(409).expectBody().jsonPath("$.code").isEqualTo("ACTION_STATE_INVALID");
        var profiles = list(token);
        assertThat(profiles).hasSize(20);
        create(tokenFor(Role.BUYER), NAME);
        client.delete().uri(BOOK + "/" + profiles[0].getId()).headers(h -> h.setBearerAuth(token))
                .exchange().expectStatus().isNoContent();
        create(token, NAME);
        assertThat(list(token)).hasSize(20);
    }

    @Test
    void deleteMe_removesAllOwnedProfilesAndPreservesAnotherBook() {
        String owner = tokenFor(Role.BUYER);
        var first = create(owner, NAME);
        var second = create(owner, NAME);
        String other = tokenFor(Role.BUYER);
        var retained = create(other, NAME);
        client.delete().uri("/api/v1/me").headers(h -> h.setBearerAuth(owner)).exchange().expectStatus().isNoContent();
        assertThat(jdbc.sql("SELECT count(*) FROM attendee_profile WHERE id IN (?, ?)")
                .params(first.getId(), second.getId()).query(Integer.class).single()).isZero();
        assertThat(list(other)).containsExactly(retained);
    }

    @Test
    void security_requiresLoginForAllOperationsAndFrontendOriginForCookieWrites() {
        String token = tokenFor(Role.BUYER);
        String path = BOOK + "/" + create(token, NAME).getId();
        for (String read : List.of(BOOK, path)) {
            client.get().uri(read).exchange().expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
            client.get().uri(read).cookie("vingo_session", token).exchange().expectStatus().isOk();
        }
        for (var method : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
            String uri = method == HttpMethod.POST ? BOOK : path;
            client.method(method).uri(uri).exchange().expectStatus().isUnauthorized()
                    .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
            client.method(method).uri(uri).cookie("vingo_session", token).exchange().expectStatus().isForbidden()
                    .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
            client.method(method).uri(uri).cookie("vingo_session", token).header(HttpHeaders.ORIGIN, "https://other.invalid")
                    .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
            var valid = client.method(method).uri(uri).cookie("vingo_session", token)
                    .header(HttpHeaders.ORIGIN, "http://localhost:3000");
            if (method != HttpMethod.DELETE) valid.body(NAME);
            valid.exchange().expectStatus().is2xxSuccessful();
        }
    }

    private ResponseSpec post(String token, Object body) {
        return client.post().uri(BOOK).headers(h -> h.setBearerAuth(token)).contentType(MediaType.APPLICATION_JSON)
                .body(body).exchange();
    }

    private AttendeeProfile create(String token, Map<String, String> body) {
        return post(token, body).expectStatus().isCreated().expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(AttendeeProfile.class).returnResult().getResponseBody();
    }

    private AttendeeProfile[] list(String token) {
        return client.get().uri(BOOK).headers(h -> h.setBearerAuth(token)).exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
                .expectBody(AttendeeProfile[].class).returnResult().getResponseBody();
    }
}
