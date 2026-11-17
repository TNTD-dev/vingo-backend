package vingo.ticketing.internal;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

import javax.imageio.ImageIO;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

/** Tạo ảnh QR vé bằng ZXing core và bộ ghi PNG của JDK. */
public final class QrPng {

    private QrPng() {}

    /** Mã hoá token thành PNG đen trắng 320x320 với lề 2 và mức sửa lỗi M. */
    public static byte[] encode(String token) {
        try {
            var matrix = new QRCodeWriter().encode(token, BarcodeFormat.QR_CODE, 320, 320,
                    Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            var image = new BufferedImage(320, 320, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < 320; y++) {
                for (int x = 0; x < 320; x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xffffff);
                }
            }
            var out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (WriterException | IOException ex) {
            throw new IllegalStateException("Không thể tạo ảnh QR", ex);
        }
    }
}
