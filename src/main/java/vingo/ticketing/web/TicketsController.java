package vingo.ticketing.web;

import java.util.UUID;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.TicketsApi;
import vingo.api.model.ListMyTickets200Response;
import vingo.api.model.Ticket;
import vingo.api.model.TicketStatus;
import vingo.ticketing.TicketingService;

/** API xem vé của người đang đăng nhập theo contract. */
@RestController
public class TicketsController implements TicketsApi {

    private final TicketingService ticketing;

    /** Nhận use case đọc vé để giữ controller chỉ ánh xạ HTTP. */
    public TicketsController(TicketingService ticketing) {
        this.ticketing = ticketing;
    }

    /** Trả trang vé theo thời điểm suất và trạng thái. */
    @Override
    public ResponseEntity<ListMyTickets200Response> listMyTickets(
            String when, TicketStatus status, String cursor, Integer limit) {
        return ResponseEntity.ok(ticketing.listMyTickets(when, status, cursor, limit));
    }

    /** Trả vé khi người gọi là chủ sở hữu. */
    @Override
    public ResponseEntity<Ticket> getTicket(UUID ticketId) {
        return ResponseEntity.ok(ticketing.getTicket(ticketId));
    }

    /** Trả ảnh QR không lưu cache cho chủ vé còn hiệu lực. */
    @Override
    public ResponseEntity<Resource> getTicketQr(UUID ticketId) {
        byte[] png = ticketing.ticketQrPng(ticketId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .body(new ByteArrayResource(png));
    }

    /** Đưa vé của người gọi vào outbox sau khi kiểm giới hạn gửi lại. */
    @Override
    public ResponseEntity<Void> resendMyTicket(UUID ticketId) {
        ticketing.resendMyTicket(ticketId);
        return ResponseEntity.accepted().build();
    }
}
