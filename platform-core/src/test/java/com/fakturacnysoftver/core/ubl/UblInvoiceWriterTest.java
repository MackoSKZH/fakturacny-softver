package com.fakturacnysoftver.core.ubl;

import com.fakturacnysoftver.core.TestInvoices;

import com.helger.phive.api.execute.ValidationExecutionManager;
import com.helger.phive.api.executorset.IValidationExecutorSet;
import com.helger.phive.api.executorset.ValidationExecutorSetRegistry;
import com.helger.phive.api.result.ValidationResultList;
import com.helger.phive.api.validity.IValidityDeterminator;
import com.helger.phive.peppol.PeppolValidation;
import com.helger.phive.peppol.PeppolValidation2026_05;
import com.helger.phive.xml.source.IValidationSourceXML;
import com.helger.phive.xml.source.ValidationSourceXML;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vygenerovane e-faktury musia prejst oficialnymi pravidlami OpenPeppol BIS Billing 3.0
 * (XSD + EN 16931 Schematron + Peppol Schematron) - rovnakymi, ake pouziva digitalny postar.
 */
class UblInvoiceWriterTest {
    private static IValidationExecutorSet<IValidationSourceXML> peppolInvoiceRules;

    @BeforeAll
    static void loadRules() {
        ValidationExecutorSetRegistry<IValidationSourceXML> registry = new ValidationExecutorSetRegistry<>();
        PeppolValidation.initStandard(registry);
        peppolInvoiceRules = registry.getOfID(PeppolValidation2026_05.VID_OPENPEPPOL_INVOICE_UBL_V3);
    }

    @Test
    void civicAssociationInvoicePassesPeppolValidation() throws Exception {
        String xml = UblInvoiceWriter.write(TestInvoices.charitableAdvertisingInvoice());

        assertPeppolValid(xml);
        assertTrue(xml.contains("<cbc:EndpointID schemeID=\"0245\">2120000000</cbc:EndpointID>"));
        assertTrue(xml.contains("<cbc:ID>O</cbc:ID>"));
        assertFalse(xml.contains("SK2020000000"), "BR-O-02: pri neplatiteľovi nesmie byť IČ DPH odberateľa");
        assertTrue(xml.contains("<cac:ProjectReference>"), "kód projektu ide do BT-11");
    }

    @Test
    void vatPayerInvoiceWithMixedRatesPassesPeppolValidation() throws Exception {
        String xml = UblInvoiceWriter.write(TestInvoices.vatPayerMixedRates());

        assertPeppolValid(xml);
        assertTrue(xml.contains("<cbc:Percent>23</cbc:Percent>"));
        assertTrue(xml.contains("<cbc:Percent>5</cbc:Percent>"));
        assertTrue(xml.contains("<cbc:PayableAmount currencyID=\"EUR\">192.16</cbc:PayableAmount>"));
    }

    @Test
    void validatorReallyRejectsBrokenInvoices() throws Exception {
        // Kontrola testu samotneho: bez tohto by zelene testy mohli znamenat, ze validacia nebezi.
        String xml = UblInvoiceWriter.write(TestInvoices.vatPayerMixedRates())
                .replace("<cbc:TaxAmount currencyID=\"EUR\">31.04</cbc:TaxAmount>",
                        "<cbc:TaxAmount currencyID=\"EUR\">31.00</cbc:TaxAmount>");

        List<String> problems = validate(xml);
        assertTrue(problems.stream().anyMatch(p -> p.contains("BR-CO-14") || p.contains("BR-CO-17")),
                () -> "Očakávali sme chybu súčtu DPH, dostali sme: " + problems);
    }

    private static void assertPeppolValid(String xml) throws Exception {
        List<String> problems = validate(xml);
        // Varovania netolerujeme - digitalny postar ich moze v buducnosti povysit na chyby.
        assertTrue(problems.isEmpty(), () -> "Peppol validácia zlyhala:\n"
                + String.join("\n", problems) + "\n\n" + xml);
    }

    private static List<String> validate(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        Document dom = dbf.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        ValidationResultList results = ValidationExecutionManager.executeValidation(
                IValidityDeterminator.createDefault(), peppolInvoiceRules,
                ValidationSourceXML.create("invoice.xml", dom));

        List<String> problems = new ArrayList<>();
        results.forEachFlattened(e -> problems.add(e.getAsString(Locale.ROOT)));
        return problems;
    }
}
