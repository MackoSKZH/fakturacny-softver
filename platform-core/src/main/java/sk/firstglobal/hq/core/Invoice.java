package sk.firstglobal.hq.core;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Vystavena faktura. Nemenny objekt - po vystaveni sa faktura nemeni, opravuje sa dobropisom.
 *
 * @param number          poradove cislo faktury (jedinecne v ramci ciselneho radu)
 * @param issueDate       datum vyhotovenia
 * @param deliveryDate    datum dodania tovaru / sluzby
 * @param dueDate         datum splatnosti
 * @param currency        ISO 4217, standardne EUR
 * @param seller          dodavatel
 * @param buyer           odberatel
 * @param lines           polozky
 * @param variableSymbol  variabilny symbol
 * @param payeeIban       IBAN pre platbu
 * @param payeeBic        BIC/SWIFT
 * @param buyerReference  referencia odberatela (objednavka, kontaktna osoba); Peppol ju vyzaduje
 * @param projectCode     kod projektu pre rozpocet (do UBL ide ako ProjectReference BT-11)
 * @param note            poznamka na fakture
 */
public record Invoice(
        String number,
        LocalDate issueDate,
        LocalDate deliveryDate,
        LocalDate dueDate,
        String currency,
        Party seller,
        Party buyer,
        List<InvoiceLine> lines,
        String variableSymbol,
        String payeeIban,
        String payeeBic,
        String buyerReference,
        String projectCode,
        String note) {

    public Invoice {
        Objects.requireNonNull(seller, "seller");
        Objects.requireNonNull(buyer, "buyer");
        lines = lines == null ? List.of() : List.copyOf(lines);
        currency = currency == null || currency.isBlank() ? "EUR" : currency;
        payeeIban = Identifiers.stripSpaces(payeeIban);
    }

    public InvoiceTotals totals() {
        return InvoiceTotals.of(this);
    }
}
