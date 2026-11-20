package vingo.search;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chi tiết một sự kiện PUBLISHED.
 */
public record EventDetailResult(
        UUID id,
        String title,
        String description,
        String category,
        String status,
        String posterUrl,
        String homeTeam,
        String awayTeam,
        String league,
        String round,
        int maxTicketsPerOrder,
        Optional<VenueResult> venue,
        List<PriceTierResult> priceTiers,
        List<ShowtimeResult> showtimes) {
}
