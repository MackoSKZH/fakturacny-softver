package com.fakturacnysoftver.core;

import org.junit.jupiter.api.Test;
import org.tukaani.xz.LZMAInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PayBySquareTest {
    /**
     * Vystup nezavislej implementacie - Python balik pay-by-square 0.2.0 (liblzma) pre rovnaku platbu.
     * Ak sa nasa serializacia odchyli od formatu, tento test to odhali.
     */
    private static final String PYTHON_REFERENCE = "0009K0005OFHJH709BV113CULJT82ANK4D0DJ8SAOVAII0454E819RKNTMSUP8"
            + "CIL9QPOFO3BKC9IG20A9RIGUJE5FM2SHSCO96BQ8KI3LVR4L144FKDRICU8SOT8AF6BSD2JU2SMNKLH1UB5QKN8K73RUVPRSO6MK"
            + "QTGU9AS1A7V0644E7U81690HAO4518KMSOFLL3CEOPBHNLJO11M349VDVJPVJU5J00";

    @Test
    void payloadMatchesIndependentImplementation() throws Exception {
        PayBySquare.Payment payment = PayBySquare.paymentFor(TestInvoices.charitableAdvertisingInvoice());

        assertEquals(decode(PYTHON_REFERENCE), PayBySquare.payload(payment));
        // xz-java a liblzma s rovnakymi parametrami davaju identicky vystup - drzime to ako regresiu
        assertEquals(PYTHON_REFERENCE, PayBySquare.encode(payment));
    }

    @Test
    void encodedCodeRoundTripsWithValidChecksum() throws Exception {
        PayBySquare.Payment payment = PayBySquare.paymentFor(TestInvoices.charitableAdvertisingInvoice());

        String code = PayBySquare.encode(payment);

        assertEquals(PayBySquare.payload(payment), decode(code));
        assertFalse(code.contains("="), "base32hex bez paddingu");
    }

    @Test
    void tabsAndNewlinesCannotBreakFieldStructure() throws Exception {
        PayBySquare.Payment payment = new PayBySquare.Payment(new BigDecimal("10"), "EUR", LocalDate.of(2027, 3, 1),
                "1", null, null, "riadok 1\n\triadok 2", TestInvoices.VALID_SK_IBAN, null, "OZ", null, null);

        String payload = decode(PayBySquare.encode(payment));

        assertEquals(19, payload.split("\t", -1).length);
        assertEquals("10.00", payload.split("\t", -1)[3]);
    }

    /** Dekoder podla specifikacie - rovnaky postup ako bankova aplikacia. */
    static String decode(String code) throws Exception {
        String alphabet = "0123456789ABCDEFGHIJKLMNOPQRSTUV";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : code.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(c);
            bits += 5;
            if (bits >= 8) {
                bytes.write((buffer >>> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        byte[] data = bytes.toByteArray();
        assertEquals(0, data[0]);
        assertEquals(0, data[1]);
        int length = (data[2] & 0xFF) | ((data[3] & 0xFF) << 8);

        // Kodovanie pouziva koncovu znacku LZMA (ako liblzma), preto dekodujeme s neznamou dlzkou
        // a dlzku z hlavicky overime az potom.
        byte[] total;
        try (LZMAInputStream in = new LZMAInputStream(
                new ByteArrayInputStream(data, 4, data.length - 4), -1, 3, 0, 2, 128 * 1024, null)) {
            total = in.readAllBytes();
        }
        assertEquals(length, total.length, "dĺžka v hlavičke");

        CRC32 crc = new CRC32();
        crc.update(total, 4, total.length - 4);
        long expected = (total[0] & 0xFFL) | (total[1] & 0xFFL) << 8 | (total[2] & 0xFFL) << 16 | (total[3] & 0xFFL) << 24;
        assertEquals(expected, crc.getValue(), "CRC32");
        return new String(total, 4, total.length - 4, StandardCharsets.UTF_8);
    }
}
