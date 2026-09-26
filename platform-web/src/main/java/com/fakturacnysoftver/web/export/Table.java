package com.fakturacnysoftver.web.export;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Tabulka na export - jedny data, tri formaty: Excel (.xlsx), PDF (na tlac a pre partnerov) a CSV (na import).
 * Hodnoty nechavame typove (BigDecimal, LocalDate), aby v Exceli boli cisla a datumy, nie text.
 */
public final class Table {
    public enum Format {
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
        PDF("pdf", "application/pdf"),
        CSV("csv", "text/csv;charset=UTF-8");

        private final String extension;
        private final String contentType;

        Format(String extension, String contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return this.extension;
        }

        public static Format of(String extension) {
            return Arrays.stream(values()).filter(f -> f.extension.equalsIgnoreCase(extension)).findFirst()
                    .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        }
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d.M.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d.M.yyyy HH:mm");

    private final String title;
    private String subtitle;
    private final List<String> header;
    private final List<List<Object>> rows = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private final List<String> preamble = new ArrayList<>();
    private boolean portrait;

    public Table(String title, String... header) {
        this.title = title;
        this.header = List.of(header);
    }

    /** Doplnkovy riadok pod nazvom - napr. obdobie alebo filter. */
    public Table subtitle(String subtitle) {
        this.subtitle = subtitle == null || subtitle.isBlank() ? null : subtitle;
        return this;
    }

    public Table row(Object... cells) {
        List<Object> r = new ArrayList<>(Arrays.asList(cells));
        while (r.size() < this.header.size()) {
            r.add(null);
        }
        this.rows.add(r);
        return this;
    }

    /** Poznamka pod tabulkou (suhrn, podpisy). V CSV sa nevypisuje, aby import ostal cisty. */
    public Table note(String note) {
        this.notes.add(note);
        return this;
    }

    /** Text nad tabulkou (napr. identifikacia stran pri potvrdeni). V Exceli ide medzi poznamky. */
    public Table preamble(String paragraph) {
        this.preamble.add(paragraph);
        return this;
    }

    public List<String> preamble() {
        return this.preamble;
    }

    /** PDF na vysku - pre listiny (potvrdenia), nie siroke prehlady. */
    public Table portrait() {
        this.portrait = true;
        return this;
    }

    public boolean isPortrait() {
        return this.portrait;
    }

    public List<String> notes() {
        return this.notes;
    }

    public String title() {
        return this.title;
    }

    public String subtitle() {
        return this.subtitle;
    }

    public List<String> header() {
        return this.header;
    }

    public List<List<Object>> rows() {
        return this.rows;
    }

    public byte[] bytes(Format format) {
        return switch (format) {
            case XLSX -> XlsxWriter.write(this);
            case PDF -> PdfTableWriter.write(this);
            case CSV -> this.csv().getBytes(StandardCharsets.UTF_8);
        };
    }

    public ResponseEntity<byte[]> response(Format format, String baseName) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + baseName + "." + format.extension() + "\"")
                .contentType(MediaType.parseMediaType(format.contentType))
                .body(this.bytes(format));
    }

    public ResponseEntity<byte[]> response(String extension, String baseName) {
        return this.response(Format.of(extension), baseName);
    }

    /** Sirka stlpca v znakoch podla najdlhsieho obsahu (s hornou hranicou). */
    List<Integer> columnWidths(int max) {
        List<Integer> w = new ArrayList<>();
        for (int c = 0; c < this.header.size(); c++) {
            int width = this.header.get(c).length();
            for (List<Object> r : this.rows) {
                for (String line : text(r.get(c)).split("\n")) {
                    width = Math.max(width, line.length());
                }
            }
            w.add(Math.min(width, max));
        }
        return w;
    }

    // ---------- CSV pre slovensky Excel / import ----------

    private String csv() {
        StringBuilder sb = new StringBuilder("﻿");
        appendCsv(sb, new ArrayList<>(this.header));
        this.rows.forEach(r -> appendCsv(sb, r));
        return sb.toString();
    }

    private static void appendCsv(StringBuilder sb, List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(';');
            }
            sb.append(csvCell(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /** Bodkociarka, desatinna ciarka, ISO datumy a ochrana proti vzorcom (CSV injection). */
    public static String csvCell(Object o) {
        if (o == null) {
            return "";
        }
        String s = o instanceof BigDecimal b ? b.toPlainString().replace('.', ',')
                : o instanceof LocalDate || o instanceof LocalDateTime ? o.toString()
                : text(o);
        String v = s.matches("(?s)^[=+\\-@\\t\\r].*") && !s.matches("^-?\\d+([.,]\\d+)?$") ? "'" + s : s;
        return v.contains(";") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    /** Hodnota ako text pre cloveka (PDF, texty v Exceli). */
    public static String text(Object o) {
        if (o == null) {
            return "";
        }
        if (o instanceof BigDecimal b) {
            DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(Locale.ROOT);
            sym.setDecimalSeparator(',');
            sym.setGroupingSeparator(' ');
            return new DecimalFormat("#,##0.00", sym).format(b);
        }
        if (o instanceof LocalDate d) {
            return DATE.format(d);
        }
        if (o instanceof LocalDateTime dt) {
            return DATE_TIME.format(dt);
        }
        if (o instanceof Boolean b) {
            return b ? "áno" : "nie";
        }
        if (o instanceof List<?> l) {
            return String.join(", ", l.stream().map(Table::text).toList());
        }
        return String.valueOf(o);
    }
}
