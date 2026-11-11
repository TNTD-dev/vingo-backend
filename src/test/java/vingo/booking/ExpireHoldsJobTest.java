package vingo.booking;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import vingo.booking.internal.ExpireHoldsJob;

class ExpireHoldsJobTest {

    private final BookingService booking = mock(BookingService.class);
    private final ExpireHoldsJob job = new ExpireHoldsJob(booking);

    @Test
    void expireHolds_drainsBatchesUntilPartialBatch() {
        when(booking.expireOverdueHolds(100)).thenReturn(100, 100, 7);

        job.expireHolds();

        verify(booking, times(3)).expireOverdueHolds(100);
        verifyNoMoreInteractions(booking);
    }

    @Test
    void expireHolds_stopsAfterEmptyBatch() {
        when(booking.expireOverdueHolds(100)).thenReturn(0);

        job.expireHolds();

        verify(booking).expireOverdueHolds(100);
        verifyNoMoreInteractions(booking);
    }

    @Test
    @Timeout(5)
    void expireHolds_stopsAtBatchLimit_whenEveryBatchIsFull() {
        when(booking.expireOverdueHolds(100)).thenReturn(100);

        job.expireHolds();

        verify(booking, times(50)).expireOverdueHolds(100);
        verifyNoMoreInteractions(booking);
    }
}
