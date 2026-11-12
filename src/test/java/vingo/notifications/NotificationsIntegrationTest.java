package vingo.notifications;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vingo.notifications.internal.*;
import vingo.support.IntegrationTest;
import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

@Import(NotificationsIntegrationTest.MailTestConfig.class)
@TestPropertySource(properties="MAIL_PAYLOAD_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class NotificationsIntegrationTest extends IntegrationTest {
    static final UUID TICKET=UUID.fromString("0197f100-0000-7000-8000-000000000601");
    private final NotificationsService notifications;
    private final OutboxRepository outbox;
    private final OutboxDispatcher dispatcher;
    private final NotificationsDeliveryService delivery;
    private final PlatformTransactionManager manager;
    private final Clock clock;
    private final TestSender sender;
    private final DataSource dataSource;
    private final TicketEmailRenderer renderer;
    private TransactionTemplate tx;

    @Autowired
    NotificationsIntegrationTest(NotificationsService notifications,OutboxRepository outbox,OutboxDispatcher dispatcher,
            NotificationsDeliveryService delivery,PlatformTransactionManager manager,Clock clock,TestSender sender,
            DataSource dataSource,TicketEmailRenderer renderer) {
        this.notifications=notifications; this.outbox=outbox; this.dispatcher=dispatcher; this.delivery=delivery;
        this.manager=manager; this.clock=clock; this.sender=sender; this.dataSource=dataSource; this.renderer=renderer;
    }
    @BeforeEach
    void fixture() {
        tx=new TransactionTemplate(manager);
        new ResourceDatabasePopulator(new ClassPathResource("vingo/reporting-fixture.sql")).execute(dataSource);
        // Chỉ DB Testcontainers của class này, không DB ứng dụng.
        jdbc.sql("DELETE FROM outbox_message").update();
        sender.calls.set(0); sender.payloads.clear(); sender.ids.clear(); sender.failure=null;
    }
    @Test
    void schema_containsOnlyTeamColumns_andNoReportingViews() {
        assertThat(jdbc.sql("SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name='outbox_message'")
                .query(String.class).list()).containsExactlyInAnyOrder("id","channel","recipient","template","payload","status",
                        "attempts","send_after","last_error","created_at","sent_at");
        assertThat(jdbc.sql("SELECT count(*) FROM pg_views WHERE schemaname='public' AND viewname LIKE 'reporting_%'")
                .query(Integer.class).single()).isZero();
    }
    @Test
    void enqueue_requiresCallerTransaction_andRollsBackWithBusinessWrite() {
        assertThatThrownBy(() -> notifications.enqueueTickets("buyer@example.test",List.of(TICKET)))
                .isInstanceOf(IllegalTransactionStateException.class);
        tx.executeWithoutResult(status -> { notifications.enqueueTickets("buyer@example.test",List.of(TICKET)); status.setRollbackOnly(); });
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_message").query(Integer.class).single()).isZero();
        assertThat(sender.calls.get()).isZero();
    }
    @Test
    void enqueue_usesDatabaseUuid_andDoesNotInventProducerDedup() {
        UUID first=enqueue(),second=enqueue();
        assertThat(first.version()).isEqualTo(7);
        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.sql("SELECT payload::text FROM outbox_message WHERE id=:id").param("id",first).query(String.class).single())
                .contains("ticket_ids").doesNotContain("ciphertext","version","expiry","lease","hash","dedup");
    }
    @Test
    void worker_rendersExistingTicketOutsideTransaction_andPreservesStoredContent() {
        UUID id=enqueue(); String original=content(id);
        dispatcher.dispatchOne();
        assertThat(state(id)).isEqualTo("SENT");
        assertThat(content(id)).isEqualTo(original);
        assertThat(sender.payloads).singleElement().satisfies(payload -> {
            assertThat(payload.html()).contains("TIEN-FIXTURE-T1","Tiến report fixture");
            assertThat(payload.attachments()).isEmpty();
        });
        assertThat(dispatcher.dispatchOne()).isFalse();
    }
    /** Ép thư đến hạn để kiểm đủ năm lỗi gửi mà không chờ thời gian thực. */
    @Test
    void retryAfter_isHonored_andOnlySendErrorsConsumeAttempts() {
        UUID id=enqueue(); sender.failure=new DeliveryException(true,"EMAIL_HTTP_429",7200);
        dispatcher.dispatchOne();
        assertThat(attempts(id)).isEqualTo(1);
        assertThat(jdbc.sql("SELECT send_after>=now()+interval '7195 seconds' FROM outbox_message WHERE id=:id")
                .param("id",id).query(Boolean.class).single()).isTrue();
        assertThat(dispatcher.dispatchOne()).isFalse();
        sender.failure=new DeliveryException(true,"EMAIL_HTTP_503",0);
        for(int index=1;index<5;index++) { due(id); dispatcher.dispatchOne(); }
        assertThat(state(id)).isEqualTo("FAILED"); assertThat(attempts(id)).isEqualTo(5);
        assertThat(content(id)).contains("ticket_ids");
    }
    /** HTTP 408 không làm mất thư: worker gửi lại cùng UUID và nội dung đã đóng băng. */
    @Test
    void requestTimeout_retriesSameMessageAndPayload_thenCompletes() {
        UUID id=enqueue();
        var payloads=new CopyOnWriteArrayList<EmailPayload>();
        EmailSender recording=(key,payload) -> { payloads.add(payload); return sender.send(key,payload); };
        var worker=new OutboxDispatcher(outbox,recording,delivery,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        sender.failure=new DeliveryException(true,"EMAIL_HTTP_408",60);
        worker.dispatchOne();
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(attempts(id)).isEqualTo(1);
        String prepared=content(id);
        assertThat(jdbc.sql("SELECT send_after>=now()+interval '55 seconds' FROM outbox_message WHERE id=:id")
                .param("id",id).query(Boolean.class).single()).isTrue();
        assertThat(worker.dispatchOne()).isFalse();
        sender.failure=null;
        due(id);
        worker.dispatchOne();
        assertThat(state(id)).isEqualTo("SENT");
        assertThat(sender.ids).containsExactly(id,id);
        assertThat(payloads).hasSize(2);
        assertThat(payloads.get(0)).isEqualTo(payloads.get(1));
        assertThat(content(id)).isEqualTo(prepared);
    }
    @Test
    void permanentError_keepsPayload_andNoInventedExpiryDropsDelayedMail() {
        UUID id=enqueue(); String original=content(id);
        jdbc.sql("UPDATE outbox_message SET created_at=now()-interval '3 days' WHERE id=:id").param("id",id).update();
        sender.failure=new DeliveryException(false,"EMAIL_HTTP_422",0); dispatcher.dispatchOne();
        assertThat(state(id)).isEqualTo("FAILED"); assertThat(content(id)).isEqualTo(original);
        assertThat(sender.calls.get()).isEqualTo(1);
    }
    @Test
    void crashAfterClaim_canRetryAtSendAfter_andStaleCompletionCannotOverwriteNewAttempt() {
        UUID id=enqueue();
        var first=tx.execute(status -> outbox.claim().orElseThrow());
        var unavailable=tx.execute(status -> outbox.claim()); assertThat(unavailable).isEmpty();
        due(id); var second=tx.execute(status -> outbox.claim().orElseThrow());
        Boolean stale=tx.execute(status -> outbox.complete(first));
        Boolean current=tx.execute(status -> outbox.complete(second));
        assertThat(stale).isFalse(); assertThat(current).isTrue();
        assertThat(first.id()).isEqualTo(second.id()); assertThat(attempts(id)).isZero();
    }
    /** Tám dispatcher tranh một row để kiểm claim không dẫn tới nhiều lần gọi provider. */
    @Test
    void concurrentDispatchers_selectOneDueMessageOnly() throws Exception {
        enqueue();
        try(var pool=Executors.newFixedThreadPool(8)) {
            var tasks=java.util.stream.IntStream.range(0,8).<java.util.concurrent.Callable<Boolean>>mapToObj(i -> dispatcher::dispatchOne).toList();
            for(var result:pool.invokeAll(tasks)) result.get();
        }
        assertThat(sender.calls.get()).isEqualTo(1);
    }
    /** Thiếu/sai khóa quá năm lượt vẫn giữ payload và phục hồi sau khi dùng reader đúng. */
    @Test
    void missingAndWrongLegacyKey_preserveContentAndRecoverAfterSevenAttempts() {
        for(String key:new String[]{"",java.util.Base64.getEncoder().encodeToString("11111111111111111111111111111111".getBytes(java.nio.charset.StandardCharsets.US_ASCII))}) {
            UUID id=legacy(); String original=content(id); int before=sender.calls.get();
            var settings=new NotificationSettings(key,"","","");
            var reader=new OutboxRepository(jdbc,new PayloadCipher(settings),renderer);
            var worker=new OutboxDispatcher(reader,sender,delivery,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            for(int index=0;index<7;index++) { due(id); worker.dispatchOne(); assertThat(attempts(id)).isZero(); assertThat(content(id)).isEqualTo(original); }
            assertThat(state(id)).isEqualTo("PENDING"); assertThat(sender.calls.get()).isEqualTo(before);
            due(id); dispatcher.dispatchOne(); assertThat(state(id)).isEqualTo("SENT"); assertThat(content(id)).isEqualTo(original);
        }
    }
    @Test
    void invalidPayloadOrUnavailableTicket_isRetainedForReview() {
        UUID id=enqueue(); jdbc.sql("UPDATE outbox_message SET payload='{}'::jsonb WHERE id=:id").param("id",id).update();
        dispatcher.dispatchOne(); assertThat(state(id)).isEqualTo("PENDING"); assertThat(attempts(id)).isZero();
        UUID missing=tx.execute(status -> notifications.enqueueTickets("buyer@example.test",List.of(UUID.randomUUID())));
        dispatcher.dispatchOne(); assertThat(state(missing)).isEqualTo("PENDING"); assertThat(content(missing)).contains("ticket_ids");
        assertThat(sender.calls.get()).isZero();
    }
    @Test
    void missingProviderConfiguration_preservesData_andCanRecover() {
        UUID id=enqueue(); String original=content(id);
        var unavailable=new ResendEmailSender(new NotificationSettings("","test@example.test","","https://api.resend.com"),clock);
        new OutboxDispatcher(outbox,unavailable,delivery,new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).dispatchOne();
        assertThat(state(id)).isEqualTo("PENDING"); assertThat(attempts(id)).isZero(); assertThat(content(id)).isEqualTo(original);
        due(id); dispatcher.dispatchOne(); assertThat(state(id)).isEqualTo("SENT");
    }
    /** Mỗi mã 401/403 được thử qua bảy lượt để phát hiện việc vô tình chuyển FAILED. */
    @Test
    void providerAuthenticationErrors_pauseWithoutConsumingAttempts_andRecoverAfterConfigurationRepair() {
        for (String code : List.of("EMAIL_HTTP_401", "EMAIL_HTTP_403")) {
            UUID id = enqueue();
            String original = content(id);
            sender.failure = new DeliveryException(false, code, 0);
            for (int index = 0; index < 7; index++) {
                due(id);
                dispatcher.dispatchOne();
                assertThat(state(id)).isEqualTo("PENDING");
                assertThat(attempts(id)).isZero();
                assertThat(content(id)).isEqualTo(original);
            }
            sender.failure = null;
            due(id);
            dispatcher.dispatchOne();
            assertThat(state(id)).isEqualTo("SENT");
            assertThat(sender.ids).filteredOn(id::equals).hasSize(8);
        }
    }
    @Test
    void retryAfterBeyondOneDay_isNotShortened_andPreservesMessage() {
        UUID id = enqueue();
        String original = content(id);
        sender.failure = new DeliveryException(true, "EMAIL_HTTP_429", 172800);
        dispatcher.dispatchOne();
        assertThat(jdbc.sql("SELECT send_after>=now()+interval '172795 seconds' FROM outbox_message WHERE id=:id")
                .param("id", id).query(Boolean.class).single()).isTrue();
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(content(id)).isEqualTo(original);
        assertThat(dispatcher.dispatchOne()).isFalse();
    }
    @Test
    void idempotencyConflict_preservesMessageForReconciliation_withoutAutomaticResend() {
        UUID id = enqueue();
        String original = content(id);
        sender.failure = new DeliveryException(false, "EMAIL_IDEMPOTENCY_CONFLICT", 0);
        dispatcher.dispatchOne();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(content(id)).isEqualTo(original);
        assertThat(jdbc.sql("SELECT last_error FROM outbox_message WHERE id=:id")
                .param("id", id).query(String.class).single()).isEqualTo("EMAIL_IDEMPOTENCY_CONFLICT");
        due(id);
        sender.failure = null;
        assertThat(dispatcher.dispatchOne()).isFalse();
        assertThat(sender.ids).containsExactly(id);
    }
    @Test
    void unexpectedSenderFailure_isObservedWithoutLeakingBody_andPreservesData() {
        UUID id=enqueue(); String original=content(id);
        EmailSender broken=(key,payload) -> { throw new IllegalArgumentException("private-provider-body"); };
        new OutboxDispatcher(outbox,broken,delivery,new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).dispatchOne();
        assertThat(state(id)).isEqualTo("PENDING"); assertThat(content(id)).isEqualTo(original);
        due(id); dispatcher.dispatchOne(); assertThat(state(id)).isEqualTo("SENT");
    }
    @Test
    void otp_isNotStored_untilHashOnlyDeliveryIsApproved() {
        assertThatThrownBy(() -> tx.execute(status -> notifications.enqueueOtp("test","buyer@example.test","654321",
                OffsetDateTime.ofInstant(clock.instant().plusSeconds(300),ZoneOffset.UTC)))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_message").query(Integer.class).single()).isZero();
    }
    /** Hai facade dùng lỗi chung cho recipient; mọi dạng danh sách vé sai đều không ghi/gửi thư. */
    @Test
    void inputs_returnValidationDomainException_withoutWritingOrSendingAnything() {
        var future=OffsetDateTime.ofInstant(clock.instant().plusSeconds(300),ZoneOffset.UTC);
        for(String recipient:new String[]{null,"","invalid","buyer @example.test","a".repeat(250)+"@b.test"}) {
            assertValidation(() -> notifications.enqueueTickets(recipient,List.of(TICKET)));
            assertValidation(() -> notifications.enqueueOtp("test",recipient,"654321",future));
        }
        List<List<UUID>> invalidIds=java.util.Arrays.asList(null,List.of(),java.util.Collections.singletonList(null),
                List.of(TICKET,TICKET),java.util.stream.IntStream.range(0,21).mapToObj(index -> UUID.randomUUID()).toList());
        for(List<UUID> ids:invalidIds) {
            assertValidation(() -> notifications.enqueueTickets("buyer@example.test",ids));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_message").query(Integer.class).single()).isZero();
        assertThat(sender.calls.get()).isZero();
    }
    /** OTP sai hình dạng/thời hạn là lỗi đầu vào, tách khỏi nhánh thiết kế OTP chưa hỗ trợ. */
    @Test
    void invalidOtpInputs_returnValidationDomainException_withoutWritingOrSendingAnything() {
        var now=OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC);
        for(String code:new String[]{null,"12345","1234567","12a456"}) {
            assertValidation(() -> notifications.enqueueOtp("test","buyer@example.test",code,now.plusSeconds(300)));
        }
        for(OffsetDateTime expiresAt:new OffsetDateTime[]{null,now.minusSeconds(1),now}) {
            assertValidation(() -> notifications.enqueueOtp("test","buyer@example.test","654321",expiresAt));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_message").query(Integer.class).single()).isZero();
        assertThat(sender.calls.get()).isZero();
    }
    /** Gọi service qua transaction thực và kiểm cả loại lỗi, code cùng HTTP status đã định nghĩa. */
    private void assertValidation(Runnable action) {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> action.run()))
                .isInstanceOfSatisfying(DomainException.class,error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(error.code().status().value()).isEqualTo(400);
                });
    }
    /** Đi qua dispatcher thật: dấu gửi phải commit trước HTTP và còn sau crash trước SENT. */
    @Test
    void providerAcceptedButWorkerStoppedBeforeUpdate_retriesSameMessageId() {
        class SimulatedWorkerCrash extends Error {}
        UUID id=enqueue();
        EmailSender crashing=(key,payload) -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.sql("SELECT jsonb_exists(payload,'delivery_started_at') FROM outbox_message WHERE id=:id")
                    .param("id",key).query(Boolean.class).single()).isTrue();
            sender.send(key,payload);
            throw new SimulatedWorkerCrash();
        };
        var firstWorker=new OutboxDispatcher(outbox,crashing,delivery,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        assertThatThrownBy(firstWorker::dispatchOne).isExactlyInstanceOf(SimulatedWorkerCrash.class);
        assertThat(state(id)).isEqualTo("PENDING");
        assertThat(attempts(id)).isZero();
        String started=jdbc.sql("SELECT payload->>'delivery_started_at' FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single();
        var restartedReader=new OutboxRepository(jdbc,new PayloadCipher(new NotificationSettings("","","","")),renderer);
        var restartedWorker=new OutboxDispatcher(restartedReader,sender,delivery,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        due(id); restartedWorker.dispatchOne();
        assertThat(sender.ids).containsExactly(id,id);
        assertThat(sender.payloads).hasSize(2);
        assertThat(sender.payloads.get(0)).isEqualTo(sender.payloads.get(1));
        assertThat(jdbc.sql("SELECT payload->>'delivery_started_at' FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo(started);
        assertThat(state(id)).isEqualTo("SENT");
        assertThat(content(id)).contains("ticket_ids");
    }
    @Test
    void freshTicketDelivery_doesNotRequireTheLegacyEncryptionKey() {
        UUID id=enqueue();
        var settings=new NotificationSettings("","", "", "");
        var reader=new OutboxRepository(jdbc,new PayloadCipher(settings),renderer);
        new OutboxDispatcher(reader,sender,delivery,new io.micrometer.core.instrument.simple.SimpleMeterRegistry()).dispatchOne();
        assertThat(state(id)).isEqualTo("SENT");
    }
    /** Khóa sai Base64/độ dài không chặn mail mới; mail legacy chờ đúng khóa rồi phục hồi. */
    @Test
    void malformedLegacyKey_doesNotBlockFreshMail_andPreservesLegacyMailUntilRepaired() {
        for (String badKey : List.of("not-base64!", "YQ==")) {
            var reader = new OutboxRepository(jdbc,
                    new PayloadCipher(new NotificationSettings(badKey, "", "", "")), renderer);
            var worker = new OutboxDispatcher(reader, sender, delivery,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
            UUID fresh = enqueue();
            worker.dispatchOne();
            assertThat(state(fresh)).isEqualTo("SENT");
            UUID old = legacy();
            String original = content(old);
            worker.dispatchOne();
            assertThat(state(old)).isEqualTo("PENDING");
            assertThat(attempts(old)).isZero();
            assertThat(content(old)).isEqualTo(original);
            due(old);
            dispatcher.dispatchOne();
            assertThat(state(old)).isEqualTo("SENT");
        }
    }
    @Test
    void ticketHtml_isEscaped_withoutChangingCatalogData() {
        var payload=tx.execute(status -> {
            jdbc.sql("UPDATE event SET title='<script>event</script>' WHERE id='0197f100-0000-7000-8000-000000000201'").update();
            var rendered=renderer.render("buyer@example.test",List.of(TICKET));
            status.setRollbackOnly();
            return rendered;
        });
        assertThat(payload.html()).contains("&lt;script&gt;event&lt;/script&gt;").doesNotContain("<script>");
    }
    @Test
    void retry_preservesFullEmailAfterCatalogAndSenderChange_andRepositoryRestart() {
        UUID id=enqueue();
        var pending=tx.execute(status -> outbox.claim().orElseThrow());
        var first=delivery.prepare(pending,outbox.decode(pending)).orElseThrow();
        String stored=jdbc.sql("SELECT payload::text FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single();
        var retry=tx.execute(status -> {
            jdbc.sql("UPDATE event SET title='Changed after first send' WHERE id='0197f100-0000-7000-8000-000000000201'").update();
            var restarted=new OutboxRepository(jdbc,new PayloadCipher(new NotificationSettings("","","","")),renderer);
            var changed=restarted.prepare(pending,outbox.decode(pending),"changed@example.test").orElseThrow();
            status.setRollbackOnly();
            return changed;
        });
        assertThat(pending.id()).isEqualTo(id);
        assertThat(retry).isEqualTo(first);
        assertThat(retry.sender()).isNotBlank().isNotEqualTo("changed@example.test");
        assertThat(jdbc.sql("SELECT payload::text FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo(stored);
        assertThat(content(id)).contains("ticket_ids");
    }
    /** Stale claim không được chốt nội dung hoặc gửi HTTP sau khi worker khác nhận row. */
    @Test
    void prepare_rejectsStaleClaim_andCannotOverwriteFrozenRequest() {
        UUID id=enqueue();
        var old=delivery.claim().orElseThrow();
        var candidate=outbox.decode(old);
        due(id);
        var current=delivery.claim().orElseThrow();
        assertThat(delivery.prepare(old,candidate)).isEmpty();
        var fixed=delivery.prepare(current,candidate).orElseThrow();
        var changed=new EmailPayload("other@example.test","changed","changed",List.of());
        assertThat(delivery.prepare(current,changed)).contains(fixed);
    }
    private UUID enqueue() { return tx.execute(status -> notifications.enqueueTickets("buyer@example.test",List.of(TICKET))); }
    /** Chỉ chặn retry sau một kết quả chưa rõ, không dùng tuổi của thư làm hạn gửi. */
    @Test
    void uncertainAttemptOutsideSafeProviderWindow_requiresReconciliation_withoutAnotherHttpCall() {
        for(int age:new int[]{25*60*60,24*60*60-15}) {
        int previousCalls=sender.calls.get();
        UUID id=enqueue();
        sender.failure=new DeliveryException(true,"EMAIL_TRANSPORT_ERROR",0);
        dispatcher.dispatchOne();
        jdbc.sql("UPDATE outbox_message SET payload=jsonb_set(payload,'{delivery_started_at}',to_jsonb(now()-make_interval(secs=>:age))) WHERE id=:id")
                .param("id",id).param("age",age).update();
        String before=jdbc.sql("SELECT payload::text FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single();
        sender.failure=null;
        due(id);
        dispatcher.dispatchOne();
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(sender.calls.get()).isEqualTo(previousCalls+1);
        assertThat(jdbc.sql("SELECT last_error FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single())
                .isEqualTo("EMAIL_RECONCILIATION_REQUIRED");
        assertThat(jdbc.sql("SELECT payload::text FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo(before);
        }
    }
    /** Lỗi 401 chắc chắn từ chối không tạo hạn 24 giờ cho thư chờ sửa cấu hình. */
    @Test
    void knownRejection_canRecoverEvenForOldQueuedMail_withoutFalseUncertainty() {
        UUID id=enqueue();
        sender.failure=new DeliveryException(false,"EMAIL_HTTP_401",0);
        dispatcher.dispatchOne();
        assertThat(jdbc.sql("SELECT jsonb_exists(payload,'delivery_started_at') FROM outbox_message WHERE id=:id").param("id",id).query(Boolean.class).single()).isFalse();
        jdbc.sql("UPDATE outbox_message SET created_at=now()-interval '3 days' WHERE id=:id").param("id",id).update();
        sender.failure=null;
        due(id);
        dispatcher.dispatchOne();
        assertThat(state(id)).isEqualTo("SENT");
    }
    /** Một lần 401 sau timeout không chứng minh lần timeout trước chưa gửi thành công. */
    @Test
    void knownRejectionAfterUncertainSend_doesNotEraseEarlierUncertainty() {
        UUID id=enqueue();
        sender.failure=new DeliveryException(true,"EMAIL_TRANSPORT_ERROR",0);
        dispatcher.dispatchOne();
        String started=jdbc.sql("SELECT payload->>'delivery_started_at' FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single();
        sender.failure=new DeliveryException(false,"EMAIL_HTTP_401",0);
        due(id);
        dispatcher.dispatchOne();
        assertThat(jdbc.sql("SELECT payload->>'delivery_started_at' FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo(started);
    }
    /** Phản hồi provider hỏng phải dừng sau năm lỗi và giữ nguyên snapshot cho đối soát. */
    @Test
    void invalidProviderResponses_stopAfterFiveFailures_withFrozenContentRetained() {
        UUID id=enqueue();
        sender.failure=new DeliveryException(true,"INVALID_PROVIDER_RESPONSE",0);
        dispatcher.dispatchOne();
        String frozen=jdbc.sql("SELECT payload::text FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single();
        for(int attempt=1;attempt<5;attempt++) { due(id); dispatcher.dispatchOne(); }
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(attempts(id)).isEqualTo(5);
        assertThat(sender.calls.get()).isEqualTo(5);
        assertThat(jdbc.sql("SELECT payload::text FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single()).isEqualTo(frozen);
        due(id);
        assertThat(dispatcher.dispatchOne()).isFalse();
    }
    /** Worker dựng chậm mất claim phải bỏ gửi; worker nhận lại chỉ gửi một lần. */
    @Test
    void slowRenderer_cannotSendAfterAnotherWorkerReclaimsMessage() throws Exception {
        UUID id=enqueue();
        var entered=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var slow=new TicketEmailRenderer(jdbc) {
            @Override public EmailPayload render(String recipient,List<UUID> ids) {
                entered.countDown();
                try { assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch(InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                return renderer.render(recipient,ids);
            }
        };
        var reader=new OutboxRepository(jdbc,new PayloadCipher(new NotificationSettings("","","","")),slow);
        var worker=new OutboxDispatcher(reader,sender,delivery,new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(worker::dispatchOne);
            try {
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                due(id);
                dispatcher.dispatchOne();
            } finally { release.countDown(); }
            assertThat(first.get(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
        assertThat(state(id)).isEqualTo("SENT");
        assertThat(sender.ids).containsExactly(id);
    }
    /** Sau dựng chậm, cửa sổ gửi được làm mới và token complete phải theo lần gia hạn. */
    @Test
    void startSend_renewsExpiredClaim_andFencesPreviousCompletion() {
        UUID id=enqueue();
        var original=delivery.claim().orElseThrow();
        var expiredAt=jdbc.sql("UPDATE outbox_message SET send_after=now()-interval '1 second' WHERE id=:id RETURNING send_after")
                .param("id",id).query(OffsetDateTime.class).single();
        var expired=new OutboxRepository.Pending(original.id(),original.recipient(),original.template(),original.payload(),original.attempts(),expiredAt);
        var renewed=delivery.startSend(expired).orElseThrow();
        assertThat(renewed.retryAt()).isAfter(expiredAt.plusSeconds(25));
        assertThat(delivery.claim()).isEmpty();
        assertThat(delivery.complete(expired)).isFalse();
        assertThat(delivery.complete(renewed)).isTrue();
    }
    private UUID legacy() {
        var json=JsonMapper.builder().build();
        var payload=new EmailPayload("buyer@example.test","Legacy ticket","<p>Existing mail</p>",List.of());
        return jdbc.sql("INSERT INTO outbox_message(channel,recipient,template,payload) VALUES ('EMAIL','legacy-recipient','TICKETS',CAST(:payload AS jsonb)) RETURNING id")
                .param("payload",json.writeValueAsString(Map.of("version",1,"ciphertext",LegacyPayloadFixture.encrypt(json.writeValueAsString(payload)))))
                .query(UUID.class).single();
    }
    /** So sánh dữ liệu gốc không mất; snapshot bổ sung được kiểm riêng về tính bất biến. */
    private String content(UUID id) { return jdbc.sql("SELECT recipient || (payload-'delivery'-'delivery_sender'-'delivery_started_at')::text FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single(); }
    private String state(UUID id) { return jdbc.sql("SELECT status FROM outbox_message WHERE id=:id").param("id",id).query(String.class).single(); }
    private int attempts(UUID id) { return jdbc.sql("SELECT attempts FROM outbox_message WHERE id=:id").param("id",id).query(Integer.class).single(); }
    private void due(UUID id) { jdbc.sql("UPDATE outbox_message SET send_after=now()-interval '1 second' WHERE id=:id").param("id",id).update(); }
    @TestConfiguration(proxyBeanMethods=false)
    static class MailTestConfig { @Bean @Primary TestSender testSender() { return new TestSender(); } }
    static class TestSender implements EmailSender {
        final AtomicInteger calls=new AtomicInteger(); final List<EmailPayload> payloads=new CopyOnWriteArrayList<>();
        final List<UUID> ids=new CopyOnWriteArrayList<>(); volatile DeliveryException failure;
        /** Ghi nhận lần gọi và mô phỏng lỗi; luôn kiểm HTTP không nằm trong transaction DB. */
        @Override public String send(UUID id,EmailPayload payload) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            calls.incrementAndGet(); ids.add(id); if(failure!=null) throw failure; payloads.add(payload); return "test-"+id;
        }
    }
}
