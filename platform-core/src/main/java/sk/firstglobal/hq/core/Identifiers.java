package sk.firstglobal.hq.core;

import java.math.BigInteger;
import java.util.regex.Pattern;

/** Formalne kontroly slovenskych identifikatorov. Neoveruju existenciu v registroch. */
public final class Identifiers {
    private static final Pattern ICO = Pattern.compile("\\d{8}");
    private static final Pattern DIC = Pattern.compile("\\d{10}");
    private static final Pattern IC_DPH_SK = Pattern.compile("SK\\d{10}");
    private static final Pattern VARIABLE_SYMBOL = Pattern.compile("\\d{1,10}");
    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}");
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private Identifiers() {
    }

    public static String stripSpaces(String value) {
        return value == null ? null : value.replaceAll("\\s+", "");
    }

    public static boolean isIco(String value) {
        return value != null && ICO.matcher(value).matches();
    }

    public static boolean isDic(String value) {
        return value != null && DIC.matcher(value).matches();
    }

    public static boolean isSlovakIcDph(String value) {
        return value != null && IC_DPH_SK.matcher(value).matches();
    }

    /** Variabilny symbol v SEPA/SK praxi: 1 az 10 cislic. */
    public static boolean isVariableSymbol(String value) {
        return value != null && VARIABLE_SYMBOL.matcher(value).matches();
    }

    /** IBAN kontrola podla ISO 13616 (mod 97). */
    public static boolean isIban(String raw) {
        String iban = stripSpaces(raw);
        if (iban == null) {
            return false;
        }
        iban = iban.toUpperCase();
        if (!IBAN.matcher(iban).matches()) {
            return false;
        }
        if (iban.startsWith("SK") && iban.length() != 24) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        StringBuilder digits = new StringBuilder(rearranged.length() * 2);
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.getNumericValue(c));
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue() == 1;
    }
}
