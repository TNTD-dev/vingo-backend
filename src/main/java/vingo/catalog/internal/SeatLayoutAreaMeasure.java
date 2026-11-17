package vingo.catalog.internal;

import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.PathIterator;

/** Tích phân Green giải tích trên line/quadratic/cubic, giữ dấu của lỗ; không flatten. */
final class SeatLayoutAreaMeasure {
    private SeatLayoutAreaMeasure() {}

    static double area(Area area) {
        if (area.isEmpty()) return 0;
        var bounds = area.getBounds2D();
        // Dời gốc để giảm triệt tiêu số khi tọa độ toàn sân lớn.
        PathIterator path = area.getPathIterator(AffineTransform.getTranslateInstance(-bounds.getX(), -bounds.getY()));
        double[] c = new double[6];
        double x = 0, y = 0, startX = 0, startY = 0, sum = 0, compensation = 0;
        while (!path.isDone()) {
            int kind = path.currentSegment(c);
            double contribution = 0;
            switch (kind) {
                case PathIterator.SEG_MOVETO -> { x = startX = c[0]; y = startY = c[1]; }
                case PathIterator.SEG_LINETO -> {
                    contribution = integral(new double[]{x, c[0] - x}, new double[]{y, c[1] - y});
                    x = c[0]; y = c[1];
                }
                case PathIterator.SEG_QUADTO -> {
                    contribution = integral(new double[]{x, 2 * (c[0] - x), x - 2 * c[0] + c[2]},
                            new double[]{y, 2 * (c[1] - y), y - 2 * c[1] + c[3]});
                    x = c[2]; y = c[3];
                }
                case PathIterator.SEG_CUBICTO -> {
                    contribution = integral(new double[]{x, 3 * (c[0] - x), 3 * (x - 2 * c[0] + c[2]), -x + 3 * c[0] - 3 * c[2] + c[4]},
                            new double[]{y, 3 * (c[1] - y), 3 * (y - 2 * c[1] + c[3]), -y + 3 * c[1] - 3 * c[3] + c[5]});
                    x = c[4]; y = c[5];
                }
                case PathIterator.SEG_CLOSE -> {
                    contribution = integral(new double[]{x, startX - x}, new double[]{y, startY - y});
                    x = startX; y = startY;
                }
                default -> throw new IllegalStateException("Không hỗ trợ đoạn hình học");
            }
            double corrected = contribution - compensation;
            double next = sum + corrected;
            compensation = (next - sum) - corrected;
            sum = next;
            path.next();
        }
        double result = Math.abs(sum) / 2;
        if (!Double.isFinite(result)) throw new IllegalArgumentException("Diện tích không hữu hạn");
        return result;
    }

    private static double integral(double[] x, double[] y) {
        double result = 0;
        for (int i = 0; i < x.length; i++) {
            for (int j = 1; j < x.length; j++) {
                result += (x[i] * j * y[j] - y[i] * j * x[j]) / (i + j);
            }
        }
        return result;
    }
}
