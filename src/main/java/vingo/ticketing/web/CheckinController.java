package vingo.ticketing.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.CheckInApi;
import vingo.api.model.CheckInRequest;
import vingo.api.model.CheckinResult;
import vingo.api.model.ListShowtimeCheckins200Response;
import vingo.ticketing.TicketingService;

/** API quét vé và lịch sử quét dành cho ADMIN theo contract. */
@RestController
public class CheckinController implements CheckInApi {

    private final TicketingService ticketing;

    /** Nhận use case soát vé để kết thúc transaction trước khi trả kết quả HTTP. */
    public CheckinController(TicketingService ticketing) {
        this.ticketing = ticketing;
    }

    /** Trả 200 khi hợp lệ hoặc 409 kèm CheckinResult khi từ chối quét vé. */
    @Override
    public ResponseEntity<CheckinResult> checkIn(CheckInRequest checkInRequest) {
        var result = ticketing.checkIn(checkInRequest);
        return ResponseEntity.status(result.getResult() == CheckinResult.ResultEnum.OK ? 200 : 409).body(result);
    }

    /** Trả lịch sử của suất được quét với cursor. */
    @Override
    public ResponseEntity<ListShowtimeCheckins200Response> listShowtimeCheckins(
            UUID showtimeId, String cursor, Integer limit) {
        return ResponseEntity.ok(ticketing.listShowtimeCheckins(showtimeId, cursor, limit));
    }
}
