package com.fakturacnysoftver.web.invoice;

import com.fakturacnysoftver.core.InvoiceLine;

import java.time.LocalDate;
import java.util.List;

/** Vstup na vystavenie faktury. Cislo pridelí az sluzba. */
public record InvoiceDraft(
        long customerId,
        Long projectId,
        LocalDate issueDate,
        LocalDate deliveryDate,
        LocalDate dueDate,
        String variableSymbol,
        String buyerReference,
        String note,
        List<InvoiceLine> lines) {
}
