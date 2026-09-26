package com.fakturacnysoftver.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentifiersAndNumberingTest {

    @Test
    void ibanChecksum() {
        assertTrue(Identifiers.isIban(TestInvoices.VALID_SK_IBAN));
        assertTrue(Identifiers.isIban("SK31 1200 0000 1987 4263 7541"));
        assertFalse(Identifiers.isIban("SK3112000000198742637542"), "zlá kontrolná číslica");
        assertFalse(Identifiers.isIban("SK311200000019874263754"), "SK IBAN má 24 znakov");
    }

    @Test
    void slovakIdentifiers() {
        assertTrue(Identifiers.isIco("12345678"));
        assertFalse(Identifiers.isIco("123123"));
        assertTrue(Identifiers.isDic("2120000000"));
        assertTrue(Identifiers.isSlovakIcDph("SK2020000000"));
        assertFalse(Identifiers.isSlovakIcDph("2020000000"));
        assertTrue(Identifiers.isVariableSymbol("20270001"));
        assertFalse(Identifiers.isVariableSymbol("FA-1"));
    }

    @Test
    void peppolIdUsesSlovakDicScheme() {
        assertEquals("0245:2120000000", TestInvoices.civicAssociation().peppolId());
    }

    @Test
    void numberingFormatsSequence() {
        assertEquals("20270001", InvoiceNumbering.format("{YYYY}{NNNN}", 2027, 1));
        assertEquals("FA27-042", InvoiceNumbering.format("FA{YY}-{NNN}", 2027, 42));
        assertEquals("20270001", InvoiceNumbering.variableSymbolOf("2027/0001"));
        assertNull(InvoiceNumbering.variableSymbolOf("2027-000000001"));
    }

    @Test
    void numberingRefusesOverflowInsteadOfDuplicating() {
        assertThrows(IllegalStateException.class, () -> InvoiceNumbering.format("{YYYY}{NNN}", 2027, 1000));
        assertThrows(IllegalArgumentException.class, () -> InvoiceNumbering.format("{YYYY}", 2027, 1));
    }
}
