package com.fakturacnysoftver.web.pdf;

import com.fakturacnysoftver.core.CreditNote;
import com.fakturacnysoftver.core.Invoice;
import com.fakturacnysoftver.core.InvoiceLine;
import com.fakturacnysoftver.core.InvoiceTotals;
import com.fakturacnysoftver.core.Party;
import com.fakturacnysoftver.core.PayBySquare;
import com.fakturacnysoftver.core.VatCategory;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * PDF faktura s nalezitostami podla § 74 zakona o DPH (pre platitelov) a § 10 zakona o uctovnictve.
 * Polozky sa zalamuju a pokracuju na dalsej strane s hlavickou tabulky; na konci je QR PAY by square.
 */
@Component
public class InvoicePdfRenderer {
    private static final PDRectangle PAGE = PDRectangle.A4;
    private static final float MARGIN = 40;
    private static final float CONTENT_WIDTH = PAGE.getWidth() - 2 * MARGIN;
    private static final float FOOTER_SPACE = 36;
    private static final float QR_SIZE = 96;
    private static final float GRAY = 0.45f;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d. M. yyyy");
    private static final Map<String, String> UNITS = Map.of("C62", "ks", "H87", "ks", "HUR", "hod", "DAY", "deň",
            "MON", "mes", "KGM", "kg", "MTR", "m", "LS", "paušál");

    /** @param cn dobropis, ku ktoremu patri {@code inv}; null pre obycajnu fakturu */
    public byte[] render(Invoice inv, CreditNote cn, String registrationNote, String projectName, String issuedBy) {
        try (PDDocument doc = new PDDocument()) {
            Canvas c = new Canvas(doc, loadFont(doc, "/fonts/NotoSans-Regular.ttf"),
                    loadFont(doc, "/fonts/NotoSans-Bold.ttf"));
            boolean vatPayer = inv.seller().isVatRegistered();
            InvoiceTotals totals = inv.totals();

            this.header(c, inv, cn);
            this.parties(c, inv, registrationNote);
            this.details(c, inv, projectName, cn != null);
            this.lines(c, inv, vatPayer);
            this.summary(c, inv, totals, vatPayer, cn != null);
            if (cn == null) {
                this.payment(c, inv, totals);
            }
            if (inv.note() != null && !inv.note().isBlank()) {
                c.ensureSpace(30, null);
                c.y -= 8;
                c.paragraph(inv.note(), c.regular, 9, CONTENT_WIDTH);
            }
            c.close();
            this.footers(doc, c, issuedBy);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Chyba pri tvorbe PDF faktúry", e);
        }
    }

    private void header(Canvas c, Invoice inv, CreditNote cn) throws IOException {
        c.text(MARGIN, c.y - 20, c.bold, 22, cn == null ? "Faktúra" : "Dobropis");
        String no = "č. " + inv.number();
        c.text(PAGE.getWidth() - MARGIN - c.width(c.bold, 14, no), c.y - 18, c.bold, 14, no);
        c.y -= 30;
        c.hline(c.y, 1f);
        c.y -= 16;
        if (cn != null) {
            c.text(MARGIN, c.y, c.bold, 10, "Opravný doklad k faktúre č. " + cn.originalNumber()
                    + (cn.originalIssueDate() == null ? "" : " zo dňa " + date(cn.originalIssueDate())));
            c.y -= 4;
            c.paragraph("Dôvod opravy: " + cn.reason(), c.regular, 9.5f, CONTENT_WIDTH);
            c.y -= 12;
        }
    }

    private void parties(Canvas c, Invoice inv, String registrationNote) throws IOException {
        float colWidth = CONTENT_WIDTH / 2 - 10;
        float top = c.y;
        float left = this.party(c, "DODÁVATEĽ", inv.seller(), MARGIN, top, colWidth, true);
        if (registrationNote != null && !registrationNote.isBlank()) {
            c.y = left - 2;
            c.paragraphAt(MARGIN, registrationNote, c.regular, 7.5f, colWidth, GRAY);
            left = c.y;
        }
        float right = this.party(c, "ODBERATEĽ", inv.buyer(), MARGIN + CONTENT_WIDTH / 2 + 10, top, colWidth, false);
        c.y = Math.min(left, right) - 12;
    }

