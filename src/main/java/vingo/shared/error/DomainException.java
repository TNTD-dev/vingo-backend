package vingo.shared.error;

import java.util.List;
import java.util.Map;

/**
 * Lỗi nghiệp vụ. Ném ra từ service, {@code GlobalExceptionHandler} chuyển thành ProblemDetail.
 *
 * <p>{@code properties} được thêm vào body lỗi, ví dụ gợi ý ghế khác khi SEAT_TAKEN.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    public DomainException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public DomainException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }
    
    private static DomainException layoutError(String reason, String message) {
        return new DomainException(ErrorCode.INVALID_SEAT_LAYOUT, "Sơ đồ sân không hợp lệ", Map.of("issues", List.of(Map.of(
                    "reason", reason,
                    "message", message
            )))
        );
    }

    private static DomainException layerError(String layerId, String reason, String message) {
    return new DomainException(
            ErrorCode.INVALID_SEAT_LAYOUT,
            "Sơ đồ sân không hợp lệ.",
            Map.of("issues", List.of(Map.of(
                    "layer_id", layerId,
                    "reason", reason,
                    "message", message
            )))
    );
}
}
