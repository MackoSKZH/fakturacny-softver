package com.fakturacnysoftver.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceValidatorTest {

    @Test
    void sampleInvoicesAreValid() {
        assertTrue(InvoiceValidator.validateForPeppol(TestInvoices.charitableAdvertisingInvoice()).isEmpty());
        assertTrue(InvoiceValidator.validateForPeppol(TestInvoices.vatPayerMixedRates()).isEmpty());
    }

    @Test
    void nonVatPayerCannotChargeVat() {
        Invoice inv = withLines(TestInvoices.civicAssociation(),
                List.of(InvoiceLine.standard("Reklama", BigDecimal.ONE, BigDecimal.TEN, 23)));

        assertHasError(InvoiceValidator.validate(inv), "nesmie mať DPH");
    }

    @Test
    void vatPayerMustUseSlovakRate() {
        Invoice inv = withLines(TestInvoices.vatPayerCompany(),
                List.of(InvoiceLine.standard("Služba", BigDecimal.ONE, BigDecimal.TEN, 20)));

        assertHasError(InvoiceValidator.validate(inv), "23, 19 alebo 5");
    }

    @Test
    void buyerWithoutDicCannotReceiveEInvoice() {
        Party consumer = new Party("Ján Novák", "Lesná 2", "Nitra", "94901", "SK",
                null, null, null, null, null);
        Invoice base = TestInvoices.charitableAdvertisingInvoice();
        Invoice inv = new Invoice(base.number(), base.issueDate(), base.deliveryDate(), base.dueDate(),
                base.currency(), base.seller(), consumer, base.lines(), base.variableSymbol(),
                base.payeeIban(), base.payeeBic(), base.buyerReference(), base.projectCode(), base.note());

        assertTrue(InvoiceValidator.validate(inv).isEmpty(), "PDF faktúra fyzickej osobe je v poriadku");
        assertHasError(InvoiceValidator.validateForPeppol(inv), "použite PDF");
    }

    @Test
    void rejectsBrokenIdentifiersAndDates() {
        Invoice base = TestInvoices.charitableAdvertisingInvoice();
        Invoice inv = new Invoice("", base.issueDate(), null, base.issueDate().minusDays(1),
                base.currency(), base.seller(), base.buyer(), base.lines(), "12345678901",
                "SK0000000000000000000000", null, null, null, null);

        List<String> errors = InvoiceValidator.validate(inv);
        assertHasError(errors, "poradové číslo");
        assertHasError(errors, "dátum dodania");
        assertHasError(errors, "splatnosti je pred");
        assertHasError(errors, "Variabilný symbol");
        assertHasError(errors, "IBAN");
    }

    private static Invoice withLines(Party seller, List<InvoiceLine> lines) {
        return new Invoice("1", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 15),
                "EUR", seller, TestInvoices.vatPayerCompany(), lines, null, null, null, null, null, null);
    }

    private static void assertHasError(List<String> errors, String fragment) {
        assertTrue(errors.stream().anyMatch(e -> e.contains(fragment)),
                () -> "Očakávaná chyba obsahujúca '" + fragment + "', dostali sme: " + errors);
    }
}
