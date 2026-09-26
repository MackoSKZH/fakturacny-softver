package com.fakturacnysoftver.web.invoice;

import com.fakturacnysoftver.core.InvoiceLine;
import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.TestData;
import com.fakturacnysoftver.web.customer.CustomerRepository;
import com.fakturacnysoftver.web.organization.OrganizationRepository;
import com.fakturacnysoftver.web.project.ProjectRepository;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreditNoteTest extends IntegrationTest {
    @Autowired
    InvoiceService service;
    @Autowired
    InvoiceRepository invoices;
    @Autowired
    OrganizationRepository organizations;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProjectRepository projects;

    long invoiceId;

    @BeforeEach
    void setUp() {
        TestData.Ids ids = TestData.setUp(this.organizations, this.customers, this.projects);
        this.invoiceId = this.service.issue(TestData.advertisingDraft(ids), "pokladnik");
    }

    private static List<InvoiceLine> amount(String eur) {
        return List.of(InvoiceLine.notSubjectToVat("Charitatívna reklama - neuskutočnená časť", BigDecimal.ONE,
                new BigDecimal(eur)));
    }

    @Test
    void issuesCreditNoteWithOwnSeriesPdfAndEInvoice() throws Exception {
        long id = this.service.issueCreditNote(this.invoiceId, amount("500.00"), "Reklama len na polovicu sezóny.",
                null, "pokladnik");

        InvoiceSummary s = this.invoices.findSummary(id).orElseThrow();
        assertEquals("D20270001", s.number());
        assertTrue(s.isCreditNote());
        assertEquals("20270001", s.correctsNumber());
        assertEquals(new BigDecimal("500.00"), s.totalPayable());

        String ubl = this.invoices.findUbl(id).orElseThrow();
        assertTrue(ubl.contains("<CreditNote"), ubl);
        assertTrue(ubl.contains("<cbc:CreditNoteTypeCode>381</cbc:CreditNoteTypeCode>"));

        try (PDDocument pdf = Loader.loadPDF(this.invoices.findPdf(id).orElseThrow())) {
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Dobropis"), text);
            assertTrue(text.contains("Opravný doklad k faktúre č. 20270001 zo dňa 15. 1. 2027"), text);
            assertTrue(text.contains("Dôvod opravy: Reklama len na polovicu sezóny."), text);
            assertTrue(text.contains("Na vrátenie odberateľovi"), text);
            assertTrue(!text.contains("PAY by square"), "dobropis nemá QR na platbu dodávateľovi");
        }

        BigDecimal invoiced = this.projects.findAll().get(0).invoiced();
        assertEquals(new BigDecimal("1000.00"), invoiced, "rozpočet projektu: 1500 - 500");
    }

    @Test
    void creditNotesCannotExceedInvoiceTotal() {
        this.service.issueCreditNote(this.invoiceId, amount("1000"), "Prvá oprava", null, "pokladnik");

        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> this.service.issueCreditNote(this.invoiceId, amount("500.01"), "Druhá", null, "pokladnik"));
        assertTrue(e.errors().get(0).contains("prevyšuje zostatok"), e.errors().toString());

        long last = this.service.issueCreditNote(this.invoiceId, amount("500.00"), "Zvyšok", null, "pokladnik");
        assertEquals("D20270002", this.invoices.findSummary(last).orElseThrow().number(), "neúspešný pokus číslo nespotreboval");
    }

    @Test
    void concurrentCreditNotesStillRespectTheLimit() throws Exception {
        List<Future<Long>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> this.service.issueCreditNote(this.invoiceId, amount("600"), "Súbežná",
                        null, "pokladnik")));
            }
        }
        int ok = 0;
        for (Future<Long> f : results) {
            try {
                f.get();
                ok++;
            } catch (Exception e) {
                assertTrue(e.getCause() instanceof InvoiceValidationException, e.toString());
            }
        }
        assertEquals(2, ok, "1500 € faktúra unesie len dva dobropisy po 600 €");
        assertEquals(new BigDecimal("1200.00"), this.invoices.creditedTotal(this.invoiceId));
    }

    @Test
    void creditNoteNeedsReasonAndCannotCorrectCreditNote() {
        assertThrows(InvoiceValidationException.class,
                () -> this.service.issueCreditNote(this.invoiceId, amount("10"), " ", null, "pokladnik"));
        long cn = this.service.issueCreditNote(this.invoiceId, amount("10"), "Dôvod", null, "pokladnik");
        assertThrows(InvoiceValidationException.class,
                () -> this.service.issueCreditNote(cn, amount("1"), "Dôvod", null, "pokladnik"));
    }

    @Test
    void creditNoteIsImmutableToo() {
        long cn = this.service.issueCreditNote(this.invoiceId, amount("10"), "Dôvod", null, "pokladnik");

        assertThrows(DataAccessException.class, () -> this.jdbc
                .sql("UPDATE invoice SET correction_reason = 'iný' WHERE id = :id").param("id", cn).update());
        assertThrows(DataAccessException.class, () -> this.jdbc
                .sql("UPDATE invoice SET doc_type = 'FAKTURA', corrects_id = NULL WHERE id = :id").param("id", cn).update());
        assertEquals(1L, this.jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'DOBROPIS'")
                .query(Long.class).single());
    }
}