    private float party(Canvas c, String label, Party p, float x, float top, float width, boolean seller)
            throws IOException {
        float y = top;
        c.textGray(x, y, c.bold, 8, label);
        y -= 15;
        for (String line : c.wrap(p.name(), c.bold, 11, width)) {
            c.text(x, y, c.bold, 11, line);
            y -= 14;
        }
        List<String> rows = new ArrayList<>();
        addIf(rows, "", p.street());
        addIf(rows, "", join(p.postalCode(), p.city()));
        if (!"SK".equals(p.country())) {
            rows.add(p.country());
        }
        addIf(rows, "IČO: ", p.ico());
        addIf(rows, "DIČ: ", p.dic());
        if (p.isVatRegistered()) {
            rows.add("IČ DPH: " + p.icDph());
        } else if (seller) {
            rows.add("Nie je platiteľ DPH");
        }
        if (seller) {
            addIf(rows, "", p.email());
            addIf(rows, "", p.phone());
        }
        for (String row : rows) {
            c.text(x, y, c.regular, 9.5f, row);
            y -= 12.5f;
        }
        return y;
    }

    private void details(Canvas c, Invoice inv, String projectName, boolean credit) throws IOException {
        c.hline(c.y + 4, 0.4f);
        c.y -= 10;
        float colW = CONTENT_WIDTH / 3;
        String[][] cells = {
                {"Dátum vyhotovenia", date(inv.issueDate())},
                {"Dátum dodania", date(inv.deliveryDate())},
                {credit ? "Vrátenie do" : "Dátum splatnosti", date(inv.dueDate())},
                {"Variabilný symbol", orDash(inv.variableSymbol())},
                {"Forma úhrady", credit ? "Prevodom na účet odberateľa"
                        : inv.payeeIban() == null ? "-" : "Prevodom na účet"},
                {"Projekt", inv.projectCode() == null ? "-"
                        : inv.projectCode() + (projectName == null ? "" : " " + projectName)},
        };
        for (int i = 0; i < cells.length; i++) {
            float x = MARGIN + (i % 3) * colW;
            float y = c.y - (i / 3) * 26;
            c.textGray(x, y, c.regular, 7.5f, cells[i][0]);
            c.text(x, y - 11, c.bold, 9.5f, c.fit(cells[i][1], c.bold, 9.5f, colW - 8));
        }
        c.y -= 52;
        if (inv.payeeIban() != null && !credit) {
            c.text(MARGIN, c.y, c.regular, 9.5f, "IBAN: " + formatIban(inv.payeeIban())
                    + (inv.payeeBic() == null ? "" : "     BIC: " + inv.payeeBic()));
            c.y -= 13;
        }
        if (inv.buyerReference() != null) {
            c.text(MARGIN, c.y, c.regular, 9.5f, "Referencia odberateľa: " + inv.buyerReference());
            c.y -= 13;
        }
        c.y -= 8;
    }

    private void lines(Canvas c, Invoice inv, boolean vatPayer) throws IOException {
        String[] head = vatPayer
                ? new String[]{"Popis", "Množstvo", "MJ", "Cena/MJ", "DPH", "Spolu bez DPH"}
                : new String[]{"Popis", "Množstvo", "MJ", "Cena/MJ", "Spolu"};
        float[] widths = vatPayer
                ? new float[]{0, 52, 34, 70, 40, 82}
                : new float[]{0, 55, 35, 80, 85};
        float fixed = 0;
        for (float w : widths) {
            fixed += w;
        }
        widths[0] = CONTENT_WIDTH - fixed;

        Runnable drawHead = () -> this.tableHead(c, head, widths);
        drawHead.run();
        for (InvoiceLine line : inv.lines()) {
            List<String> desc = c.wrap(line.description(), c.regular, 9, widths[0] - 8);
            float rowH = desc.size() * 11 + 7;
            c.ensureSpace(rowH, drawHead);

            String[] cells = vatPayer
                    ? new String[]{null, qty(line.quantity()), unit(line.unitCode()), money(line.unitPrice()),
                            vatLabel(line), money(line.netAmount())}
                    : new String[]{null, qty(line.quantity()), unit(line.unitCode()), money(line.unitPrice()),
                            money(line.netAmount())};
            float textY = c.y - 11;
            for (int i = 0; i < desc.size(); i++) {
                c.text(MARGIN + 4, textY - i * 11, c.regular, 9, desc.get(i));
            }
            float x = MARGIN + widths[0];
            for (int i = 1; i < cells.length; i++) {
                c.textRight(x + widths[i] - 4, textY, c.regular, 9, cells[i]);
                x += widths[i];
            }
            c.y -= rowH;
            c.hline(c.y, 0.3f);
        }
        c.y -= 10;
    }

