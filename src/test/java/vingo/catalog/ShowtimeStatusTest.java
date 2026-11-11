package vingo.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** Unit test cho hàm thuần {@link ShowtimeStatus#of}, phủ từng mốc biên. */
class ShowtimeStatusTest {

    private static final OffsetDateTime OPENS = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime CLOSES = OffsetDateTime.of(2026, 11, 20, 18, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ENDS = OffsetDateTime.of(2026, 11, 20, 21, 0, 0, 0, ZoneOffset.UTC);

    private static ShowtimeStatus scheduledAt(OffsetDateTime now) {
        return ShowtimeStatus.of("SCHEDULED", OPENS, CLOSES, ENDS, now);
    }

    @Test
    void of_returnsScheduled_oneSecondBeforeSaleOpensAt() {
        assertThat(scheduledAt(OPENS.minusSeconds(1))).isEqualTo(ShowtimeStatus.SCHEDULED);
    }

    @Test
    void of_returnsOnSale_exactlyAtSaleOpensAt() {
        assertThat(scheduledAt(OPENS)).isEqualTo(ShowtimeStatus.ON_SALE);
    }

    @Test
    void of_returnsOnSale_oneSecondBeforeSaleClosesAt() {
        assertThat(scheduledAt(CLOSES.minusSeconds(1))).isEqualTo(ShowtimeStatus.ON_SALE);
    }

    @Test
    void of_returnsSalesClosed_exactlyAtSaleClosesAt() {
        assertThat(scheduledAt(CLOSES)).isEqualTo(ShowtimeStatus.SALES_CLOSED);
    }

    @Test
    void of_returnsSalesClosed_oneSecondBeforeEndsAt() {
        assertThat(scheduledAt(ENDS.minusSeconds(1))).isEqualTo(ShowtimeStatus.SALES_CLOSED);
    }

    @Test
    void of_returnsFinished_exactlyAtEndsAt() {
        assertThat(scheduledAt(ENDS)).isEqualTo(ShowtimeStatus.FINISHED);
    }

    @Test
    void of_returnsFinished_longAfterEndsAt() {
        assertThat(scheduledAt(ENDS.plusDays(365))).isEqualTo(ShowtimeStatus.FINISHED);
    }

    @Test
    void of_returnsCancelled_whateverTheClock() {
        for (OffsetDateTime now : new OffsetDateTime[] {OPENS.minusDays(1), OPENS, CLOSES, ENDS.plusDays(1)}) {
            assertThat(ShowtimeStatus.of("CANCELLED", OPENS, CLOSES, ENDS, now)).isEqualTo(ShowtimeStatus.CANCELLED);
        }
    }

    @Test
    void of_returnsDraft_whateverTheClock() {
        for (OffsetDateTime now : new OffsetDateTime[] {OPENS.minusDays(1), OPENS, CLOSES, ENDS.plusDays(1)}) {
            assertThat(ShowtimeStatus.of("DRAFT", OPENS, CLOSES, ENDS, now)).isEqualTo(ShowtimeStatus.DRAFT);
        }
    }

    @Test
    void of_throwsIllegalArgument_whenStoredStatusUnknown() {
        assertThatThrownBy(() -> ShowtimeStatus.of("ON_SALE", OPENS, CLOSES, ENDS, OPENS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShowtimeStatus.of("WHATEVER", OPENS, CLOSES, ENDS, OPENS))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
