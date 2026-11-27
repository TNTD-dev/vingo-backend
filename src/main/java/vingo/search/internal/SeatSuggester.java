package vingo.search.internal;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import vingo.search.SeatPosition;
import vingo.search.internal.SeatmapRepository.LayoutSeat;

/**
 * Chọn nhóm ghế gợi ý theo thứ tự tự nhiên của hàng rồi số ghế. Hàm thuần, không đụng DB.
 */
final class SeatSuggester {

    private SeatSuggester() {
    }

    static Map<String, Integer> rowRanks(List<LayoutSeat> seats) {
        var rows = seats.stream().map(LayoutSeat::row).distinct().sorted(SeatOrder::compareRows).toList();
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ranks.put(rows.get(i), i + 1);
        }
        return ranks;
    }

    /**
     * Ưu tiên nhóm liền đầu tiên trong hàng hợp vị trí nhất. Nếu phải tách, giữ các cửa sổ theo thứ tự
     * tự nhiên để các hàng ở hai phía của khu vẫn giữ đúng thứ tự vật lý.
     * Ít hơn {@code n} ghế trống thì trả hết cho booking khi giữ ghế lỗi 409. Endpoint gợi ý kiểm tra đủ ghế trước khi gọi.
     */
    static List<LayoutSeat> pick(List<LayoutSeat> available, int n, SeatPosition position, Map<String, Integer> ranks) {
        Comparator<String> natural = Comparator.comparingInt(ranks::get);
        Comparator<String> preferred = switch (position) {
            case FRONT -> natural;
            case BACK -> natural.reversed();
            case MIDDLE -> Comparator.comparingLong((String row) -> Math.abs(2L * ranks.get(row) - ranks.size() - 1))
                    .thenComparing(natural);
        };
        var free = available.stream().sorted(SeatOrder.BY_ROW_AND_NUMBER).toList();
        if (free.size() <= n) {
            return free;
        }
        List<LayoutSeat> adjacent = List.of();
        for (int start = 0; start + n <= free.size(); start++) {
            var window = free.subList(start, start + n);
            if (together(window)) {
                if (position == SeatPosition.FRONT) {
                    return window;
                }
                if (adjacent.isEmpty() || preferred.compare(window.getFirst().row(), adjacent.getFirst().row()) < 0) {
                    adjacent = window;
                }
            }
        }
        if (!adjacent.isEmpty()) {
            return adjacent;
        }
        // Không có nhóm liền nhau: cửa sổ n ghế gần nhau nhất.
        var best = free.subList(0, n);
        long bestCost = cost(best);
        for (int start = 1; start + n <= free.size(); start++) {
            var window = free.subList(start, start + n);
            long windowCost = cost(window);
            if (windowCost < bestCost || (windowCost == bestCost && position != SeatPosition.FRONT
                    && compareWindowRows(window, best, preferred) < 0)) {
                bestCost = windowCost;
                best = window;
            }
        }
        return best;
    }

    /** Hai cửa sổ cùng chi phí: xét lần lượt các hàng từ ưu tiên nhất, hoà hết thì giữ cửa sổ đầu. */
    private static int compareWindowRows(List<LayoutSeat> left, List<LayoutSeat> right, Comparator<String> preferred) {
        var leftRows = left.stream().map(LayoutSeat::row).distinct().sorted(preferred).toArray(String[]::new);
        var rightRows = right.stream().map(LayoutSeat::row).distinct().sorted(preferred).toArray(String[]::new);
        return Arrays.compare(leftRows, rightRows, preferred);
    }

    /** Chi phí cửa sổ: số hàng trải qua nhân lớn, cộng tổng khoảng số ghế trong từng hàng. */
    private static long cost(List<LayoutSeat> window) {
        long rows = 0;
        long spread = 0;
        int rowMin = 0;
        int rowMax = 0;
        String row = null;
        for (LayoutSeat seat : window) {
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

    /** Ghế đã sắp theo hàng và số, số ghế là duy nhất trong mỗi hàng. */
    static boolean together(List<LayoutSeat> seats) {
        if (seats.isEmpty()) {
            return false;
        }
        LayoutSeat first = seats.getFirst();
        LayoutSeat last = seats.getLast();
        return first.row().equals(last.row()) && last.number() - first.number() == seats.size() - 1;
    }
}
