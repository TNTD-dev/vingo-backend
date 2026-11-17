package vingo.catalog.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cấu hình tích hợp tra cứu địa điểm; thiếu API key thì client trả kết quả rỗng an toàn. */
@ConfigurationProperties(prefix = "vingo.venue-lookup")
public record GoogleVenueLookupSettings(
        String googleApiKey,
        String geminiApiKey,
        String geminiModel,
        Duration timeout
) {
    public GoogleVenueLookupSettings {
        geminiModel = geminiModel == null || geminiModel.isBlank() ? "gemini-2.5-flash" : geminiModel;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }
}
