package vingo.search.internal;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import vingo.catalog.CatalogChanged;
import vingo.catalog.VenueChanged;

/**
 * Xoá cache của search khi catalog thay đổi. Chạy <b>sau commit</b> của transaction đã phát event, trong cùng
 * thread, nên khi request ghi của admin trả về thì cache đã sạch; transaction rollback thì không xoá.
 *
 * <p>Không xoá từng khoá mà tăng thế hệ ({@link CacheGenerations}): khoá cũ không ai đọc nữa và hết hạn theo TTL, kể
 * cả khoá của một lần nạp bắt đầu trước commit mà ghi muộn. Lỗi Redis chỉ ghi WARN: TTL 10 phút là lưới an toàn.
 */
@Component
@Profile("api")
class CatalogChangedListener {

    private final CacheGenerations generations;

    CatalogChangedListener(CacheGenerations generations) {
        this.generations = generations;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(CatalogChanged event) {
        generations.bumpEvent(event.eventId());
        event.showtimeIds().forEach(generations::bumpShowtime);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void on(VenueChanged event) {
        generations.bumpVenues();
    }
}
