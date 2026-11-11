package vingo.booking.web;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import vingo.api.BookingApi;
import vingo.api.model.HoldRequest;
import vingo.api.model.ListMyOrders200Response;
import vingo.api.model.Order;
import vingo.api.model.OrderStatus;
import vingo.booking.BookingService;
import vingo.shared.ratelimit.RateLimiter;
import vingo.shared.security.CurrentUser;

/** Phục vụ API giữ chỗ và đơn hàng theo interface sinh từ contract. */
@RestController
public class BookingController implements BookingApi {

    private final BookingService booking;
    private final RateLimiter rateLimiter;
    private final CurrentUser currentUser;

    public BookingController(BookingService booking, RateLimiter rateLimiter, CurrentUser currentUser) {
        this.booking = booking;
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
    }

    @Override
    public ResponseEntity<Order> holdSeats(UUID showtimeId, UUID idempotencyKey, HoldRequest holdRequest) {
        rateLimiter.check(currentUser.require().id(), "holds", 10, Duration.ofMinutes(1));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(booking.holdSeats(showtimeId, idempotencyKey, holdRequest));
    }

    @Override
    public ResponseEntity<Order> getOrder(UUID orderId) {
        return ResponseEntity.ok(booking.getOrder(orderId));
    }

    @Override
    public ResponseEntity<ListMyOrders200Response> listMyOrders(OrderStatus status, String cursor, Integer limit) {
        return ResponseEntity.ok(booking.listMyOrders(status, cursor, limit));
    }

    @Override
    public ResponseEntity<Order> cancelOrder(UUID orderId) {
        return ResponseEntity.ok(booking.cancelOrder(orderId));
    }
}
