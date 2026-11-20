package vingo.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static vingo.support.CatalogFixture.uniquePhone;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient.ResponseSpec;

import vingo.api.model.LoginRequest;
import vingo.api.model.OtpSession;
import vingo.api.model.PhoneOtpRequest;
import vingo.api.model.PhoneVerifyRequest;
import vingo.api.model.RefreshTokenRequest;
import vingo.api.model.RegisterRequest;
import vingo.api.model.TokenPair;
import vingo.api.model.User;
import vingo.api.model.VerifyOtpRequest;
import vingo.auth.internal.OtpProperties;
import vingo.shared.security.Role;
import vingo.support.IntegrationTest;
import vingo.support.RecordingSmsSender;

/** Chốt hành vi HTTP và dữ liệu SMS trước khi cài logic xác minh điện thoại. */
@Import(RegisterAndOtpIntegrationTest.StubMailConfig.class)
@TestPropertySource(properties = {"vingo.auth.otp.ttl=PT5M", "vingo.auth.otp.max-attempts=3"})
class PhoneVerificationApiTest extends IntegrationTest {

    private static final String REQUEST = "/api/v1/me/phone/otp";
    private static final String VERIFY = "/api/v1/me/phone/verify";
    private static final String ME = "/api/v1/me";
    private static final String PASSWORD = "PhoneTest#2026";

    @Autowired private RecordingSmsSender sms;
    @Autowired private RegisterAndOtpIntegrationTest.RecordingEmailSender mail;
    @Autowired private PasswordEncoder passwords;
    @Autowired private OtpProperties otpProperties;

    private final List<UUID> buyers = new ArrayList<>();
    private UUID buyer;
    private String phone;

    @BeforeEach
    void createUnverifiedBuyerAndResetSenders() {
        clock.set(Instant.now().truncatedTo(ChronoUnit.MICROS));
        sms.reset();
        mail.sent.clear();
        mail.failure = null;
        buyer = unverifiedBuyer();
        phone = uniquePhone();
    }

    @AfterEach
    void restoreClockAndReleasePhoneSendLimits() {
        // Các user phụ cũng dùng chung IP; dọn đủ lượt của ca này để không ăn hạn mức ca sau.
        jdbc.sql("DELETE FROM otp_verification WHERE user_id IN (:ids) AND purpose = 'PHONE'").param("ids", buyers).update();
        sms.reset();
        clock.reset();
    }

    @Test
    void requestPhoneOtp_neverExposesTheCodeInHeaders_whenNotInDemoMode() {
        request(buyer, phone).expectStatus().isOk().expectHeader().doesNotExist("X-Demo-Otp");
    }

