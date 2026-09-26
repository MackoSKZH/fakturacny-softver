package com.fakturacnysoftver.web.export;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** CSV pre slovensky Excel: bodkociarka, BOM kvoli diakritike, ochrana proti vzorcom (CSV injection). */
public final class Csv {
    private final StringBuilder sb = new StringBuilder("﻿");

    public Csv(String... header) {
        this.row((Object[])header);
    }

    public Csv row(Object... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                this.sb.append(';');
            }
            this.sb.append(cell(cells[i]));
        }
        this.sb.append("\r\n");
        return this;
    }

    public ResponseEntity<byte[]> response(String filename) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(this.sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String cell(Object o) {
        if (o == null) {
            return "";
        }
        String s = o instanceof java.math.BigDecimal b ? b.toPlainString().replace('.', ',')
                : o instanceof List<?> l ? String.join(", ", l.stream().map(String::valueOf).toList())
                : String.valueOf(o);
        String v = s.matches("(?s)^[=+\\-@\\t\\r].*") && !s.matches("^-?\\d+([.,]\\d+)?$") ? "'" + s : s;
        return v.contains(";") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
