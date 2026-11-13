package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import vingo.api.model.LoginRequest;
import vingo.api.model.OtpSession;
import vingo.api.model.Problem;
import vingo.api.model.RegisterRequest;
import vingo.api.model.ResendOtpRequest;
import vingo.api.model.TokenPair;
import vingo.api.model.User;
import vingo.api.model.VerifyOtpRequest;
import vingo.notifications.internal.DeliveryException;
import vingo.notifications.internal.EmailPayload;
import vingo.notifications.internal.EmailSender;
import vingo.support.IntegrationTest;

@Import(RegisterAndOtpIntegrationTest.StubMailConfig.class)
@TestPropertySource(properties = {
        "vingo.auth.secure-cookies=false",
        "vingo.auth.otp.ttl=PT5M",
        "vingo.auth.otp.max-attempts=3",
        "vingo.auth.otp.resend-cooldown=PT30S",
        "vingo.auth.otp.resend-max-per-window=3",
        "vingo.auth.otp.resend-window=PT15M",
        "vingo.auth.otp.register-max-per-window=3",
        "vingo.auth.otp.register-window=PT1M"
})
class RegisterAndOtpIntegrationTest extends IntegrationTest {

    private static final String REGISTER = "/api/v1/auth/register";
    private static final String VERIFY_OTP = "/api/v1/auth/otp/verify";
    private static final String RESEND_OTP = "/api/v1/auth/otp/resend";
    private static final String LOGIN = "/api/v1/auth/login";
    private static final String PASSWORD = "StrongPassword#2026";
    private static final Pattern OTP_PATTERN = Pattern.compile("<strong>(\\d{6})</strong>");

    @Autowired
    private RecordingEmailSender mailSender;

    @BeforeEach
    void resetMailAndClock() {
        clock.set(Instant.parse("2026-11-13T10:00:00Z"));
        mailSender.sent.clear();
        mailSender.failure = null;
    }

