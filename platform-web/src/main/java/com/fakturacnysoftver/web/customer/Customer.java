package com.fakturacnysoftver.web.customer;

import com.fakturacnysoftver.core.Party;

public record Customer(
        Long id,
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

    public Party toParty() {
        return new Party(this.name, this.street, this.city, this.postalCode, this.country,
                blankToNull(this.ico), blankToNull(this.dic), blankToNull(this.icDph),
                blankToNull(this.email), blankToNull(this.phone));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