    private void tableHead(Canvas c, String[] head, float[] widths) {
        try {
            float h = 18;
            c.cs.setNonStrokingColor(0.93f);
            c.cs.addRect(MARGIN, c.y - h, CONTENT_WIDTH, h);
            c.cs.fill();
            c.cs.setNonStrokingColor(0f);
            float x = MARGIN;
            for (int i = 0; i < head.length; i++) {
                if (i == 0) {
                    c.text(x + 4, c.y - 12.5f, c.bold, 8.5f, head[i]);
                } else {
                    c.textRight(x + widths[i] - 4, c.y - 12.5f, c.bold, 8.5f, head[i]);
                }
                x += widths[i];
            }
            c.y -= h;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void summary(Canvas c, Invoice inv, InvoiceTotals totals, boolean vatPayer, boolean credit)
            throws IOException {
        float right = PAGE.getWidth() - MARGIN;
        if (vatPayer) {
            c.ensureSpace(30 + totals.vatBreakdown().size() * 13, null);
            float[] cols = {right - 250, right - 170, right - 85, right};
            String[] head = {"Sadzba", "Základ dane", "DPH", "Spolu"};
            for (int i = 0; i < head.length; i++) {
                c.textRight(cols[i], c.y, c.bold, 8.5f, head[i]);
            }
            c.y -= 13;
            for (InvoiceTotals.VatBreakdown b : totals.vatBreakdown()) {
                String rate = b.category() == VatCategory.STANDARD ? pct(b.ratePercent()) : b.exemptionReason();
                c.textRight(cols[0], c.y, c.regular, 9, rate);
                c.textRight(cols[1], c.y, c.regular, 9, money(b.taxableAmount()));
                c.textRight(cols[2], c.y, c.regular, 9, money(b.taxAmount()));
                c.textRight(cols[3], c.y, c.regular, 9, money(b.taxableAmount().add(b.taxAmount())));
                c.y -= 13;
            }
            c.y -= 4;
        }
        c.ensureSpace(30, null);
        c.hline(c.y + 6, 0.8f, right - 250, right);
        String total = money(totals.payableAmount()) + " " + currency(inv.currency());
        c.text(right - 250, c.y - 10, c.bold, 12, credit ? "Na vrátenie odberateľovi" : "Spolu na úhradu");
        c.textRight(right, c.y - 10, c.bold, 13, total);
        c.y -= 22;
        if (!vatPayer) {
            c.textGray(right - 250, c.y, c.regular, 8, "Dodávateľ nie je platiteľom DPH.");
            c.y -= 12;
        }
        c.y -= 6;
    }

    private void payment(Canvas c, Invoice inv, InvoiceTotals totals) throws IOException {
        boolean qr = inv.payeeIban() != null && totals.payableAmount().signum() > 0 && "EUR".equals(inv.currency());
        if (!qr) {
            return;
        }
        c.ensureSpace(QR_SIZE + 20, null);
        float top = c.y;
        this.drawQr(c, PayBySquare.encode(PayBySquare.paymentFor(inv)), MARGIN, top, QR_SIZE);
        c.textGray(MARGIN + 12, top - QR_SIZE - 10, c.bold, 8, "PAY by square");

        float x = MARGIN + QR_SIZE + 16;
        c.text(x, top - 12, c.bold, 10, "Zaplaťte QR kódom v bankovej aplikácii");
        c.text(x, top - 28, c.regular, 9, "Suma: " + money(totals.payableAmount()) + " " + currency(inv.currency()));
        c.text(x, top - 41, c.regular, 9, "IBAN: " + formatIban(inv.payeeIban()));
        c.text(x, top - 54, c.regular, 9, "Variabilný symbol: " + orDash(inv.variableSymbol()));
        c.text(x, top - 67, c.regular, 9, "Splatnosť: " + date(inv.dueDate()));
        c.y = top - QR_SIZE - 20;
    }

    private void drawQr(Canvas c, String content, float x, float top, float size) throws IOException {
        BitMatrix m;
        try {
            m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 0));
        } catch (WriterException e) {
            throw new IllegalStateException("QR kód sa nepodarilo vytvoriť", e);
        }
        float module = size / m.getWidth();
        c.cs.setNonStrokingColor(0f);
        for (int row = 0; row < m.getHeight(); row++) {
            for (int col = 0; col < m.getWidth(); col++) {
                if (m.get(col, row)) {
                    c.cs.addRect(x + col * module, top - (row + 1) * module, module, module);
                }
            }
        }
        c.cs.fill();
    }

