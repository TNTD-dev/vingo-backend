package vingo.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;

import net.javacrumbs.shedlock.core.LockProvider;
import vingo.support.TestcontainersConfig;

/**
 * Profile worker không mở cổng HTTP (web-application-type: none) nên không có bean web/security.
 * App vẫn phải khởi động được và có LockProvider cho job nền.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"worker", "test"})
@Import(TestcontainersConfig.class)
class WorkerProfileStartupTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void workerProfile_startsWithoutWebSecurity() {
        assertThat(context.getBeanNamesForType(LockProvider.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
    }
}
