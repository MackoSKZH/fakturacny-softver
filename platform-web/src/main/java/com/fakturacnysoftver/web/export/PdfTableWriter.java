package com.fakturacnysoftver.web.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Tabulka do PDF: A4 na sirku, zalamovanie textu v bunkach, hlavicka na kazdej strane, "strana X / Y". */
final class PdfTableWriter {
    private static final PDRectangle PAGE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = 28;
    private static final float SIZE = 8;
    private static final float LEADING = 10;
    private static final float PAD = 3;
    /** Jedna bunka nesmie byt vyssia nez strana. */
    private static final int MAX_LINES = 40;

    private PdfTableWriter() {
    }

    static byte[] write(Table t) {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font regular = font(doc, "/fonts/NotoSans-Regular.ttf");
            PDType0Font bold = font(doc, "/fonts/NotoSans-Bold.ttf");
            new Layout(doc, t, regular, bold).render();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PDType0Font font(PDDocument doc, String path) throws IOException {
        try (InputStream in = PdfTableWriter.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("Chýba písmo " + path);
            }
            return PDType0Font.load(doc, in, true);
        }
    }

    private static final class Layout {
        private final PDDocument doc;
        private final Table t;
        private final PDType0Font regular;
        private final PDType0Font bold;
        private final float[] widths;
        private final boolean[] numeric;
        private PDPageContentStream cs;
        private float y;

        Layout(PDDocument doc, Table t, PDType0Font regular, PDType0Font bold) throws IOException {
            this.doc = doc;
            this.t = t;
            this.regular = regular;
            this.bold = bold;
            int n = t.header().size();
            this.numeric = new boolean[n];
            for (int c = 0; c < n; c++) {
                final int col = c;
                this.numeric[c] = !t.rows().isEmpty() && t.rows().stream()
                        .allMatch(r -> r.get(col) == null || r.get(col) instanceof BigDecimal || r.get(col) instanceof Number);
            }
            this.widths = this.columnWidths(PAGE.getWidth() - 2 * MARGIN);
        }

        /**
         * Sirky podla skutocnej sirky textu: kazdy stlpec dostane aspon najdlhsie slovo (datum, cas, kod sa
         * nikdy nezlomia v polovici) a ked sa tabulka nezmesti, zuzuju sa len dlhe textove stlpce.
         */
        private float[] columnWidths(float available) throws IOException {
            int n = this.t.header().size();
            float[] natural = new float[n];
            float[] min = new float[n];
            for (int c = 0; c < n; c++) {
                for (String word : this.t.header().get(c).split("\\s+")) {
                    min[c] = Math.max(min[c], this.width(this.bold, word));
                }
                natural[c] = Math.max(min[c], this.width(this.bold, this.t.header().get(c)));
                for (List<Object> r : this.t.rows()) {
                    String text = Table.text(r.get(c));
                    for (String line : text.split("\n")) {
                        natural[c] = Math.max(natural[c], Math.min(this.width(this.regular, line), 260));
                    }
                    for (String word : text.split("\\s+")) {
                        min[c] = Math.max(min[c], Math.min(this.width(this.regular, word), 90));
                    }
                }
                natural[c] += 2 * PAD;
                min[c] = Math.min(min[c] + 2 * PAD, natural[c]);
            }
            float total = 0;
            float shrinkable = 0;
            for (int c = 0; c < n; c++) {
                total += natural[c];
                shrinkable += natural[c] - min[c];
            }
            float[] w = new float[n];
            if (total <= available) {
                for (int c = 0; c < n; c++) {
                    w[c] = natural[c] * available / total;
                }
            } else if (total - available <= shrinkable) {
                float ratio = (total - available) / shrinkable;
                for (int c = 0; c < n; c++) {
                    w[c] = natural[c] - (natural[c] - min[c]) * ratio;
                }
            } else {
                float minTotal = 0;
                for (float m : min) {
                    minTotal += m;
                }
                for (int c = 0; c < n; c++) {
                    w[c] = min[c] * available / minTotal;
                }
            }
            return w;
        }

        void render() throws IOException {
            this.newPage(true);
            if (this.t.rows().isEmpty()) {
                this.text(MARGIN, this.y - SIZE - 4, this.regular, 9, "Žiadne záznamy.");
            }
            boolean shade = false;
            for (List<Object> row : this.t.rows()) {
                List<List<String>> cells = new ArrayList<>();
                int lines = 1;
                for (int c = 0; c < row.size(); c++) {
                    List<String> wrapped = this.wrap(Table.text(row.get(c)), this.regular, this.widths[c] - 2 * PAD);
                    if (wrapped.size() > MAX_LINES) {
                        wrapped = new ArrayList<>(wrapped.subList(0, MAX_LINES));
                        wrapped.set(MAX_LINES - 1, wrapped.get(MAX_LINES - 1) + " …");
                    }
                    cells.add(wrapped);
                    lines = Math.max(lines, wrapped.size());
                }
                float h = lines * LEADING + 2 * PAD;
                if (this.y - h < MARGIN + 14) {
                    this.cs.close();
                    this.newPage(false);
                    shade = false;
                }
                if (shade) {
                    this.cs.setNonStrokingColor(0.965f);
                    this.cs.addRect(MARGIN, this.y - h, PAGE.getWidth() - 2 * MARGIN, h);
                    this.cs.fill();
                    this.cs.setNonStrokingColor(0f);
                }
                shade = !shade;
                this.drawRow(cells, this.regular);
                this.y -= h;
            }
            float full = PAGE.getWidth() - 2 * MARGIN;
            this.y -= 8;
            for (String note : this.t.notes()) {
                for (String line : this.wrap(note, this.regular, full)) {
                    if (this.y - LEADING - 2 < MARGIN + 14) {
                        this.cs.close();
                        this.newPage(false);
                    }
                    this.text(MARGIN, this.y - SIZE - 2, this.regular, 9, line);
                    this.y -= LEADING + 3;
                }
                this.y -= 6;
            }
            this.cs.close();
            this.footers();
        }

