package sk.firstglobal.hq.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Vzorove faktury pre testy. Vsetky udaje su fiktivne. */
public final class TestInvoices {
    public static final String VALID_SK_IBAN = "SK3112000000198742637541";

    private TestInvoices() {
    }

    /** Obcianske zdruzenie (neplatitel DPH) - napr. FIRST Global Slovakia. */
    public static Party civicAssociation() {
        return new Party("Robotické združenie, o. z.", "Hlavná 1", "Bratislava", "81101", "SK",
                "12345678", "2120000000", null, "info@example.sk", "+421900000000");
    }

    /** Sponzor - platitel DPH. */
    public static Party vatPayerCompany() {
        return new Party("Sponzor s.r.o.", "Priemyselná 5", "Košice", "04001", "SK",
                "87654321", "2020000000", "SK2020000000", "fakturacie@sponzor.example", null);
    }

    /** OZ fakturuje sponzorovi charitativnu reklamu - bez DPH. */
    public static Invoice charitableAdvertisingInvoice() {
        return new Invoice("20270001",
                LocalDate.of(2027, 1, 15),
                LocalDate.of(2027, 1, 15),
                LocalDate.of(2027, 1, 29),
                "EUR",
                civicAssociation(),
                vatPayerCompany(),
                List.of(InvoiceLine.notSubjectToVat("Charitatívna reklama - logo na robote FGC 2027",
                        BigDecimal.ONE, new BigDecimal("1500.00"))),
                "20270001",
                VALID_SK_IBAN,
                "TATRSKBX",
                "Zmluva o reklame 3/2027",
                "FGC-2027",
                null);
    }

    /** Ciastocny dobropis k fakture platitela DPH - vratenie montaze. */
    public static CreditNote partialCreditNote() {
        Invoice original = vatPayerMixedRates();
        Invoice body = new Invoice("D2027-0001", LocalDate.of(2027, 2, 10), LocalDate.of(2027, 2, 10),
                LocalDate.of(2027, 2, 24), "EUR", original.seller(), original.buyer(),
                List.of(original.lines().get(1)), original.variableSymbol(), original.payeeIban(), null,
                original.buyerReference(), "FGC-2027", null);
        return new CreditNote(body, original.number(), original.issueDate(), "Montáž nebola vykonaná.");
    }

    /** Platitel DPH so zmiesanymi sadzbami. */
    public static Invoice vatPayerMixedRates() {
        return new Invoice("FA2027-0042",
                LocalDate.of(2027, 2, 1),
                LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 2, 15),
                "EUR",
                vatPayerCompany(),
                new Party("Odberateľ a.s.", "Nová 10", "Žilina", "01001", "SK",
                        "11223344", "2021111111", "SK2021111111", null, null),
                List.of(
                        InvoiceLine.standard("Hliníkové profily", new BigDecimal("3"), new BigDecimal("19.99"), 23),
                        InvoiceLine.standard("Montáž", new BigDecimal("2.5"), new BigDecimal("30.00"), 23),
                        InvoiceLine.standard("Odborná kniha", BigDecimal.ONE, new BigDecimal("24.90"), 5)),
                "20270042",
                VALID_SK_IBAN,
                null,
                "OBJ-778",
                null,
                "Ďakujeme za spoluprácu.");
    }
}
