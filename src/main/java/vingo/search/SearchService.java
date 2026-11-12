package vingo.search;

import java.util.List;
import java.util.UUID;

/**
 * API công khai của module search. Toàn bộ là luồng đọc.
 */
public interface SearchService {

    /**
     * Tìm sự kiện PUBLISHED còn ít nhất một suất SCHEDULED chưa diễn ra.
     *
     * @throws InvalidSearchRequestException khi tham số sai
     */
    Page<EventSummaryResult> searchEvents(EventSearchCriteria criteria);

    /**
     * Chi tiết sự kiện: mô tả, địa điểm, bảng giá và các suất.
     *
     * @throws vingo.shared.error.DomainException NOT_FOUND khi sự kiện không tồn tại hoặc chưa PUBLISHED
     */
    EventDetailResult getEvent(UUID eventId);

    /**
     * Các suất của sự kiện theo {@code starts_at}, kèm trạng thái tính theo giờ hiện tại. Không trả suất DRAFT;
     * suất CANCELLED vẫn trả.
     *
     * @throws vingo.shared.error.DomainException NOT_FOUND khi sự kiện không tồn tại hoặc chưa PUBLISHED
     */
    List<ShowtimeResult> listEventShowtimes(UUID eventId);

    /**
     * Sơ đồ ghế của suất: các khu của layout kèm hạng giá, ghế ngồi kèm trạng thái, khu đứng kèm số chỗ còn.
     * Ghế HELD đã quá hạn được báo AVAILABLE. Có {@code zoneId} thì chỉ trả khu đó.
     *
     * @param zoneId khu cần lấy, {@code null} là lấy tất cả
     * @throws vingo.shared.error.DomainException NOT_FOUND khi suất không tồn tại, là DRAFT, sự kiện chưa PUBLISHED,
     *         hoặc {@code zoneId} không thuộc layout của suất
     */
    SeatmapResult getSeatmap(UUID showtimeId, UUID zoneId);

    /**
     * Số chỗ còn theo khu và cờ {@code soldOut}. Nhẹ hơn {@link #getSeatmap}.
     *
     * @throws vingo.shared.error.DomainException NOT_FOUND khi suất không tồn tại, là DRAFT hoặc sự kiện chưa PUBLISHED
     */
    AvailabilityResult getAvailability(UUID showtimeId);

    /**
     * Gợi ý tối đa {@code n} ghế trống trong khu ghế ngồi, đọc thẳng DB theo Implicit Status (AVAILABLE, hoặc HELD
     * đã quá hạn). Ưu tiên {@code n} ghế liền nhau (số ghế liên tiếp) cùng hàng; không có thì lấy các ghế trống gần
     * nhau nhất theo hàng rồi số ghế. Ít ghế trống hơn {@code n} thì trả hết số ghế trống. Khu đứng, khu không thuộc
     * suất hoặc khu không còn ghế trống trả danh sách rỗng.
     *
     * <p><b>Cách gọi:</b> booking gọi hàm này <b>sau khi</b> transaction giữ ghế đã rollback vì
     * {@code SEAT_TAKEN}, để gợi ý ghế khác cho khách. Không gọi trong transaction đang giữ lock: hàm chỉ đọc nhưng
     * kết quả có thể đã cũ ngay khi trả về, và không được kéo dài thời gian giữ lock của đơn.
     *
     * @throws IllegalArgumentException khi {@code n <= 0}
     */
    List<UUID> suggestSeats(UUID showtimeId, UUID zoneId, int n);
}
