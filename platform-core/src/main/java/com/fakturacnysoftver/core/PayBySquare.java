package com.fakturacnysoftver.core;

import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.zip.CRC32;

/**
 * Obsah QR kodu PAY by square (slovensky bankovy standard, verzia 1.1).
 * Postup: polia oddelene tabulatorom -> CRC32 (little endian) -> raw LZMA1
 * (lc=3, lp=0, pb=2, slovnik 128 KiB) -> 2 B hlavicka + 2 B dlzka -> base32hex bez paddingu.
 *
 * <p>Pred ostrym pouzitim treba QR z vygenerovaneho PDF naskenovat aspon v dvoch bankovych appkach.
 */
public final class PayBySquare {
    private static final char[] BASE32HEX = "0123456789ABCDEFGHIJKLMNOPQRSTUV".toCharArray();
    private static final DateTimeFormatter DUE_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    public record Payment(
            BigDecimal amount,
            String currency,
            LocalDate dueDate,
            String variableSymbol,
            String constantSymbol,
            String specificSymbol,
            String note,
            String iban,
            String bic,
            String beneficiaryName,
            String beneficiaryAddress1,
            String beneficiaryAddress2) {
    }

    private PayBySquare() {
    }

    /** QR platba pre fakturu - suma na uhradu, splatnost, VS a ucet dodavatela. */
    public static Payment paymentFor(Invoice inv) {
        Party seller = inv.seller();
        String address2 = join(seller.postalCode(), seller.city());
        return new Payment(inv.totals().payableAmount(), inv.currency(), inv.dueDate(),
                inv.variableSymbol(), null, null, "Faktura " + inv.number(), inv.payeeIban(), inv.payeeBic(),
                seller.name(), seller.street(), address2);
    }

    public static String encode(Payment p) {
        byte[] payload = payload(p).getBytes(StandardCharsets.UTF_8);

        CRC32 crc = new CRC32();
        crc.update(payload);
        long checksum = crc.getValue();

        byte[] total = new byte[payload.length + 4];
        for (int i = 0; i < 4; i++) {
            total[i] = (byte)(checksum >>> (8 * i));
        }
        System.arraycopy(payload, 0, total, 4, payload.length);
        if (total.length > 0xFFFF) {
            throw new IllegalArgumentException("PAY by square údaje sú príliš dlhé.");
        }

        byte[] compressed = lzma(total);
        byte[] out = new byte[compressed.length + 4];
        out[0] = 0x00; // typ by square, verzia
        out[1] = 0x00; // typ dokumentu, rezerva
        out[2] = (byte)(total.length & 0xFF);
        out[3] = (byte)(total.length >>> 8);
        System.arraycopy(compressed, 0, out, 4, compressed.length);
        return base32hex(out);
    }

    /** Nekomprimovany obsah - zverejneny kvoli testom a diagnostike. */
    static String payload(Payment p) {
        return String.join("\t",
                "",                                   // ID dokladu
                "1",                                  // pocet platieb
                "1",                                  // typ: platobny prikaz
                p.amount() == null ? "" : p.amount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                clean(p.currency()),
                p.dueDate() == null ? "" : p.dueDate().format(DUE_DATE),
                clean(p.variableSymbol()),
                clean(p.constantSymbol()),
                clean(p.specificSymbol()),
                "",                                   // referencia v SEPA formate - symboly su vyssie
                clean(p.note()),
                "1",                                  // pocet uctov
                clean(Identifiers.stripSpaces(p.iban())),
                clean(p.bic()),
                "0",                                  // bez trvaleho prikazu
                "0",                                  // bez inkasa
                clean(p.beneficiaryName()),
                clean(p.beneficiaryAddress1()),
                clean(p.beneficiaryAddress2()));
    }

    private static byte[] lzma(byte[] data) {
        try {
            LZMA2Options options = new LZMA2Options();
            options.setLcLp(3, 0);
            options.setPb(2);
            options.setDictSize(128 * 1024);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (LZMAOutputStream lz = new LZMAOutputStream(bytes, options, true)) {
                lz.write(data);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String base32hex(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32HEX[(buffer >>> (bits - 5)) & 0x1F]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32HEX[(buffer << (5 - bits)) & 0x1F]);
        }
        return sb.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").trim();
    }

    private static String join(String a, String b) {
        return (clean(a) + " " + clean(b)).trim();
    }
}
