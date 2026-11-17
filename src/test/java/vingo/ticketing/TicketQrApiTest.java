package vingo.ticketing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import javax.imageio.ImageIO;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;

import vingo.booking.BookingService;
import vingo.shared.security.Role;

class TicketQrApiTest extends TicketingTestSupport {

    @Autowired
    TicketQrApiTest(BookingService bookingService, TicketingService ticketingService,
                    PlatformTransactionManager transactionManager) {
        super(bookingService, ticketingService, transactionManager);
    }

    @Test
    void getTicketQr_returnsUncachedPngEncodingStoredTokenForIssuedOwner() throws Exception {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();

        assertThat(decodedQr(id)).isEqualTo(ticket(id).qrToken());
    }

    @Test
    void getTicketQr_encodesNewTokenAfterReissue() throws Exception {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();
        String oldToken = ticket(id).qrToken();
        assertThat(decodedQr(id)).isEqualTo(oldToken);
        String newToken = "reissued-" + UUID.randomUUID();
        jdbc.sql("UPDATE ticket SET qr_token = :token WHERE id = :id")
                .param("token", newToken).param("id", id).update();

        assertThat(decodedQr(id)).isEqualTo(newToken).isNotEqualTo(oldToken);
    }

    @Test
    void getTicketQr_returns404ForAnotherBuyersTicket() {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();

        problem(get(tokenFor(Role.BUYER), "/api/v1/tickets/{id}/qr.png", id), 404, "NOT_FOUND");
    }

    @Test
    void getTicketQr_returns404ForUnknownId() {
        problem(get(token, "/api/v1/tickets/{id}/qr.png", UUID.randomUUID()), 404, "NOT_FOUND");
    }

    @Test
    void getTicketQr_returns404ForAdminWhoDoesNotOwnTicket() {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();

        problem(get(tokenFor(Role.ADMIN), "/api/v1/tickets/{id}/qr.png", id), 404, "NOT_FOUND");
    }

    @Test
    void getTicketQr_returns401WithoutToken() {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();

        problem(get(null, "/api/v1/tickets/{id}/qr.png", id), 401, "UNAUTHORIZED");
    }

    @ParameterizedTest
    @CsvSource({"USED,TICKET_USED", "VOID,TICKET_NOT_VALID"})
    void getTicketQr_returns409ForNonIssuedTicket(String state, String code) {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();
        if (state.equals("USED")) {
            jdbc.sql("UPDATE ticket SET status = 'USED', used_at = :now WHERE id = :id")
                    .param("now", catalog.now()).param("id", id).update();
        } else {
            voidTicket(id);
        }

        problem(get(token, "/api/v1/tickets/{id}/qr.png", id), 409, code);
    }

    @Test
    void getTicketQr_preservesErrorStatusWhenAcceptingOnlyPng() {
        UUID id = issueSeats(showtime.event().seat(1)).getFirst();
        voidTicket(id);

        client.get().uri("/api/v1/tickets/{id}/qr.png", id)
                .headers(headers -> headers.setBearerAuth(token)).accept(MediaType.IMAGE_PNG)
                .exchange().expectStatus().isEqualTo(409);
        client.get().uri("/api/v1/tickets/{id}/qr.png", UUID.randomUUID())
                .headers(headers -> headers.setBearerAuth(token)).accept(MediaType.IMAGE_PNG)
                .exchange().expectStatus().isEqualTo(404);
    }

    private String decodedQr(UUID id) throws Exception {
        var response = get(token, "/api/v1/tickets/{id}/qr.png", id)
                .expectStatus().isOk().expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectBody(byte[].class).returnResult();
        assertThat(response.getResponseHeaders().getCacheControl()).contains("no-store");
        byte[] png = response.getResponseBody();
        assertThat(png).isNotNull().startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47,
                (byte) 0x0d, (byte) 0x0a, (byte) 0x1a, (byte) 0x0a);
        var image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(320);
        assertThat(image.getHeight()).isEqualTo(320);
        var result = new QRCodeReader().decode(new BinaryBitmap(
                new HybridBinarizer(new BufferedImageLuminanceSource(image))));
        assertThat(result.getResultMetadata()).containsEntry(ResultMetadataType.ERROR_CORRECTION_LEVEL, "M");
        return result.getText();
    }
}
