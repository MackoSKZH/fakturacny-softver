package com.fakturacnysoftver.web.partner;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record Partner(
        long id,
        String name,
        String kind,
        String ico,
        String web,
        String contactName,
        String contactEmail,
        String contactPhone,
        Long customerId,
        Long ownerPersonId,
        String ownerName,
        List<String> tags,
        String note,
        BigDecimal secured,
        BigDecimal negotiating,
        BigDecimal received,
        LocalDate lastContact,
        LocalDate nextStepOn) {

    public String kindLabel() {
        return PartnerKind.labelOf(this.kind);
    }

    /** Dlho bez kontaktu - vztah treba udrziavat, nielen pytat peniaze. */
    public boolean isNeglected(LocalDate today) {
        return this.lastContact == null || this.lastContact.isBefore(today.minusDays(120));
    }
}