    @Test
    void register_createsUnverifiedUserAndHashedOtp_andBlocksLoginUntilVerified() {
        String rawEmail = "New.Buyer." + UUID.randomUUID() + "@Vingo.Local";
        String normalizedEmail = rawEmail.toLowerCase();
        String phone = randomPhone();

        OtpSession session = client.post().uri(REGISTER)
                .body(new RegisterRequest("Nguyen Van A", rawEmail, phone, PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OtpSession.class)
                .returnResult()
                .getResponseBody();

        assertThat(session).isNotNull();
        assertThat(session.getOtpSessionId()).isNotNull();
        assertThat(session.getChannel()).isEqualTo(OtpSession.ChannelEnum.EMAIL);
        assertThat(session.getExpiresAt()).isNotNull();

        String status = jdbc.sql("SELECT status FROM users WHERE email = :email")
                .param("email", normalizedEmail)
                .query(String.class)
                .single();
        assertThat(status).isEqualTo("UNVERIFIED");

        assertThat(mailSender.sent).singleElement().satisfies(sent -> {
            assertThat(sent.payload().recipient()).isEqualTo(normalizedEmail);
            String otp = extractOtp(sent.payload().html());
            String storedHash = jdbc.sql("SELECT otp_hash FROM otp_session WHERE id = :id")
                    .param("id", session.getOtpSessionId())
                    .query(String.class)
                    .single();
            assertThat(storedHash).startsWith("$argon2").doesNotContain(otp);
        });

        // Không lưu OTP vào outbox_message
        int outboxCount = jdbc.sql("SELECT count(*) FROM outbox_message WHERE recipient = :email")
                .param("email", normalizedEmail)
                .query(Integer.class)
                .single();
        assertThat(outboxCount).isZero();

        // Tài khoản UNVERIFIED không đăng nhập được
        Problem loginProblem = client.post().uri(LOGIN)
                .body(new LoginRequest(normalizedEmail, PASSWORD))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(loginProblem).isNotNull();
        assertThat(loginProblem.getCode()).isEqualTo(Problem.CodeEnum.INVALID_CREDENTIALS);
    }

    @Test
    void register_returns409_whenEmailOrPhoneAlreadyTaken() {
        String email = "dup." + UUID.randomUUID() + "@vingo.local";
        String phone = randomPhone();

        client.post().uri(REGISTER)
                .body(new RegisterRequest("First User", email, phone, PASSWORD))
                .exchange()
                .expectStatus().isCreated();

        Problem emailConflict = client.post().uri(REGISTER)
                .body(new RegisterRequest("Second User", email.toUpperCase(), randomPhone(), PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(emailConflict).isNotNull();
        assertThat(emailConflict.getCode()).isEqualTo(Problem.CodeEnum.EMAIL_TAKEN);

        Problem phoneConflict = client.post().uri(REGISTER)
                .body(new RegisterRequest("Third User", "other." + UUID.randomUUID() + "@vingo.local", phone, PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(phoneConflict).isNotNull();
        assertThat(phoneConflict.getCode()).isEqualTo(Problem.CodeEnum.PHONE_TAKEN);
    }

    @Test
    void register_returns400ValidationError_whenFullNameIsBlank() {
        Problem blankName = client.post().uri(REGISTER)
                .body(new RegisterRequest("   ", "blank." + UUID.randomUUID() + "@vingo.local", randomPhone(), PASSWORD))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(blankName).isNotNull();
        assertThat(blankName.getCode()).isEqualTo(Problem.CodeEnum.VALIDATION_ERROR);
    }

    @Test
    void resendOtp_returns400OtpInvalid_whenSessionMissingOrAlreadyConsumed() {
        Problem missing = client.post().uri(RESEND_OTP)
                .body(new ResendOtpRequest(UUID.randomUUID()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(missing).isNotNull();
        assertThat(missing.getCode()).isEqualTo(Problem.CodeEnum.OTP_INVALID);

        String email = "consumed." + UUID.randomUUID() + "@vingo.local";
        OtpSession session = registerUser(email, randomPhone());
        String otp = extractOtp(mailSender.sent.getLast().payload().html());

        client.post().uri(VERIFY_OTP)
                .body(new VerifyOtpRequest(session.getOtpSessionId(), otp))
                .exchange()
                .expectStatus().isOk();

        Problem consumed = client.post().uri(RESEND_OTP)
                .body(new ResendOtpRequest(session.getOtpSessionId()))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(consumed).isNotNull();
        assertThat(consumed.getCode()).isEqualTo(Problem.CodeEnum.OTP_INVALID);
    }

    @Test
    void verifyOtp_activatesAccountAndIssuesTokens_thenUserCanLogin() {
        String email = "verify." + UUID.randomUUID() + "@vingo.local";
        OtpSession session = registerUser(email, randomPhone());
        String otp = extractOtp(mailSender.sent.getLast().payload().html());

        var verifyResult = client.post().uri(VERIFY_OTP)
                .body(new VerifyOtpRequest(session.getOtpSessionId(), otp))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpHeaders.SET_COOKIE)
                .expectBody(TokenPair.class)
                .returnResult();

        TokenPair tokens = verifyResult.getResponseBody();
        assertThat(tokens).isNotNull();
        assertThat(tokens.getAccessToken()).isNotBlank();
        assertThat(tokens.getRefreshToken()).isNotBlank();
        assertThat(tokens.getUser().getEmail()).isEqualTo(email);
        assertThat(tokens.getUser().getStatus()).isEqualTo(User.StatusEnum.ACTIVE);
        assertThat(tokens.getUser().getRole()).isEqualTo(User.RoleEnum.BUYER);

        // Dùng lại phiên OTP đã xác thực phải bị từ chối
        Problem reused = client.post().uri(VERIFY_OTP)
                .body(new VerifyOtpRequest(session.getOtpSessionId(), otp))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(reused).isNotNull();
        assertThat(reused.getCode()).isEqualTo(Problem.CodeEnum.OTP_INVALID);

        // Sau khi kích hoạt có thể đăng nhập bằng mật khẩu
        client.post().uri(LOGIN)
                .body(new LoginRequest(email, PASSWORD))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void verifyOtp_returns400OtpInvalidThenOtpTooManyAttempts_whenWrongOtpRepeated() {
        String email = "attempts." + UUID.randomUUID() + "@vingo.local";
        OtpSession session = registerUser(email, randomPhone());
        String validOtp = extractOtp(mailSender.sent.getLast().payload().html());
        String wrongOtp = validOtp.equals("000000") ? "999999" : "000000";

        for (int i = 0; i < 3; i++) {
            Problem invalid = client.post().uri(VERIFY_OTP)
                    .body(new VerifyOtpRequest(session.getOtpSessionId(), wrongOtp))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody(Problem.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(invalid).isNotNull();
            assertThat(invalid.getCode()).isEqualTo(Problem.CodeEnum.OTP_INVALID);
        }

        // Lần thứ 4 (vượt max-attempts=3) dù nhập đúng vẫn báo OTP_TOO_MANY_ATTEMPTS
        Problem locked = client.post().uri(VERIFY_OTP)
                .body(new VerifyOtpRequest(session.getOtpSessionId(), validOtp))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(locked).isNotNull();
        assertThat(locked.getCode()).isEqualTo(Problem.CodeEnum.OTP_TOO_MANY_ATTEMPTS);
    }

    @Test
    void verifyOtp_returns400OtpExpired_whenPastExpiry() {
        String email = "expired." + UUID.randomUUID() + "@vingo.local";
        OtpSession session = registerUser(email, randomPhone());
        String validOtp = extractOtp(mailSender.sent.getLast().payload().html());

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));

        Problem expired = client.post().uri(VERIFY_OTP)
                .body(new VerifyOtpRequest(session.getOtpSessionId(), validOtp))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(expired).isNotNull();
        assertThat(expired.getCode()).isEqualTo(Problem.CodeEnum.OTP_EXPIRED);
    }

    @Test
    void resendOtp_invalidatesPreviousOtp_sendsNewOtp_andEnforcesRateLimit() {
        String email = "resend." + UUID.randomUUID() + "@vingo.local";
        OtpSession session = registerUser(email, randomPhone());
        String firstOtp = extractOtp(mailSender.sent.getLast().payload().html());

        // Gửi lại ngay trong thời gian cooldown -> 429 RATE_LIMITED + Retry-After
        var tooSoon = client.post().uri(RESEND_OTP)
                .body(new ResendOtpRequest(session.getOtpSessionId()))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(tooSoon).isNotNull();
        assertThat(tooSoon.getCode()).isEqualTo(Problem.CodeEnum.RATE_LIMITED);

        // Qua cooldown 30s -> gửi lại thành công (202)
        clock.advance(Duration.ofSeconds(31));
        client.post().uri(RESEND_OTP)
                .body(new ResendOtpRequest(session.getOtpSessionId()))
                .exchange()
                .expectStatus().isAccepted();

        assertThat(mailSender.sent).hasSize(2);
        String secondOtp = extractOtp(mailSender.sent.getLast().payload().html());

        // Mã cũ bị vô hiệu hóa (trừ trường hợp ngẫu nhiên trùng 1/10^6)
        if (!firstOtp.equals(secondOtp)) {
            Problem oldRejected = client.post().uri(VERIFY_OTP)
                    .body(new VerifyOtpRequest(session.getOtpSessionId(), firstOtp))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectBody(Problem.class)
                    .returnResult()
                    .getResponseBody();
            assertThat(oldRejected).isNotNull();
            assertThat(oldRejected.getCode()).isEqualTo(Problem.CodeEnum.OTP_INVALID);
        }

        // Mã mới xác thực thành công
        client.post().uri(VERIFY_OTP)
                .body(new VerifyOtpRequest(session.getOtpSessionId(), secondOtp))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void register_returns429RateLimited_whenExceedingPerIpLimit() {
        for (int i = 0; i < 3; i++) {
            registerUser("rl." + i + "." + UUID.randomUUID() + "@vingo.local", randomPhone());
        }

        Problem rateLimited = client.post().uri(REGISTER)
                .body(new RegisterRequest("Too Fast", "rl.overflow." + UUID.randomUUID() + "@vingo.local",
                        randomPhone(), PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(rateLimited).isNotNull();
        assertThat(rateLimited.getCode()).isEqualTo(Problem.CodeEnum.RATE_LIMITED);
    }

    @Test
    void register_cleansUpUnverifiedUserWhenEmailFails_allowingSafeRetry() {
        String email = "retry." + UUID.randomUUID() + "@vingo.local";
        String phone = randomPhone();
        mailSender.failure = new DeliveryException(true, "EMAIL_TRANSPORT_ERROR", 0);

        Problem failed = client.post().uri(REGISTER)
                .body(new RegisterRequest("Retry User", email, phone, PASSWORD))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectBody(Problem.class)
                .returnResult()
                .getResponseBody();
        assertThat(failed).isNotNull();
        assertThat(failed.getCode()).isEqualTo(Problem.CodeEnum.SERVICE_UNAVAILABLE);

        // Khi dịch vụ email phục hồi, đăng ký lại cùng email và SĐT phải thành công, không kẹt ở 409
        mailSender.failure = null;
        OtpSession recovered = registerUser(email, phone);
        assertThat(recovered).isNotNull();
        assertThat(recovered.getOtpSessionId()).isNotNull();
    }

    private OtpSession registerUser(String email, String phone) {
        return client.post().uri(REGISTER)
                .body(new RegisterRequest("Test Buyer", email, phone, PASSWORD))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(OtpSession.class)
                .returnResult()
                .getResponseBody();
    }

    private static String extractOtp(String html) {
        Matcher matcher = OTP_PATTERN.matcher(html);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String randomPhone() {
        long suffix = Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000000L);
        return String.format("09%08d", suffix);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubMailConfig {
        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    static class RecordingEmailSender implements EmailSender {
        final List<SentMail> sent = new CopyOnWriteArrayList<>();
        volatile DeliveryException failure;

        @Override
        public String send(UUID idempotencyKey, EmailPayload payload) {
            if (failure != null) {
                throw failure;
            }
            sent.add(new SentMail(idempotencyKey, payload));
            return "msg-" + idempotencyKey;
        }
    }

    record SentMail(UUID key, EmailPayload payload) {
    }
}
