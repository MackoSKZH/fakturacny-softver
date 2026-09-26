package sk.firstglobal.hq.web.donation;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Map;

/** QR kod ako inline SVG (jedna cesta, bez skriptov a stylov - prejde cez CSP). */
public final class QrSvg {
    private QrSvg() {
    }

    public static String svg(String content, String label) {
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
        } catch (WriterException e) {
            throw new IllegalStateException("QR kód sa nepodarilo vytvoriť", e);
        }
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < m.getHeight(); y++) {
            for (int x = 0; x < m.getWidth(); x++) {
                if (m.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + m.getWidth() + " " + m.getHeight()
                + "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"" + label.replace("\"", "") + "\">"
                + "<rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path fill=\"#000\" d=\"" + path + "\"/></svg>";
    }
}
