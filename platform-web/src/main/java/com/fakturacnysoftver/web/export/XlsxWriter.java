package com.fakturacnysoftver.web.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Minimalny zapis .xlsx (Office Open XML) bez externej kniznice: jeden harok, tucna zamrazena hlavicka,
 * automaticky filter, cisla a datumy ako skutocne hodnoty (Excel s nimi vie pocitat a triedit).
 * Texty su vzdy "inline string" - Excel ich nikdy nevyhodnoti ako vzorec, takze CSV injection tu nehrozi.
 */
final class XlsxWriter {
    private static final LocalDate EPOCH = LocalDate.of(1899, 12, 30);
    private static final int STYLE_HEADER = 1;
    private static final int STYLE_DATE = 2;
    private static final int STYLE_DATETIME = 3;
    private static final int STYLE_MONEY = 4;
    private static final int STYLE_TITLE = 5;

    private XlsxWriter() {
    }

    static byte[] write(Table t) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                    </Types>""");
            put(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            put(zip, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                    </Relationships>""");
            put(zip, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="%s" sheetId="1" r:id="rId1"/></sheets>
                    <definedNames><definedName name="_xlnm._FilterDatabase" localSheetId="0" hidden="1">'%s'!$A$%d:$%s$%d</definedName></definedNames>
                    </workbook>""".formatted(xml(sheetName(t.title())), sheetName(t.title()).replace("'", "''"),
                    headerRow(t), column(t.header().size() - 1), headerRow(t) + t.rows().size()));
            put(zip, "xl/styles.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                    <numFmts count="3"><numFmt numFmtId="164" formatCode="d.m.yyyy"/><numFmt numFmtId="165" formatCode="d.m.yyyy h:mm"/><numFmt numFmtId="166" formatCode="#,##0.00"/></numFmts>
                    <fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><name val="Calibri"/></font></fonts>
                    <fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FFE8EEF7"/></patternFill></fill></fills>
                    <borders count="1"><border/></borders>
                    <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
                    <cellXfs count="6">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
                    <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                    <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                    <xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
                    <xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
                    </cellXfs>
                    <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
                    </styleSheet>""");
            put(zip, "xl/worksheets/sheet1.xml", sheet(t));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** Nad hlavickou je nazov a podnadpis (napr. filter), aby vytlaceny harok bol zrozumitelny. */
    private static int headerRow(Table t) {
        return t.subtitle() == null ? 3 : 4;
    }

    private static String sheet(Table t) {
        int hr = headerRow(t);
        StringBuilder sb = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                """);
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"").append(hr)
                .append("\" topLeftCell=\"A").append(hr + 1).append("\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                .append("</sheetView></sheetViews><cols>");
        List<Integer> widths = t.columnWidths(60);
        for (int i = 0; i < widths.size(); i++) {
            sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"")
                    .append(Math.max(8, widths.get(i) + 2)).append("\" customWidth=\"1\"/>");
        }
        sb.append("</cols><sheetData>");
        sb.append("<row r=\"1\">");
        cell(sb, "A1", t.title(), STYLE_TITLE);
        sb.append("</row>");
        if (t.subtitle() != null) {
            sb.append("<row r=\"2\">");
            cell(sb, "A2", t.subtitle(), 0);
            sb.append("</row>");
        }
        sb.append("<row r=\"").append(hr).append("\">");
        for (int c = 0; c < t.header().size(); c++) {
            cell(sb, column(c) + hr, t.header().get(c), STYLE_HEADER);
        }
        sb.append("</row>");
        int r = hr;
        for (List<Object> row : t.rows()) {
            r++;
            sb.append("<row r=\"").append(r).append("\">");
            for (int c = 0; c < row.size(); c++) {
                cell(sb, column(c) + r, row.get(c), -1);
            }
            sb.append("</row>");
        }
        int lastData = r;
        r++;
        for (String note : t.notes()) {
            r++;
            sb.append("<row r=\"").append(r).append("\">");
            cell(sb, "A" + r, note, 0);
            sb.append("</row>");
        }
        sb.append("</sheetData>");
        sb.append("<autoFilter ref=\"A").append(hr).append(':').append(column(t.header().size() - 1)).append(lastData)
                .append("\"/>");
        sb.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>");
        sb.append("<pageSetup orientation=\"landscape\" paperSize=\"9\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        sb.append("</worksheet>");
        return sb.toString();
    }

    private static void cell(StringBuilder sb, String ref, Object v, int style) {
        if (v == null || (v instanceof String s && s.isEmpty())) {
            return;
        }
        sb.append("<c r=\"").append(ref).append('"');
        if (v instanceof BigDecimal b) {
            sb.append(" s=\"").append(style < 0 ? STYLE_MONEY : style).append("\"><v>").append(b.toPlainString())
                    .append("</v></c>");
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short) {
            appendStyle(sb, style).append("><v>").append(v).append("</v></c>");
        } else if (v instanceof LocalDate d) {
            sb.append(" s=\"").append(STYLE_DATE).append("\"><v>").append(ChronoUnit.DAYS.between(EPOCH, d))
                    .append("</v></c>");
        } else if (v instanceof LocalDateTime dt) {
            double serial = ChronoUnit.DAYS.between(EPOCH, dt.toLocalDate()) + dt.toLocalTime().toSecondOfDay() / 86400.0;
            sb.append(" s=\"").append(STYLE_DATETIME).append("\"><v>").append(serial).append("</v></c>");
        } else {
            appendStyle(sb, style).append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    .append(xml(Table.text(v))).append("</t></is></c>");
        }
    }

    private static StringBuilder appendStyle(StringBuilder sb, int style) {
        return style > 0 ? sb.append(" s=\"").append(style).append('"') : sb;
    }

    static String column(int index) {
        StringBuilder s = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) {
            s.insert(0, (char)('A' + (n - 1) % 26));
        }
        return s.toString();
    }

    /** Excel: najviac 31 znakov, bez []:*?/\ . */
    static String sheetName(String title) {
        String s = title.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        s = s.length() > 31 ? s.substring(0, 31).trim() : s;
        return s.isEmpty() ? "Export" : s;
    }

    /** XML escapovanie; riadiace znaky, ktore XML 1.0 nepovoluje, vynechame. */
    static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            switch (cp) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> {
                    if (cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF)
                            || (cp >= 0xE000 && cp <= 0xFFFD) || cp >= 0x10000) {
                        sb.appendCodePoint(cp);
                    }
                }
            }
        });
        return sb.toString();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
