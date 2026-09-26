package sk.firstglobal.hq.core;

/**
 * Dodavatel alebo odberatel.
 *
 * @param name       obchodne meno / nazov zdruzenia (povinne)
 * @param street     ulica a cislo
 * @param city       obec
 * @param postalCode PSC
 * @param country    ISO 3166-1 alpha-2, napr. "SK"
 * @param ico        IČO (8 cislic), ak je pridelene
 * @param dic        DIČ (10 cislic) - zaroven Peppol identifikator 0245:DIČ
 * @param icDph      IČ DPH v tvare SK + 10 cislic, len pre platitelov DPH
 * @param email      kontaktny e-mail
 * @param phone      kontaktny telefon
 */
public record Party(
        String name,
        String street,
        String city,
        String postalCode,
        String country,
        String ico,
        String dic,
        String icDph,
        String email,
        String phone) {

    /** Peppol schema pre slovenske DIČ (Peppol Code Lists v9.5+). */
    public static final String PEPPOL_SCHEME_SK_DIC = "0245";

    public Party {
        country = country == null || country.isBlank() ? "SK" : country.trim().toUpperCase();
        ico = Identifiers.stripSpaces(ico);
        dic = Identifiers.stripSpaces(dic);
        icDph = Identifiers.stripSpaces(icDph);
    }

    public boolean isVatRegistered() {
        return this.icDph != null && !this.icDph.isBlank();
    }

    /** Peppol participant ID vo forme "0245:1234567890" alebo null, ak DIČ chyba. */
    public String peppolId() {
        if (this.dic == null || this.dic.isBlank()) {
            return null;
        }
        return PEPPOL_SCHEME_SK_DIC + ":" + this.dic;
    }
}
