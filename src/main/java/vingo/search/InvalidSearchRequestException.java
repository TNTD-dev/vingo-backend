package vingo.search;

import java.util.List;
import java.util.Map;

import vingo.shared.error.DomainException;
import vingo.shared.error.ErrorCode;

/**
 * Tham số tìm kiếm sai (400 {@code VALIDATION_ERROR}). Kèm {@code errors} theo field như ví dụ lỗi trong contract.
 */
public class InvalidSearchRequestException extends DomainException {

    public InvalidSearchRequestException(String field, String message) {
        super(ErrorCode.VALIDATION_ERROR, message,
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
