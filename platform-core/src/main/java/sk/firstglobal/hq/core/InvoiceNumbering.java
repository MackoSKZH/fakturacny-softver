package sk.firstglobal.hq.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ciselne rady faktur. Vzor napr. "{YYYY}{NNNN}" -> 20270001 (zaroven pouzitelne ako VS).
 * Tokeny: {YYYY}, {YY}, {N...} kde pocet N = minimalna sirka poradoveho cisla.
 *
 * <p>Samotne pridelenie dalsieho cisla musi prebehnut v DB transakcii so zamkom na rade,
 * aby nevznikli duplicity ani medzery - tato trieda len formatuje.
 */
public final class InvoiceNumbering {
    private static final Pattern SEQ_TOKEN = Pattern.compile("\\{(N+)}");

    private InvoiceNumbering() {
    }

    public static String format(String pattern, int year, long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("Poradové číslo musí byť kladné.");
        }
        Matcher m = SEQ_TOKEN.matcher(pattern);
        if (!m.find()) {
            throw new IllegalArgumentException("Vzor musí obsahovať {N...}: " + pattern);
        }
        int width = m.group(1).length();
        String seq = String.format("%0" + width + "d", sequence);
        if (seq.length() > width) {
            throw new IllegalStateException("Číselný rad " + pattern + " pretiekol v roku " + year);
        }
        return pattern
                .replace("{YYYY}", String.format("%04d", year))
                .replace("{YY}", String.format("%02d", year % 100))
                .replace(m.group(0), seq);
    }

    /** Variabilny symbol odvodeny z cisla faktury (len cislice, max 10), inak null. */
    public static String variableSymbolOf(String invoiceNumber) {
        if (invoiceNumber == null) {
            return null;
        }
        String digits = invoiceNumber.replaceAll("\\D", "");
        return Identifiers.isVariableSymbol(digits) ? digits : null;
    }
}
