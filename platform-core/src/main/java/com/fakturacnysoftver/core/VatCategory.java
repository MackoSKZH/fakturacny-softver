package com.fakturacnysoftver.core;

/**
 * Kategorie DPH podla EN 16931 (UNCL5305), ktore potrebujeme pre SK.
 * Sadzby platne od 1.1.2025: 23 % zakladna, 19 % a 5 % znizene.
 */
public enum VatCategory {
    /** Standardna alebo znizena sadzba (23 / 19 / 5 %). */
    STANDARD("S", true, null, null),
    /** Nulova sadzba. */
    ZERO("Z", true, null, null),
    /** Oslobodene od dane - vyzaduje dovod (paragraf zakona o DPH). */
    EXEMPT("E", true, "VATEX-EU-132", "Oslobodené od dane podľa zákona o DPH"),
    /** Prenesenie danovej povinnosti (tuzemsky reverse charge). */
    REVERSE_CHARGE("AE", true, "VATEX-EU-AE", "Prenesenie daňovej povinnosti"),
    /** Dodavatel nie je platitel DPH - typicky obcianske zdruzenie. */
    NOT_SUBJECT("O", false, "VATEX-EU-O", "Dodávateľ nie je platiteľom DPH");

    private final String code;
    private final boolean hasRate;
    private final String defaultExemptionCode;
    private final String defaultExemptionReason;

    VatCategory(String code, boolean hasRate, String defaultExemptionCode, String defaultExemptionReason) {
        this.code = code;
        this.hasRate = hasRate;
        this.defaultExemptionCode = defaultExemptionCode;
        this.defaultExemptionReason = defaultExemptionReason;
    }

    public String code() {
        return this.code;
    }

    /** Kategoria O nesmie v UBL obsahovat cbc:Percent (pravidlo BR-O-05). */
    public boolean hasRate() {
        return this.hasRate;
    }

    public String defaultExemptionCode() {
        return this.defaultExemptionCode;
    }

    public String defaultExemptionReason() {
        return this.defaultExemptionReason;
    }

    public boolean requiresExemptionReason() {
        return this.defaultExemptionCode != null;
    }
}
