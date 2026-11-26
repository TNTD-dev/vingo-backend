package vingo.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import vingo.search.ZoneKind;
import vingo.search.internal.SeatmapRepository.LayoutSeat;
import vingo.search.internal.SeatmapRepository.LayoutZone;
import vingo.search.internal.SeatmapRepository.SeatmapLayout;
import vingo.support.CatalogFixtures;

class SeatSuggestionPriceTest extends SearchRepositorySpyTest {

    @Test
    void seatSuggestions_serializesNullPricesWhenLayoutHasNoTier() {
        var fixtures = new CatalogFixtures(jdbc, clock);
        fixtures.reset();
        UUID showtime = fixtures.event().showtime(s -> s.onSale().seated(1)).create().showtimeId();
        UUID zone = fixtures.zoneIds(showtime).getFirst();
        // event_zone.ticket_type_id là NOT NULL. Chỉ giả lập layout thiếu hạng, tồn kho vẫn đọc từ PostgreSQL.
        var seat = new LayoutSeat(fixtures.seatIds(zone).getFirst(), "A", 1);
        var layout = new SeatmapLayout(List.of(new LayoutZone(zone, "A", ZoneKind.SEATED, null, null, List.of(seat))));
        doReturn(layout).when(seatmaps).findLayout(any(), any());

        String body = client.get().uri("/api/v1/showtimes/" + showtime + "/seat-suggestions?zone_id=" + zone
                        + "&quantity=1").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        var result = JsonMapper.builder().build().readTree(body);
        assertThat(result.path("seats")).hasSize(1);
        assertThat(result.path("unit_price").isNull()).isTrue();
        assertThat(result.path("total").isNull()).isTrue();
    }
}