    private void footers(PDDocument doc, Canvas c, String issuedBy) throws IOException {
        int total = doc.getNumberOfPages();
        for (int i = 0; i < total; i++) {
            PDPage page = doc.getPage(i);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                    PDPageContentStream.AppendMode.APPEND, true, true)) {
                c.cs = cs;
                c.hline(MARGIN + 14, 0.3f);
                if (issuedBy != null) {
                    c.textGray(MARGIN, MARGIN, c.regular, 7.5f, "Vystavil: " + issuedBy);
                }
                String pageNo = "Strana " + (i + 1) + " / " + total;
                c.textGray(PAGE.getWidth() - MARGIN - c.width(c.regular, 7.5f, pageNo), MARGIN, c.regular, 7.5f, pageNo);
            }
        }
    }

    private static PDType0Font loadFont(PDDocument doc, String path) throws IOException {
        try (InputStream in = InvoicePdfRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Chýba písmo " + path);
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    static String money(BigDecimal value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols();
        symbols.setDecimalSeparator(',');
        symbols.setGroupingSeparator(' ');
        symbols.setMinusSign('-');
        DecimalFormat format = new DecimalFormat("#,##0.00", symbols);
        format.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return format.format(value);
    }

    private static String qty(BigDecimal q) {
        BigDecimal s = q.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString().replace('.', ',');
    }

    private static String pct(BigDecimal rate) {
        return rate == null ? "-" : qty(rate) + " %";
    }

    private static String vatLabel(InvoiceLine line) {
        return switch (line.vatCategory()) {
            case STANDARD -> pct(line.vatRate());
            case EXEMPT -> "oslob.";
            case REVERSE_CHARGE -> "PDP";
            case ZERO -> "0 %";
            case NOT_SUBJECT -> "-";
        };
    }

    private static String unit(String code) {
        return UNITS.getOrDefault(code, code);
    }

    private static String currency(String code) {
        return "EUR".equals(code) ? "€" : code;
    }

    private static String date(LocalDate d) {
        return d == null ? "-" : d.format(DATE);
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private static String formatIban(String iban) {
        return iban.replaceAll("(.{4})(?!$)", "$1 ");
    }

    private static String join(String a, String b) {
        String s = ((a == null ? "" : a.trim()) + " " + (b == null ? "" : b.trim())).trim();
        return s.isEmpty() ? null : s;
    }

    private static void addIf(List<String> rows, String prefix, String value) {
        if (value != null && !value.isBlank()) {
            rows.add(prefix + value);
        }
    }

    /** Stav kreslenia: aktualna strana, pozicia a pisma. */
    private static final class Canvas {
        final PDDocument doc;
        final PDType0Font regular;
        final PDType0Font bold;
        PDPageContentStream cs;
        float y;

        Canvas(PDDocument doc, PDType0Font regular, PDType0Font bold) throws IOException {
            this.doc = doc;
            this.regular = regular;
            this.bold = bold;
            this.newPage();
        }

        void newPage() throws IOException {
            if (this.cs != null) {
                this.cs.close();
            }
            PDPage page = new PDPage(PAGE);
            this.doc.addPage(page);
            this.cs = new PDPageContentStream(this.doc, page);
            this.y = PAGE.getHeight() - MARGIN;
        }

        /** Ak sa blok nezmesti, pokracuje na novej strane (volitelne s hlavickou tabulky). */
        void ensureSpace(float height, Runnable onNewPage) throws IOException {
            if (this.y - height < MARGIN + FOOTER_SPACE) {
                this.newPage();
                if (onNewPage != null) {
                    onNewPage.run();
                }
            }
        }

        void close() throws IOException {
            this.cs.close();
        }

        void text(float x, float y, PDType0Font font, float size, String s) throws IOException {
            this.cs.beginText();
            this.cs.setFont(font, size);
            this.cs.newLineAtOffset(x, y);
            this.cs.showText(this.safe(font, s).replace('\n', ' '));
            this.cs.endText();
        }

        void textGray(float x, float y, PDType0Font font, float size, String s) throws IOException {
            this.cs.setNonStrokingColor(GRAY);
            this.text(x, y, font, size, s);
            this.cs.setNonStrokingColor(0f);
        }

        void textRight(float right, float y, PDType0Font font, float size, String s) throws IOException {
            this.text(right - this.width(font, size, s), y, font, size, s);
        }

        void paragraph(String text, PDType0Font font, float size, float width) throws IOException {
            this.paragraphAt(MARGIN, text, font, size, width, 0f);
        }

        void paragraphAt(float x, String text, PDType0Font font, float size, float width, float gray)
                throws IOException {
            this.cs.setNonStrokingColor(gray);
            for (String line : this.wrap(text, font, size, width)) {
                this.ensureSpace(size + 3, null);
                this.cs.setNonStrokingColor(gray);
                this.text(x, this.y - size, font, size, line);
                this.y -= size + 3;
            }
            this.cs.setNonStrokingColor(0f);
        }

        void hline(float atY, float width) throws IOException {
            this.hline(atY, width, MARGIN, PAGE.getWidth() - MARGIN);
        }

        void hline(float atY, float width, float from, float to) throws IOException {
            this.cs.setLineWidth(width);
            this.cs.setStrokingColor(0.6f);
            this.cs.moveTo(from, atY);
            this.cs.lineTo(to, atY);
            this.cs.stroke();
            this.cs.setStrokingColor(0f);
        }

        float width(PDType0Font font, float size, String s) {
            try {
                return font.getStringWidth(this.safe(font, s)) / 1000 * size;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String fit(String s, PDType0Font font, float size, float max) {
            if (this.width(font, size, s) <= max) {
                return s;
            }
            String cut = s;
            while (cut.length() > 1 && this.width(font, size, cut + "…") > max) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + "…";
        }

        List<String> wrap(String text, PDType0Font font, float size, float max) {
            List<String> out = new ArrayList<>();
            for (String paragraph : this.safe(font, text == null ? "" : text).split("\n")) {
                StringBuilder line = new StringBuilder();
                for (String word : glue(paragraph.split(" "))) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (this.width(font, size, candidate) <= max) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    // Slovo dlhsie nez stlpec (napr. URL) rozdelime natvrdo.
                    String rest = word;
                    while (this.width(font, size, rest) > max && rest.length() > 1) {
                        int n = rest.length();
                        while (n > 1 && this.width(font, size, rest.substring(0, n)) > max) {
                            n--;
                        }
                        out.add(rest.substring(0, n));
                        rest = rest.substring(n);
                    }
                    line.append(rest);
                }
                out.add(line.toString());
            }
            return out;
        }

        /**
         * Slovenska typografia: skratky ("o. z.", "s. r. o.") sa neodtrhavaju od predchadzajuceho slova
         * a jednopismenove predlozky (v, s, z, k, a, o, u, i) nezostavaju na konci riadku.
         */
        static List<String> glue(String[] words) {
            List<String> out = new ArrayList<>();
            String pending = null;
            for (String w : words) {
                if (w.isEmpty()) {
                    continue;
                }
                if (pending != null) {
                    w = pending + " " + w;
                    pending = null;
                }
                boolean abbreviation = w.length() <= 3 && w.endsWith(".") && !out.isEmpty();
                if (abbreviation) {
                    out.set(out.size() - 1, out.get(out.size() - 1) + " " + w);
                } else if (w.length() == 1 && Character.isLetter(w.charAt(0))) {
                    pending = w;
                } else {
                    out.add(w);
                }
            }
            if (pending != null) {
                out.add(pending);
            }
            return out;
        }

        /** Znaky, ktore pismo nevie vykreslit (emoji, tabulator...), nahradi - PDF sa nesmie zrutit. */
        String safe(PDType0Font font, String s) {
            if (s == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder(s.length());
            s.replace('\t', ' ').replace('\r', ' ').codePoints().forEach(cp -> {
                String ch = new String(Character.toChars(cp));
                if (cp == '\n') {
                    sb.append(ch);
                    return;
                }
                try {
                    font.encode(ch);
                    sb.append(ch);
                } catch (IOException | IllegalArgumentException e) {
                    sb.append('?');
                }
            });
            return sb.toString();
        }
    }
}
