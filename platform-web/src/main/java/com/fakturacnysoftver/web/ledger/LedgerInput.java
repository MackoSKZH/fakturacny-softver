package com.fakturacnysoftver.web.ledger;

import java.util.List;

/** Vstup z tabulky - vsetko ako text, aby sa dali vratit presne chyby ("12,5x" nie je suma). */
public record LedgerInput(
        String entryDate,
        String description,
        String direction,
        String amount,
        Long projectId,
        List<String> tags,
        String category,
        String counterparty,
        String documentRef,
        String paymentMethod,
        String note,
        Integer version) {
}
