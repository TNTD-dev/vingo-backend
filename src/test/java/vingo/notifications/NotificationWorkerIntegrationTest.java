package vingo.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import vingo.support.TestcontainersConfig;

/** Worker không có HTTP nên dùng cùng kiểu boot test như WorkerProfileStartupTest của team. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"worker", "test"})
@Import({TestcontainersConfig.class, NotificationsIntegrationTest.MailTestConfig.class})
@TestPropertySource(properties = {"spring.flyway.enabled=true", "vingo.jobs.outbox-sender=PT0.1S",
        "MAIL_PAYLOAD_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class NotificationWorkerIntegrationTest {
    private final ApplicationContext context;
    private final NotificationsService notifications;
    private final PlatformTransactionManager manager;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final NotificationsIntegrationTest.TestSender sender;

    @Autowired
    NotificationWorkerIntegrationTest(ApplicationContext context, NotificationsService notifications, PlatformTransactionManager manager, JdbcClient jdbc, Clock clock, NotificationsIntegrationTest.TestSender sender) {
        this.context = context;
        this.notifications = notifications;
        this.manager = manager;
        this.jdbc = jdbc;
        this.clock = clock;
        this.sender = sender;
    }

    @Test
    void realWorkerJob_usesSharedLockProviderAndSendsCommittedEmail_withoutWebServer() {
        assertThat(context.getBeanNamesForType(LockProvider.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource("vingo/reporting-fixture.sql"))
                .execute(context.getBean(javax.sql.DataSource.class));
        UUID id = new TransactionTemplate(manager).execute(status -> notifications.enqueueTickets(
                "buyer@example.test", java.util.List.of(NotificationsIntegrationTest.TICKET)));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.sql("SELECT status FROM outbox_message WHERE id=:id").param("id", id)
                        .query(String.class).single()).isEqualTo("SENT"));
        assertThat(sender.calls.get()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM shedlock WHERE name='outbox_sender'").query(Integer.class).single()).isEqualTo(1);
    }
}
