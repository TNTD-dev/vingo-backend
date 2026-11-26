package vingo.search;

/** Vị trí hàng ưu tiên trong một khu ngồi. */
public enum SeatPosition {
    FRONT, MIDDLE, BACK;

    public static SeatPosition parse(String value) {
        for (SeatPosition position : values()) {
            if (position.name().equals(value)) {
                return position;
            }
        }
        throw new InvalidSearchRequestException("position", "position phải là FRONT, MIDDLE hoặc BACK");
    }
}