    @Test
    void requestPhoneOtp_returnsSmsSession_persistsHashAndE164_andSendsOutsideTransaction() {
        String previousPhone = userPhone(buyer);
        OtpSession session = requestOk(buyer, phone);
        assertThat(session.getChannel()).isEqualTo(OtpSession.ChannelEnum.SMS);
        assertThat(session.getExpiresAt()).isEqualTo(now().plus(otpProperties.ttl()));
        assertThat(sms.sent()).hasSize(1);
        var sent = sms.sent().getFirst();
        assertThat(sent.requestId()).isNotNull();
        assertThat(sent.phone()).isEqualTo(phone);
        assertThat(sent.code()).matches("[0-9]{6}");
        assertThat(sent.minutes()).isEqualTo(5);
        var stored = snapshot(session.getOtpSessionId());
        assertThat(stored.userId()).isEqualTo(buyer);
        assertThat(stored.purpose()).isEqualTo("PHONE");
        assertThat(stored.channel()).isEqualTo("SMS");
        assertThat(stored.phone()).isEqualTo(phone);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.lastSentAt()).isEqualTo(now());
        assertThat(stored.expiresAt()).isEqualTo(session.getExpiresAt());
        assertThat(stored.consumedAt()).isNull();
        assertThat(passwords.matches(sent.code(), stored.hash())).isTrue();
        assertThat(stored.hash()).isNotEqualTo(sent.code());
        assertThat(sendCount(buyer)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT sent_at FROM otp_verification WHERE user_id = :id AND purpose = 'PHONE' AND consumed_at IS NULL")
                .param("id", buyer).query(OffsetDateTime.class).single()).isEqualTo(now());
        assertThat(userPhone(buyer)).isEqualTo(previousPhone);
        assertThat(isVerified(buyer)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0912345678", "+84912345678", "84912345678", " 0912 345 678 ", " .09-123.456-78. "})
    void requestPhoneOtp_normalizesAcceptedVietnameseFormats(String input) {
        OtpSession session = requestOk(buyer, input);
        assertThat(snapshot(session.getOtpSessionId()).phone()).isEqualTo("+84912345678");
        assertThat(sms.sent().getLast().phone()).isEqualTo("+84912345678");
    }

    @ParameterizedTest
    @ValueSource(strings = {"+12025550123", "09123", "09abc45678", "0212345678", "", "   "})
    void requestPhoneOtp_returns400ValidationError_forInvalidPhone(String input) {
        error(request(buyer, input), 400, "VALIDATION_ERROR");
        assertThat(sms.sent()).isEmpty();
        assertThat(sendCount(buyer)).isZero();
    }

    @Test
    void requestPhoneOtp_returns400ValidationError_whenPhoneMissing() {
        error(client.post().uri(REQUEST).headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .body(Map.of()).exchange(), 400, "VALIDATION_ERROR");
    }

    @Test
    void requestPhoneOtp_returns401_withoutToken() {
        error(client.post().uri(REQUEST).body(new PhoneOtpRequest(phone)).exchange(), 401, "UNAUTHORIZED");
        assertThat(sms.sent()).isEmpty();
    }

    @Test
    void requestPhoneOtp_returns409PhoneTaken_whenAnotherUserVerifiedPhone() {
        verifiedOwner(phone);
        error(request(buyer, "0" + phone.substring(3)), 409, "PHONE_TAKEN");
        assertThat(sms.sent()).isEmpty();
        assertThat(sendCount(buyer)).isEqualTo(1);
    }

    @Test
    void requestPhoneOtp_phoneTakenCountsTowardsCooldown_withoutCreatingSessionOrSendingSms() {
        verifiedOwner(phone);
        error(request(buyer, phone), 409, "PHONE_TAKEN");
        error(request(buyer, phone).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60"),
                429, "RATE_LIMITED");
        assertThat(sendCount(buyer)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM otp_verification WHERE user_id = :id AND purpose = 'PHONE' AND consumed_at IS NULL")
                .param("id", buyer).query(Integer.class).single()).isZero();
        assertThat(sms.sent()).isEmpty();
    }

    @Test
    void requestPhoneOtp_limitsFivePhoneTakenProbesPerUserAcrossDifferentPhones() {
        for (int i = 0; i < 5; i++) {
            String takenPhone = uniquePhone();
            verifiedOwner(takenPhone);
            error(request(buyer, takenPhone), 409, "PHONE_TAKEN");
            clock.advance(Duration.ofSeconds(61));
        }
        verifiedOwner(phone);
        error(request(buyer, phone).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "86095"),
                429, "RATE_LIMITED");
        assertThat(sendCount(buyer)).isEqualTo(5);
        assertThat(jdbc.sql("SELECT count(*) FROM otp_verification WHERE user_id = :id AND purpose = 'PHONE' AND consumed_at IS NULL")
                .param("id", buyer).query(Integer.class).single()).isZero();
        assertThat(sms.sent()).isEmpty();
    }

