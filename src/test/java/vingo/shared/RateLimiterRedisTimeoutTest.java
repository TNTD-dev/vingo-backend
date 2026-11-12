package vingo.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import vingo.shared.ratelimit.RateLimiter;

class RateLimiterRedisTimeoutTest {

    @Test
    @Timeout(10)
    void check_failsOpenWithinTwoSeconds_whenRedisAcceptsConnectionButNeverReplies() throws Exception {
        var timeout = Duration.ofMillis(200);
        var accepted = new CountDownLatch(1);
        var stop = new CountDownLatch(1);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stub = executor.submit(() -> {
                try (var socket = server.accept()) {
                    accepted.countDown();
                    stop.await();
                }
                return null;
            });
            var clientConfig = LettuceClientConfiguration.builder().commandTimeout(timeout)
                    .clientOptions(ClientOptions.builder()
                            .socketOptions(SocketOptions.builder().connectTimeout(timeout).build()).build())
                    .shutdownTimeout(timeout).build();
            var config = new RedisStandaloneConfiguration(server.getInetAddress().getHostAddress(),
                    server.getLocalPort());
            var factory = new LettuceConnectionFactory(config, clientConfig);
            try {
                factory.afterPropertiesSet();
                factory.start();
                var limiter = new RateLimiter(new StringRedisTemplate(factory), Clock.systemUTC());

                assertTimeoutPreemptively(Duration.ofSeconds(2),
                        () -> limiter.check(UUID.randomUUID(), "holds", 10, Duration.ofMinutes(1)));

                assertThat(accepted.await(1, TimeUnit.SECONDS)).as("Stub accepted the Redis connection").isTrue();
            } finally {
                stop.countDown();
                try {
                    factory.destroy();
                } finally {
                    server.close();
                    stub.get(2, TimeUnit.SECONDS);
                }
            }
        }
    }
}
