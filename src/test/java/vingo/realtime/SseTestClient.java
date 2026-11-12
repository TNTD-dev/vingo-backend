package vingo.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

final class SseTestClient implements AutoCloseable {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    record Event(UUID id, String event, String data) {
    }

    private final HttpClient http;
    private final HttpResponse<InputStream> response;
    private final Thread reader;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> comments = new LinkedBlockingQueue<>();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile Throwable readFailure;
    private volatile boolean closing;

    SseTestClient(int port, String token, String lastEventId) throws Exception {
        http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(TIMEOUT).build();
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/me/stream"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream");
        if (lastEventId != null) {
            request.header("Last-Event-ID", lastEventId);
        }
        var pending = http.sendAsync(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try {
            response = pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(response.statusCode()).as("SSE HTTP status").isEqualTo(200);
        } catch (Exception | AssertionError ex) {
            pending.cancel(true);
            http.shutdownNow();
            throw ex;
        }
        reader = Thread.ofVirtual().name("sse-test-reader").start(this::read);
    }

    int statusCode() {
        return response.statusCode();
    }

    HttpHeaders headers() {
        return response.headers();
    }

    Event awaitEvent(Duration timeout) throws InterruptedException {
        Event event = events.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (event == null) {
            throw new AssertionError("Không nhận được SSE event trong " + timeout, readFailure);
        }
        return event;
    }

    void awaitComment(String expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        long remaining = timeout.toNanos();
        while (remaining > 0) {
            if (expected.equals(comments.poll(remaining, TimeUnit.NANOSECONDS))) {
                return;
            }
            remaining = deadline - System.nanoTime();
        }
        throw new AssertionError("Không nhận được SSE comment " + expected + " trong " + timeout, readFailure);
    }

    void awaitNoEvent(Duration timeout) throws InterruptedException {
        assertThat(events.poll(timeout.toNanos(), TimeUnit.NANOSECONDS)).isNull();
        // Kết nối chết không được tính là phân quyền hoặc rollback thành công.
        assertThat(closed.getCount()).as("Stream vẫn mở").isEqualTo(1);
        assertThat(readFailure).isNull();
    }

    void awaitClosed(Duration timeout) throws InterruptedException {
        assertThat(closed.await(timeout.toNanos(), TimeUnit.NANOSECONDS))
                .as("Server đóng stream trong %s", timeout).isTrue();
        assertThat(readFailure).isNull();
    }

    private void read() {
        try (var lines = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String id = null;
            String event = "message";
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = lines.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        events.add(new Event(UUID.fromString(id), event, data.substring(0, data.length() - 1)));
                    }
                    id = null;
                    event = "message";
                    data.setLength(0);
                } else if (line.startsWith(":")) {
                    comments.add(value(line.substring(1)));
                } else {
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String fieldValue = colon < 0 ? "" : value(line.substring(colon + 1));
                    switch (field) {
                        case "id" -> id = fieldValue;
                        case "event" -> event = fieldValue;
                        case "data" -> data.append(fieldValue).append('\n');
                        default -> { }
                    }
                }
            }
        } catch (IOException | RuntimeException ex) {
            if (!closing) {
                readFailure = ex;
            }
        } finally {
            closed.countDown();
        }
    }

    private static String value(String text) {
        return text.startsWith(" ") ? text.substring(1) : text;
    }

    @Override
    public void close() throws IOException, InterruptedException {
        if (closing) {
            return;
        }
        closing = true;
        http.shutdownNow();
        try {
            response.body().close();
        } finally {
            reader.interrupt();
            assertThat(reader.join(TIMEOUT)).as("Dừng reader của test").isTrue();
        }
    }
}