    @Test
    void requestPhoneOtp_phoneTakenKeepsOpenSession_andCooldownUsesLatestProbe() {
        OtpSession session = requestOk(buyer, phone);
        var before = snapshot(session.getOtpSessionId());
        String takenPhone = uniquePhone();
        verifiedOwner(takenPhone);
        clock.advance(Duration.ofSeconds(61));
        error(request(buyer, takenPhone), 409, "PHONE_TAKEN");
        assertThat(snapshot(session.getOtpSessionId())).isEqualTo(before);
        clock.advance(Duration.ofSeconds(17));
        error(request(buyer, phone).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "43"),
                429, "RATE_LIMITED");
        assertThat(sendCount(buyer)).isEqualTo(2);
        assertThat(snapshot(session.getOtpSessionId())).isEqualTo(before);
        assertThat(sms.sent()).hasSize(1);
        verifyOk(buyer, session.getOtpSessionId(), sms.lastCode(phone));
    }

    @Test
    void requestPhoneOtp_acceptsPhoneOnlyDeclaredByAnotherRegistration() {
        register(phone);
        requestOk(buyer, phone);
        assertThat(sms.sent()).hasSize(1);
    }

    @Test
    void requestPhoneOtp_returns429AndRemainingRetryAfter_duringUserCooldownEvenForDifferentPhone() {
        OtpSession session = requestOk(buyer, phone);
        String hash = snapshot(session.getOtpSessionId()).hash();
        clock.advance(Duration.ofSeconds(17));
        error(request(buyer, uniquePhone()).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "43"),
                429, "RATE_LIMITED");
        assertThat(sendCount(buyer)).isEqualTo(1);
        assertThat(sms.sent()).hasSize(1);
        assertThat(snapshot(session.getOtpSessionId()).hash()).isEqualTo(hash);
    }

    @Test
    void requestPhoneOtp_after61Seconds_createsNewSession_andInvalidatesOldCode() {
        OtpSession first = requestOk(buyer, phone);
        String oldCode = sms.lastCode(phone);
        String oldHash = snapshot(first.getOtpSessionId()).hash();
        error(verify(buyer, first.getOtpSessionId(), wrongCode(oldCode)), 400, "OTP_INVALID");
        OtpSession second;
        do {
            clock.advance(Duration.ofSeconds(61));
            second = requestOk(buyer, phone);
        } while (sms.lastCode(phone).equals(oldCode));
        assertThat(second.getOtpSessionId()).isNotEqualTo(first.getOtpSessionId());
        var stored = snapshot(second.getOtpSessionId());
        assertThat(stored.hash()).isNotEqualTo(oldHash);
        assertThat(stored.attempts()).isZero();
        assertThat(stored.lastSentAt()).isEqualTo(now());
        assertThat(stored.expiresAt()).isEqualTo(now().plus(otpProperties.ttl()));
        assertThat(passwords.matches(sms.lastCode(phone), stored.hash())).isTrue();
        assertThat(sms.sent()).extracting(RecordingSmsSender.SentSms::requestId).doesNotHaveDuplicates();
        error(verify(buyer, first.getOtpSessionId(), oldCode), 400, "OTP_INVALID");
        verifyOk(buyer, second.getOtpSessionId(), sms.lastCode(phone));
    }

    @Test
    void requestPhoneOtp_newPhoneCreatesNewSession() {
        OtpSession first = requestOk(buyer, phone);
        String replacement = uniquePhone();
        clock.advance(Duration.ofSeconds(61));
        OtpSession second = requestOk(buyer, replacement);
        assertThat(second.getOtpSessionId()).isNotEqualTo(first.getOtpSessionId());
        assertThat(snapshot(second.getOtpSessionId()).phone()).isEqualTo(replacement);
        assertThat(verifyOk(buyer, second.getOtpSessionId(), sms.lastCode(replacement)).getPhone()).isEqualTo(replacement);
    }

    @Test
    void requestPhoneOtp_limitsFivePerUserAcrossDifferentPhones_andAllowsAfter24Hours() {
        for (int i = 0; i < 5; i++) {
            requestOk(buyer, uniquePhone());
            clock.advance(Duration.ofSeconds(61));
        }
        error(request(buyer, phone).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "86095"),
                429, "RATE_LIMITED");
        assertThat(sendCount(buyer)).isEqualTo(5);
        assertThat(sms.sent()).hasSize(5);
        clock.advance(Duration.ofHours(24));
        requestOk(buyer, phone);
        assertThat(sendCount(buyer)).isEqualTo(6);
    }

    @Test
    void requestPhoneOtp_limitsFivePerPhoneAcrossUsers_andAllowsAfter24Hours() {
        for (int i = 0; i < 5; i++) requestOk(unverifiedBuyer(), phone);
        error(request(buyer, "0" + phone.substring(3)).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "86400"),
                429, "RATE_LIMITED");
        assertThat(sendCount(buyer)).isZero();
        assertThat(sms.sent()).hasSize(5);
        clock.advance(Duration.ofHours(24).plusSeconds(1));
        requestOk(buyer, phone);
    }

    @Test
    void requestPhoneOtp_limitsTwentyPerIpAcrossDifferentUsersAndPhones() {
        clock.set(Instant.parse("2026-11-18T23:59:30Z"));
        for (int i = 0; i < 20; i++) requestOk(unverifiedBuyer(), uniquePhone());
        error(request(buyer, phone).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "86400"),
                429, "RATE_LIMITED");
        assertThat(sms.sent()).hasSize(20);
        assertThat(sendCount(buyer)).isZero();

        // Đầu vào sai vẫn trả 400, kể cả khi IP đã hết hạn mức.
        error(request(buyer, "09123"), 400, "VALIDATION_ERROR");
        assertThat(sendCount(buyer)).isZero();

        clock.advance(Duration.ofMinutes(1));
        error(request(buyer, phone).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "86340"),
                429, "RATE_LIMITED");
        clock.advance(Duration.ofHours(24).minusSeconds(61));
        error(request(buyer, phone).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "1"),
                429, "RATE_LIMITED");
        clock.advance(Duration.ofSeconds(1));
        requestOk(buyer, phone);
        assertThat(sms.sent()).hasSize(21);
        assertThat(sendCount(buyer)).isEqualTo(1);
    }

    @Test
    void requestPhoneOtp_returns503OnProviderFailure_keepsLogAndCooldown_thenNewSession() {
        sms.failNext();
        error(request(buyer, phone), 503, "SERVICE_UNAVAILABLE");
        assertThat(sendCount(buyer)).isEqualTo(1);
        UUID session = jdbc.sql("SELECT id FROM otp_verification WHERE user_id = :id AND purpose = 'PHONE' AND consumed_at IS NULL")
                .param("id", buyer).query(UUID.class).single();
        error(request(buyer, phone).expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "60"), 429, "RATE_LIMITED");
        clock.advance(Duration.ofSeconds(61));
        assertThat(requestOk(buyer, phone).getOtpSessionId()).isNotEqualTo(session);
        assertThat(sendCount(buyer)).isEqualTo(2);
        assertThat(sms.sent()).extracting(RecordingSmsSender.SentSms::requestId).doesNotHaveDuplicates();
    }

    @Test
    void verifyPhoneOtp_returnsVerifiedUser_andConsumesSessionAtomically() {
        OtpSession session = requestOk(buyer, phone);
        User user = verifyOk(buyer, session.getOtpSessionId(), sms.lastCode(phone));
        assertThat(user.getId()).isEqualTo(buyer);
        assertThat(user.getPhone()).isEqualTo(phone);
        assertThat(user.getPhoneVerified()).isTrue();
        assertThat(userPhone(buyer)).isEqualTo(phone);
        assertThat(jdbc.sql("SELECT phone_verified_at FROM user_account WHERE id = :id")
                .param("id", buyer).query(OffsetDateTime.class).single()).isEqualTo(now());
        assertThat(snapshot(session.getOtpSessionId()).consumedAt()).isEqualTo(now());
    }

    @Test
    void verifyPhoneOtp_returns400OtpInvalid_andCommitsAttemptForWrongCode() {
        OtpSession session = requestOk(buyer, phone);
        error(verify(buyer, session.getOtpSessionId(), wrongCode(sms.lastCode(phone))), 400, "OTP_INVALID");
        assertThat(snapshot(session.getOtpSessionId()).attempts()).isEqualTo(1);
        assertThat(snapshot(session.getOtpSessionId()).consumedAt()).isNull();
        assertThat(isVerified(buyer)).isFalse();
    }

    @Test
    void verifyPhoneOtp_returns400OtpTooManyAttempts_afterConfiguredMaximum() {
        OtpSession session = requestOk(buyer, phone);
        String code = sms.lastCode(phone);
        for (int i = 0; i < otpProperties.maxAttempts(); i++) {
            error(verify(buyer, session.getOtpSessionId(), wrongCode(code)), 400, "OTP_INVALID");
        }
        error(verify(buyer, session.getOtpSessionId(), code), 400, "OTP_TOO_MANY_ATTEMPTS");
        assertThat(snapshot(session.getOtpSessionId()).attempts()).isEqualTo(otpProperties.maxAttempts());
        assertThat(isVerified(buyer)).isFalse();
    }

    @Test
    void verifyPhoneOtp_returns400OtpExpired_atExpiry() {
        OtpSession session = requestOk(buyer, phone);
        clock.set(session.getExpiresAt().toInstant());
        error(verify(buyer, session.getOtpSessionId(), sms.lastCode(phone)), 400, "OTP_EXPIRED");
        assertThat(isVerified(buyer)).isFalse();
    }

    @Test
    void verifyPhoneOtp_returns400OtpInvalid_whenSessionAlreadyConsumed() {
        OtpSession session = requestOk(buyer, phone);
        String code = sms.lastCode(phone);
        verifyOk(buyer, session.getOtpSessionId(), code);
        error(verify(buyer, session.getOtpSessionId(), code), 400, "OTP_INVALID");
    }

    @Test
    void verifyPhoneOtp_returns400OtpInvalid_forAnotherUsersSession_withoutChangingAttempts() {
        OtpSession session = requestOk(buyer, phone);
        UUID intruder = unverifiedBuyer();
        error(verify(intruder, session.getOtpSessionId(), sms.lastCode(phone)), 400, "OTP_INVALID");
        assertThat(snapshot(session.getOtpSessionId()).attempts()).isZero();
        assertThat(snapshot(session.getOtpSessionId()).consumedAt()).isNull();
        assertThat(isVerified(intruder)).isFalse();
        verifyOk(buyer, session.getOtpSessionId(), sms.lastCode(phone));
    }

    @Test
    void verifyPhoneOtp_returns400OtpInvalid_forMissingSession() {
        error(verify(buyer, UUID.randomUUID(), "123456"), 400, "OTP_INVALID");
    }

    @Test
    void verifyPhoneOtp_returns400OtpInvalid_forEmailPurpose() {
        UUID session = jdbc.sql("""
                INSERT INTO otp_verification (user_id, purpose, client_key, code_hash, sent_at, expires_at)
                VALUES (:user, 'VERIFY', :clientKey, :hash, :now, :expires) RETURNING id
                """).param("user", buyer).param("clientKey", UUID.randomUUID())
                .param("hash", passwords.encode("123456"))
                .param("now", now()).param("expires", now().plusMinutes(5)).query(UUID.class).single();
        error(verify(buyer, session, "123456"), 400, "OTP_INVALID");
        assertThat(snapshot(session).consumedAt()).isNull();
    }

    @Test
    void verifyPhoneOtp_returns409PhoneTaken_whenAnotherPendingClaimWasVerifiedFirst() {
        UUID other = unverifiedBuyer();
        OtpSession first = requestOk(buyer, phone);
        String firstCode = sms.lastCode(phone);
        OtpSession second = requestOk(other, phone);
        String secondCode = sms.lastCode(phone);
        verifyOk(buyer, first.getOtpSessionId(), firstCode);
        error(verify(other, second.getOtpSessionId(), secondCode), 409, "PHONE_TAKEN");
        assertThat(isVerified(other)).isFalse();
        assertThat(snapshot(second.getOtpSessionId()).consumedAt()).isNull();
        assertThat(userPhone(buyer)).isEqualTo(phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567", "12a456", ""})
    void verifyPhoneOtp_returns400ValidationError_forMalformedCode(String otp) {
        OtpSession session = requestOk(buyer, phone);
        error(verify(buyer, session.getOtpSessionId(), otp), 400, "VALIDATION_ERROR");
        assertThat(snapshot(session.getOtpSessionId()).attempts()).isZero();
    }

    @Test
    void verifyPhoneOtp_returns400ValidationError_forMalformedSessionId() {
        error(client.post().uri(VERIFY).headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .body(Map.of("otp_session_id", "not-a-uuid", "otp", "123456")).exchange(), 400, "VALIDATION_ERROR");
    }

    @Test
    void verifyPhoneOtp_returns401_withoutToken() {
        error(client.post().uri(VERIFY).body(new PhoneVerifyRequest(UUID.randomUUID(), "123456"))
                .exchange(), 401, "UNAUTHORIZED");
    }

    @Test
    void updateMe_clearsVerification_whenPhoneChanges() {
        verifyBuyer();
        String replacement = uniquePhone();
        client.patch().uri(ME).headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .body(Map.of("phone", "0" + replacement.substring(3))).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.phone_verified").isEqualTo(false);
        assertThat(isVerified(buyer)).isFalse();
        assertThat(userPhone(buyer)).isEqualTo(replacement);
    }

    @Test
    void updateMe_keepsVerificationTimestamp_whenNormalizedPhoneIsUnchanged() {
        verifyBuyer();
        OffsetDateTime verifiedAt = now();
        clock.advance(Duration.ofSeconds(1));
        client.patch().uri(ME).headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .body(Map.of("phone", "0" + phone.substring(3))).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.phone_verified").isEqualTo(true)
                .jsonPath("$.phone").isEqualTo(phone);
        assertThat(jdbc.sql("SELECT phone_verified_at FROM user_account WHERE id = :id")
                .param("id", buyer).query(OffsetDateTime.class).single()).isEqualTo(verifiedAt);
    }

    @Test
    void updateMe_keepsVerification_whenOnlyNameChanges() {
        verifyBuyer();
        client.patch().uri(ME).headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .body(Map.of("full_name", "New Name")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.phone_verified").isEqualTo(true);
        assertThat(userPhone(buyer)).isEqualTo(phone);
    }

    @Test
    void deleteMe_clearsPhoneAndVerification_andReleasesPhoneForAnotherUser() {
        verifyBuyer();
        client.delete().uri(ME).headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .exchange().expectStatus().isNoContent();
        assertThat(jdbc.sql("SELECT phone IS NULL AND phone_verified_at IS NULL FROM user_account WHERE id = :id")
                .param("id", buyer).query(Boolean.class).single()).isTrue();
        UUID other = unverifiedBuyer();
        OtpSession session = requestOk(other, phone);
        assertThat(verifyOk(other, session.getOtpSessionId(), sms.lastCode(phone)).getPhoneVerified()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void getMe_loginAndRefresh_alwaysExposeCurrentPhoneVerification(boolean verified) {
        if (verified) verifyBuyer();
        jdbc.sql("UPDATE user_account SET password_hash = :hash WHERE id = :id")
                .param("hash", passwords.encode(PASSWORD)).param("id", buyer).update();
        String email = jdbc.sql("SELECT email FROM user_account WHERE id = :id")
                .param("id", buyer).query(String.class).single();
        client.get().uri(ME).headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.phone_verified").isEqualTo(verified);
        TokenPair login = client.post().uri("/api/v1/auth/login").body(new LoginRequest(email, PASSWORD))
                .exchange().expectStatus().isOk().expectBody(TokenPair.class).returnResult().getResponseBody();
        assertThat(login).isNotNull();
        assertThat(login.getUser().getPhoneVerified()).isEqualTo(verified);
        client.post().uri("/api/v1/auth/refresh").body(new RefreshTokenRequest(login.getRefreshToken()))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.user.phone_verified").isEqualTo(verified);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void verifyEmailOtp_preservesCurrentPhoneVerification_includingFalseForNewRegistration(boolean verified) {
        OtpSession session = register(phone);
        if (verified) {
            jdbc.sql("UPDATE user_account SET phone_verified_at = :now WHERE id = (SELECT user_id FROM otp_verification WHERE id = :session)")
                    .param("now", now()).param("session", session.getOtpSessionId()).update();
        }
        var matcher = java.util.regex.Pattern.compile("<strong>(\\d{6})</strong>")
                .matcher(mail.sent.getLast().payload().html());
        assertThat(matcher.find()).isTrue();
        client.post().uri("/api/v1/auth/otp/verify")
                .body(new VerifyOtpRequest(session.getOtpSessionId(), matcher.group(1)))
                .exchange().expectStatus().isOk().expectBody().jsonPath("$.user.phone_verified").isEqualTo(verified);
    }

    @Test
    void register_returns409PhoneTaken_forDifferentFormatOfVerifiedPhone() {
        verifiedOwner(phone);
        error(client.post().uri("/api/v1/auth/register")
                .body(new RegisterRequest("Buyer", UUID.randomUUID() + "@phone.test", "0" + phone.substring(3), PASSWORD))
                .exchange(), 409, "PHONE_TAKEN");
    }

    @Test
    void register_acceptsDuplicateUnverifiedPhone_andNormalizesToE164WithoutVerification() {
        String localPhone = "0" + phone.substring(3);
        register(localPhone);
        OtpSession second = register(localPhone);
        var stored = jdbc.sql("""
                SELECT u.phone, u.phone_verified_at IS NOT NULL AS verified
                FROM user_account u JOIN otp_verification o ON o.user_id = u.id WHERE o.id = :id
                """).param("id", second.getOtpSessionId())
                .query((rs, row) -> Map.<String, Object>of("phone", rs.getString("phone"), "verified", rs.getBoolean("verified"))).single();
        assertThat(stored).containsEntry("phone", phone).containsEntry("verified", false);
    }

    @Test
    void register_returns409PhoneTaken_forExactLegacyNonVietnameseVerifiedPhone() {
        String legacy = "+1202" + uniquePhone().substring(4);
        verifiedOwner(legacy);
        error(client.post().uri("/api/v1/auth/register")
                .body(new RegisterRequest("Buyer", UUID.randomUUID() + "@phone.test", legacy, PASSWORD))
                .exchange(), 409, "PHONE_TAKEN");
    }

    @Test
    void updateMe_clearsVerification_whenRemovingPhone() {
        verifyBuyer();
        client.patch().uri(ME).headers(h -> h.setBearerAuth(tokenFor(buyer, Role.BUYER)))
                .body(Map.of("phone", "")).exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.phone_verified").isEqualTo(false);
        assertThat(jdbc.sql("SELECT phone IS NULL AND phone_verified_at IS NULL FROM user_account WHERE id = :id")
                .param("id", buyer).query(Boolean.class).single()).isTrue();
    }

    @Test
    void requestPhoneOtp_allowsOwnVerifiedPhone_andKeepsVerificationWhilePending() {
        verifyBuyer();
        clock.advance(Duration.ofSeconds(61));
        requestOk(buyer, phone);
        assertThat(isVerified(buyer)).isTrue();
        assertThat(userPhone(buyer)).isEqualTo(phone);
    }

    private UUID unverifiedBuyer() {
        UUID id = catalog.buyer();
        buyers.add(id);
        jdbc.sql("UPDATE user_account SET phone_verified_at = NULL WHERE id = :id").param("id", id).update();
        return id;
    }

    private void verifiedOwner(String number) {
        jdbc.sql("UPDATE user_account SET phone = :phone WHERE id = :id")
                .param("phone", number).param("id", catalog.buyer()).update();
    }

    private void verifyBuyer() {
        OtpSession session = requestOk(buyer, phone);
        verifyOk(buyer, session.getOtpSessionId(), sms.lastCode(phone));
    }

    private ResponseSpec request(UUID user, String number) {
        return client.post().uri(REQUEST).headers(h -> h.setBearerAuth(tokenFor(user, Role.BUYER)))
                .body(new PhoneOtpRequest(number)).exchange();
    }

    private OtpSession requestOk(UUID user, String number) {
        OtpSession result = request(user, number).expectStatus().isOk()
                .expectBody(OtpSession.class).returnResult().getResponseBody();
        assertThat(result).isNotNull();
        assertThat(result.getOtpSessionId()).isNotNull();
        return result;
    }

    private ResponseSpec verify(UUID user, UUID session, String code) {
        return client.post().uri(VERIFY).headers(h -> h.setBearerAuth(tokenFor(user, Role.BUYER)))
                .body(new PhoneVerifyRequest(session, code)).exchange();
    }

    private User verifyOk(UUID user, UUID session, String code) {
        User result = verify(user, session, code).expectStatus().isOk()
                .expectBody(User.class).returnResult().getResponseBody();
        assertThat(result).isNotNull();
        return result;
    }

    private OtpSession register(String number) {
        return client.post().uri("/api/v1/auth/register")
                .body(new RegisterRequest("Buyer", UUID.randomUUID() + "@phone.test", number, PASSWORD))
                .exchange().expectStatus().isCreated().expectBody(OtpSession.class).returnResult().getResponseBody();
    }

    private void error(ResponseSpec response, int status, String code) {
        response.expectStatus().isEqualTo(status).expectBody().jsonPath("$.code").isEqualTo(code);
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock); }

    private String userPhone(UUID id) {
        return jdbc.sql("SELECT phone FROM user_account WHERE id = :id").param("id", id).query(String.class).single();
    }

    private boolean isVerified(UUID id) {
        return jdbc.sql("SELECT phone_verified_at IS NOT NULL FROM user_account WHERE id = :id")
                .param("id", id).query(Boolean.class).single();
    }

    private int sendCount(UUID id) {
        return jdbc.sql("SELECT count(*) FROM otp_verification WHERE user_id = :id AND purpose = 'PHONE'")
                .param("id", id).query(Integer.class).single();
    }

    private static String wrongCode(String correct) { return correct.equals("000000") ? "000001" : "000000"; }

    private SessionSnapshot snapshot(UUID session) {
        return jdbc.sql("SELECT * FROM otp_verification WHERE id = :id").param("id", session)
                .query((rs, row) -> new SessionSnapshot(rs.getObject("user_id", UUID.class), rs.getString("purpose"),
                        "SMS", rs.getString("phone"), rs.getString("code_hash"), rs.getInt("attempts"),
                        rs.getObject("sent_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("consumed_at", OffsetDateTime.class))).single();
    }

    private record SessionSnapshot(UUID userId, String purpose, String channel, String phone, String hash,
                                   int attempts, OffsetDateTime lastSentAt, OffsetDateTime expiresAt,
                                   OffsetDateTime consumedAt) {}
}
