package vingo.auth.web;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import tools.jackson.databind.json.JsonMapper;
import vingo.auth.AuthService;
import vingo.auth.AuthUser;
import vingo.auth.internal.AuthProperties;
import vingo.auth.internal.TokenSecrets;
import vingo.shared.security.AuthenticatedUser;
import vingo.shared.security.CurrentUser;
import vingo.shared.security.ProblemAuthenticationHandlers;
import vingo.shared.security.Role;
import vingo.shared.security.SecurityConfig;
import vingo.shared.web.ApiPathConfig;

@SpringJUnitWebConfig(SessionSecurityChainTest.Config.class)
class SessionSecurityChainTest {
    @EnableWebMvc
    @EnableWebSecurity
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @Import({SecurityConfig.class, AuthWebConfig.class, AuthCookies.class, ProblemAuthenticationHandlers.class,
            CurrentUser.class, ApiPathConfig.class, MeController.class})
    static class Config {
        @Bean Clock clock() { return Clock.fixed(Instant.parse("2026-11-25T00:00:00Z"), ZoneOffset.UTC); }
        @Bean AuthService auth() { return mock(AuthService.class); }
        @Bean JsonMapper jsonMapper() {
            return JsonMapper.builder().addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class).build();
        }
        @Bean AuthProperties properties() {
            return new AuthProperties(URI.create("http://localhost:3000/auth/google"), false,
                    Duration.ofDays(7));
        }
    }

    @Test
    void configuredSecurityChainUsesOneLookupAndPassesPrincipalToController(
            @Autowired WebApplicationContext context, @Autowired AuthService auth) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var principal = new AuthenticatedUser(UUID.randomUUID(), Role.BUYER, UUID.randomUUID(),
                OffsetDateTime.parse("2027-01-01T00:00Z"));
        String token = TokenSecrets.generate();
        when(auth.authenticate(eq(token), any(), anyString(), eq(true))).thenReturn(Optional.of(principal));
        when(auth.getMe(principal.id())).thenReturn(new AuthUser(principal.id(), "test@example.invalid",
                null, "Buyer", null, Role.BUYER, "ACTIVE"));

        mvc.perform(get("/api/v1/me").cookie(new Cookie("vingo_session", token)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(principal.id().toString()));
        verify(auth).authenticate(eq(token), any(), anyString(), eq(true));
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(post("/api/v1/auth/login").header("Origin", "https://other.invalid"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void passwordRotationCookieUsesOnlyTimeRemainingOnOriginalSession(
            @Autowired WebApplicationContext context, @Autowired AuthService auth, @Autowired Clock clock) throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var principal = new AuthenticatedUser(UUID.randomUUID(), Role.BUYER, UUID.randomUUID(),
                OffsetDateTime.now(clock).plusSeconds(90));
        var account = new AuthUser(principal.id(), "test@example.invalid", null, "Buyer", null, Role.BUYER, "ACTIVE");
        String original = TokenSecrets.generate();
        String replacement = TokenSecrets.generate();
        when(auth.authenticate(eq(original), any(), anyString(), eq(true))).thenReturn(Optional.of(principal));
        when(auth.updateMe(principal, null, null, "oldPassword", "newPassword"))
                .thenReturn(new AuthService.ProfileUpdate(account, Optional.of(replacement)));

        mvc.perform(patch("/api/v1/me").header("Authorization", "Bearer " + original)
                        .contentType("application/json").content("""
                                {"current_password":"oldPassword","new_password":"newPassword"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().value("vingo_session", replacement))
                .andExpect(cookie().maxAge("vingo_session", 90));
    }
}
