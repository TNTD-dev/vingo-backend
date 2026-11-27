package vingo.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import vingo.search.SeatPosition;
import vingo.search.internal.SeatmapRepository.LayoutSeat;

class SeatSuggesterTest {

    @Test
    void picksFirstAdjacentGroupInNaturalRowAndSeatOrder() {
        LayoutSeat first = seat("R2", 2);
        LayoutSeat second = seat("R2", 3);
        var free = List.of(seat("R10", 1), second, seat("A", 1), seat("R10", 2), first);

        var ranks = SeatSuggester.rowRanks(free);
        assertThat(ranks).containsEntry("R2", 2).containsEntry("R10", 3);
        var picked = SeatSuggester.pick(free, 2, SeatPosition.FRONT, ranks);

        assertThat(picked).containsExactly(first, second);
        assertThat(SeatSuggester.together(picked)).isTrue();
    }

    @Test
    void prefersOneRowThenItsNarrowestGap() {
        LayoutSeat a7 = seat("A", 7);
        LayoutSeat a9 = seat("A", 9);
        var free = List.of(seat("B", 1), a9, seat("A", 1), a7);
        var picked = SeatSuggester.pick(free, 2, SeatPosition.FRONT, SeatSuggester.rowRanks(free));

        assertThat(picked).containsExactly(a7, a9);
        assertThat(SeatSuggester.together(picked)).isFalse();
    }

    @Test
    void differentRowsAreSplitEvenWithConsecutiveNumbers() {
        LayoutSeat first = seat("A", 1);
        LayoutSeat second = seat("B", 2);
        var free = List.of(second, first);
        var picked = SeatSuggester.pick(free, 2, SeatPosition.FRONT, SeatSuggester.rowRanks(free));

        assertThat(picked).containsExactly(first, second);
        assertThat(SeatSuggester.together(picked)).isFalse();
    }

    @Test
    void retainsPartialGroupForBookingAndHandlesEmptyAndSingleGroups() {
        LayoutSeat last = seat("A", 3);
        var free = List.of(last);
        var picked = SeatSuggester.pick(free, 2, SeatPosition.FRONT, SeatSuggester.rowRanks(free));

        assertThat(picked).containsExactly(last);
        assertThat(SeatSuggester.together(picked)).isTrue();
        assertThat(SeatSuggester.together(List.of())).isFalse();
    }

    @Test
    void backPicksFirstAdjacentPairInLastRowWithRoom() {
        var free = List.of(seat("R10", 9), seat("R2", 2), seat("R2", 1), seat("R10", 3),
                seat("R10", 2), seat("R10", 8), seat("R11", 1));

        assertThat(SeatSuggester.pick(free, 2, SeatPosition.BACK, SeatSuggester.rowRanks(free)))
                .containsExactly(free.get(4), free.get(3));
    }

    @Test
    void middleUsesAllLayoutRowsAndBreaksEqualDistanceTowardFront() {
        var free = List.of(seat("R2", 1), seat("R3", 1), seat("R4", 1));
        var layout = Stream.concat(free.stream(), Stream.of(seat("R1", 1))).toList();

        assertThat(SeatSuggester.pick(free, 1, SeatPosition.MIDDLE, SeatSuggester.rowRanks(layout)))
                .containsExactly(free.getFirst());
        var fiveRows = Stream.concat(layout.stream(), Stream.of(seat("R5", 1))).toList();
        assertThat(SeatSuggester.pick(free, 1, SeatPosition.MIDDLE, SeatSuggester.rowRanks(fiveRows)))
                .containsExactly(free.get(1));
    }

    @Test
    void equalCostSplitGroupsFollowPositionAfterRowCountAndSpread() {
        var free = List.of(seat("R1", 1), seat("R1", 3), seat("R2", 1), seat("R2", 3),
                seat("R3", 1), seat("R3", 3), seat("R4", 1), seat("R4", 5));
        var ranks = SeatSuggester.rowRanks(free);

        assertThat(SeatSuggester.pick(free, 2, SeatPosition.FRONT, ranks)).containsExactlyElementsOf(free.subList(0, 2));
        assertThat(SeatSuggester.pick(free, 2, SeatPosition.MIDDLE, ranks)).containsExactlyElementsOf(free.subList(2, 4));
        assertThat(SeatSuggester.pick(free, 2, SeatPosition.BACK, ranks)).containsExactlyElementsOf(free.subList(4, 6));
    }

    @Test
    void splitGroupsAcrossRowsRemainNaturalWindows() {
        var free = List.of(seat("A", 1), seat("B", 1), seat("C", 1), seat("D", 1), seat("E", 1));
        var ranks = SeatSuggester.rowRanks(free);

        assertThat(SeatSuggester.pick(free, 3, SeatPosition.MIDDLE, ranks)).containsExactlyElementsOf(free.subList(1, 4));
        assertThat(SeatSuggester.pick(free, 2, SeatPosition.BACK, ranks)).containsExactlyElementsOf(free.subList(3, 5));
    }

    private static LayoutSeat seat(String row, int number) {
        return new LayoutSeat(UUID.randomUUID(), row, number);
    }
}
