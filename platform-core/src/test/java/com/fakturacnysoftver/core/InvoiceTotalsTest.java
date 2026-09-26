package com.fakturacnysoftver.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InvoiceTotalsTest {

    @Test
    void notSubjectToVatHasNoTax() {
        InvoiceTotals t = TestInvoices.charitableAdvertisingInvoice().totals();

        assertEquals(new BigDecimal("1500.00"), t.lineExtensionAmount());
        assertEquals(new BigDecimal("0.00"), t.vatAmount());
        assertEquals(new BigDecimal("1500.00"), t.payableAmount());
        assertEquals(1, t.vatBreakdown().size());
        assertEquals(VatCategory.NOT_SUBJECT, t.vatBreakdown().get(0).category());
    }

    @Test
    void vatIsComputedPerRateGroup() {
        InvoiceTotals t = TestInvoices.vatPayerMixedRates().totals();

        // 3 * 19.99 = 59.97; 2.5 * 30 = 75.00 -> zaklad 23 % = 134.97, DPH = 31.04
        // 24.90 pri 5 % -> DPH 1.245 -> 1.25
        assertEquals(new BigDecimal("159.87"), t.lineExtensionAmount());
        assertEquals(2, t.vatBreakdown().size());
        assertEquals(new BigDecimal("134.97"), t.vatBreakdown().get(0).taxableAmount());
        assertEquals(new BigDecimal("31.04"), t.vatBreakdown().get(0).taxAmount());
        assertEquals(new BigDecimal("1.25"), t.vatBreakdown().get(1).taxAmount());
        assertEquals(new BigDecimal("32.29"), t.vatAmount());
        assertEquals(new BigDecimal("192.16"), t.payableAmount());
    }

    @Test
    void perGroupRoundingDiffersFromPerLineRounding() {
        // Stara desktop verzia pocitala DPH po polozkach a scitavala - pri malych sumach
        // to dava iny vysledok nez EN 16931, ktora pocita DPH za celu skupinu sadzby.
        List<InvoiceLine> lines = List.of(
                InvoiceLine.standard("A", BigDecimal.ONE, new BigDecimal("0.33"), 23),
                InvoiceLine.standard("B", BigDecimal.ONE, new BigDecimal("0.33"), 23),
                InvoiceLine.standard("C", BigDecimal.ONE, new BigDecimal("0.33"), 23));
        Invoice inv = new Invoice("1", LocalDate.now(), LocalDate.now(), LocalDate.now(), "EUR",
                TestInvoices.vatPayerCompany(), TestInvoices.civicAssociation(), lines,
                null, null, null, null, null, null);

        // po polozkach: 3 * round(0.0759) = 3 * 0.08 = 0.24; za skupinu: round(0.99 * 0.23) = 0.23
        assertEquals(new BigDecimal("0.23"), inv.totals().vatAmount());
    }
}
