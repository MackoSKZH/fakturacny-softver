package com.fakturacnysoftver.web.organization;

import com.fakturacnysoftver.core.Party;

/** Udaje zdruzenia ako dodavatela. Na fakturu sa kopiruju v case vystavenia. */
public record Organization(
        String name,
        String street,
        String city,
        String postalCode,
        String country,
        String ico,
        String dic,
        String icDph,
        String email,
        String phone,
        String iban,
        String bic,
        String registrationNote,
        String invoicePattern,
        int dueDays) {

    public static Organization empty() {
        return new Organization("", "", "", "", "SK", "", "", "", "", "", "", "", "", "{YYYY}{NNNN}", 14);
    }

    public Party toParty() {
        return new Party(this.name, this.street, this.city, this.postalCode, this.country,
                blankToNull(this.ico), blankToNull(this.dic), blankToNull(this.icDph),
                blankToNull(this.email), blankToNull(this.phone));
    }

    public boolean isVatPayer() {
        return this.icDph != null && !this.icDph.isBlank();
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