        private void newPage(boolean first) throws IOException {
            PDPage page = new PDPage(PAGE);
            this.doc.addPage(page);
            this.cs = new PDPageContentStream(this.doc, page);
            this.y = PAGE.getHeight() - MARGIN;
            if (first) {
                this.text(MARGIN, this.y - 14, this.bold, 14, this.t.title());
                this.y -= 20;
                if (this.t.subtitle() != null) {
                    this.cs.setNonStrokingColor(0.35f);
                    this.text(MARGIN, this.y - 9, this.regular, 9, this.t.subtitle());
                    this.cs.setNonStrokingColor(0f);
                    this.y -= 14;
                }
                this.y -= 4;
            }
            List<List<String>> head = new ArrayList<>();
            int lines = 1;
            for (int c = 0; c < this.t.header().size(); c++) {
                List<String> w = this.wrap(this.t.header().get(c), this.bold, this.widths[c] - 2 * PAD);
                head.add(w);
                lines = Math.max(lines, w.size());
            }
            float h = lines * LEADING + 2 * PAD;
            this.cs.setNonStrokingColor(0.91f, 0.93f, 0.97f);
            this.cs.addRect(MARGIN, this.y - h, PAGE.getWidth() - 2 * MARGIN, h);
            this.cs.fill();
            this.cs.setNonStrokingColor(0f);
            this.drawRow(head, this.bold);
            this.y -= h;
        }

        private void drawRow(List<List<String>> cells, PDType0Font font) throws IOException {
            float x = MARGIN;
            for (int c = 0; c < cells.size(); c++) {
                List<String> lines = cells.get(c);
                for (int i = 0; i < lines.size(); i++) {
                    float lineY = this.y - PAD - SIZE - i * LEADING + 1;
                    if (this.numeric[c]) {
                        float w = this.width(font, lines.get(i));
                        this.text(x + this.widths[c] - PAD - w, lineY, font, SIZE, lines.get(i));
                    } else {
                        this.text(x + PAD, lineY, font, SIZE, lines.get(i));
                    }
                }
                x += this.widths[c];
            }
        }

        private void footers() throws IOException {
            int total = this.doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                try (PDPageContentStream f = new PDPageContentStream(this.doc, this.doc.getPage(i),
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String left = "FIRST Global Slovakia HQ · " + this.t.title();
                    String right = "strana " + (i + 1) + " / " + total;
                    f.setNonStrokingColor(0.45f);
                    f.beginText();
                    f.setFont(this.regular, 7);
                    f.newLineAtOffset(MARGIN, MARGIN - 12);
                    f.showText(this.safe(this.regular, left));
                    f.endText();
                    f.beginText();
                    f.newLineAtOffset(PAGE.getWidth() - MARGIN - this.regular.getStringWidth(right) / 1000 * 7,
                            MARGIN - 12);
                    f.showText(right);
                    f.endText();
                }
            }
        }

        private void text(float x, float y, PDType0Font font, float size, String s) throws IOException {
            this.cs.beginText();
            this.cs.setFont(font, size);
            this.cs.newLineAtOffset(x, y);
            this.cs.showText(this.safe(font, s));
            this.cs.endText();
        }

        private float width(PDType0Font font, String s) throws IOException {
            return font.getStringWidth(this.safe(font, s)) / 1000 * SIZE;
        }

        private List<String> wrap(String text, PDType0Font font, float max) throws IOException {
            List<String> out = new ArrayList<>();
            for (String paragraph : text.replace("\r", "").split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                for (String word : paragraph.split(" ")) {
                    String candidate = line.isEmpty() ? word : line + " " + word;
                    if (this.width(font, candidate) <= max) {
                        line.setLength(0);
                        line.append(candidate);
                        continue;
                    }
                    if (!line.isEmpty()) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    String rest = word;
                    while (this.width(font, rest) > max && rest.length() > 1) {
                        int n = rest.length() - 1;
                        while (n > 1 && this.width(font, rest.substring(0, n)) > max) {
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

        /** Znaky, ktore pismo nema (napr. emoji), nahradime otaznikom namiesto padu exportu. */
        private String safe(PDType0Font font, String s) {
            StringBuilder sb = new StringBuilder(s.length());
            s.replace('\t', ' ').replace('\n', ' ').codePoints().forEach(cp -> {
                String ch = new String(Character.toChars(cp));
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
