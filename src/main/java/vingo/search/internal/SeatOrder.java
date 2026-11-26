package vingo.search.internal;

import java.math.BigInteger;
import java.util.Comparator;

import vingo.search.internal.SeatmapRepository.LayoutSeat;

final class SeatOrder {

    static final Comparator<LayoutSeat> BY_ROW_AND_NUMBER = Comparator
            .comparing(LayoutSeat::row, SeatOrder::compareRows)
            .thenComparingInt(LayoutSeat::number);

    private SeatOrder() {
    }

    static int compareRows(String left, String right) {
        if (left.equals(right)) {
            return 0;
        }
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            char a = left.charAt(i);
            char b = right.charAt(j);
            int difference;
            if (Character.isDigit(a) && Character.isDigit(b)) {
                int endA = endOfDigits(left, i);
                int endB = endOfDigits(right, j);
                difference = new BigInteger(left.substring(i, endA)).compareTo(new BigInteger(right.substring(j, endB)));
                i = endA;
                j = endB;
            } else {
                difference = Character.compare(a, b);
                i++;
                j++;
            }
            if (difference != 0) {
                return difference;
            }
        }
        int remaining = Integer.compare(left.length() - i, right.length() - j);
        // Hai nhãn như R2 và R02 vẫn là hai hàng riêng.
        return remaining != 0 ? remaining : left.compareTo(right);
    }

    private static int endOfDigits(String label, int start) {
        int end = start + 1;
        while (end < label.length() && Character.isDigit(label.charAt(end))) {
            end++;
        }
        return end;
    }
}
