package vingo.search.internal;

import java.util.List;

import vingo.search.PriceTierResult;
import vingo.search.internal.EventDetailRepository.EventRow;
import vingo.search.internal.EventDetailRepository.ShowtimeRow;

/**
 * Dữ liệu thô của chi tiết sự kiện, được cache 10 phút: sự kiện, bảng giá, các suất kèm mốc giờ và sân. Không có
 * trạng thái suất tính theo giờ hay {@code sold_out}; hai thứ đó tính lại mỗi lần đọc.
 */
record EventCore(EventRow event, List<PriceTierResult> priceTiers, List<ShowtimeRow> showtimes) {
}
