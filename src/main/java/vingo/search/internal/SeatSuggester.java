package vingo.search.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import vingo.search.internal.SeatmapRepository.FreeSeat;

/**
 * Chọn nhóm ghế gợi ý từ danh sách ghế trống đã sắp theo {@code row_label}, {@code number}. Hàm thuần, không đụng DB.
 */
final class SeatSuggester {

    private SeatSuggester() {
    }

    /**
     * Ưu tiên {@code n} ghế có số liên tiếp cùng hàng (nhóm đầu tiên theo thứ tự hàng, số). Không có thì chọn cửa sổ
     * {@code n} ghế liên tiếp trong danh sách có ít hàng nhất, rồi khoảng số ghế hẹp nhất; hoà thì lấy cửa sổ đầu.
     * Ít hơn {@code n} ghế trống thì trả hết.
     */
    static List<UUID> pick(List<FreeSeat> free, int n) {
        if (free.size() <= n) {
            return free.stream().map(FreeSeat::id).toList();
        }
        // Cùng hàng và số liên tiếp.
        int runStart = 0;
        for (int i = 1; i <= free.size(); i++) {
            boolean continues = i < free.size() && free.get(i).row().equals(free.get(i - 1).row())
                    && free.get(i).number() == free.get(i - 1).number() + 1;
            if (!continues) {
                if (i - runStart >= n) {
                    return ids(free.subList(runStart, runStart + n));
                }
                runStart = i;
            }
        }
        // Không có nhóm liền nhau: cửa sổ n ghế gần nhau nhất.
        int bestStart = 0;
        long bestCost = Long.MAX_VALUE;
        for (int start = 0; start + n <= free.size(); start++) {
            long cost = cost(free.subList(start, start + n));
            if (cost < bestCost) {
                bestCost = cost;
                bestStart = start;
            }
        }
        return ids(free.subList(bestStart, bestStart + n));
    }

    /** Chi phí cửa sổ: số hàng trải qua nhân lớn, cộng tổng khoảng số ghế trong từng hàng. */
    private static long cost(List<FreeSeat> window) {
        long rows = 0;
        long spread = 0;
        int rowMin = 0;
        int rowMax = 0;
        String row = null;
        for (FreeSeat seat : window) {
            if (!seat.row().equals(row)) {
                spread += rowMax - rowMin;
                rows++;
                row = seat.row();
                rowMin = seat.number();
            }
            rowMax = seat.number();
        }
        spread += rowMax - rowMin;
        return rows * 1_000_000L + spread;
    }

    private static List<UUID> ids(List<FreeSeat> seats) {
        List<UUID> ids = new ArrayList<>(seats.size());
        seats.forEach(s -> ids.add(s.id()));
        return ids;
    }
}
