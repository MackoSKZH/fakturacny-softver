package sk.firstglobal.hq.web.pdf;

import sk.firstglobal.hq.core.Invoice;
import sk.firstglobal.hq.core.InvoiceLine;
import sk.firstglobal.hq.core.Party;
import sk.firstglobal.hq.core.PayBySquare;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvoicePdfRendererTest {

    @Test
    void printedQrCodeScansToExactPayBySquarePayload() throws Exception {
        Party oz = new Party("Robotické združenie, o. z.", "Hlavná 1", "Bratislava", "81101", "SK",
                "12345678", "2120000000", null, null, null);
        Party sponsor = new Party("Sponzor s.r.o.", "Priemyselná 5", "Košice", "04001", "SK",
                "87654321", "2020000000", "SK2020000000", null, null);
        Invoice inv = new Invoice("20270001", LocalDate.of(2027, 1, 15), LocalDate.of(2027, 1, 15),
                LocalDate.of(2027, 1, 29), "EUR", oz, sponsor,
                List.of(InvoiceLine.notSubjectToVat("Charitatívna reklama", BigDecimal.ONE, new BigDecimal("1500"))),
                "20270001", "SK3112000000198742637541", "TATRSKBX", null, null, null);

        byte[] pdf = new InvoicePdfRenderer().render(inv, null, null, null, "test");

        BufferedImage page;
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            page = new PDFRenderer(doc).renderImageWithDPI(0, 150);
        }
        int[] pixels = page.getRGB(0, 0, page.getWidth(), page.getHeight(), null, 0, page.getWidth());
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(
                new RGBLuminanceSource(page.getWidth(), page.getHeight(), pixels)));
        String scanned = new QRCodeReader().decode(bitmap, Map.of(DecodeHintType.TRY_HARDER, Boolean.TRUE)).getText();

        assertEquals(PayBySquare.encode(PayBySquare.paymentFor(inv)), scanned);
    }

    @Test
    void moneyUsesSlovakFormatting() {
        assertEquals("1 234 567,89", InvoicePdfRenderer.money(new BigDecimal("1234567.885")));
        assertEquals("-10,00", InvoicePdfRenderer.money(new BigDecimal("-10")));
    }
}
