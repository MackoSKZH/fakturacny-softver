package sk.firstglobal.hq.web.invoice;

import sk.firstglobal.hq.core.Invoice;
import sk.firstglobal.hq.core.InvoiceLine;
import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.TestData;
import sk.firstglobal.hq.web.customer.CustomerRepository;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.project.ProjectRepository;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvoiceServiceTest extends IntegrationTest {
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

    TestData.Ids ids;

    @BeforeEach
    void setUp() {
        this.ids = TestData.setUp(this.organizations, this.customers, this.projects);
    }

    @Test
    void issuesInvoiceWithNumberPdfAndEInvoice() throws Exception {
        long id = this.service.issue(TestData.advertisingDraft(this.ids), "pokladnik");

        InvoiceSummary s = this.invoices.findSummary(id).orElseThrow();
        assertEquals("20270001", s.number());
        assertEquals(LocalDate.of(2027, 1, 15), s.issueDate());
        assertEquals(LocalDate.of(2027, 1, 29), s.dueDate(), "splatnosť podľa nastavení (14 dní)");
        assertEquals(new BigDecimal("1500.00"), s.totalPayable());
        assertEquals("FGC-2027", s.projectCode());
        assertTrue(s.hasUbl(), "sponzor má DIČ, takže e-faktúra sa dá doručiť");

        String ubl = this.invoices.findUbl(id).orElseThrow();
        assertTrue(ubl.contains("<cbc:ID>20270001</cbc:ID>"));
        assertTrue(ubl.contains("<cbc:PaymentID>20270001</cbc:PaymentID>"), "VS odvodený z čísla faktúry");

        String text = pdfText(this.invoices.findPdf(id).orElseThrow());
        assertTrue(text.contains("č. 20270001"), text);
        assertTrue(text.contains("Nie je platiteľ DPH"), text);
        assertTrue(text.contains("1 500,00 €"), text);
        assertTrue(text.contains("PAY by square"), text);
        assertTrue(text.contains("VVS/1-900/90-00000"), "registrácia OZ na doklade");
    }

    @Test
    void storedSnapshotRoundTrips() {
        long id = this.service.issue(TestData.advertisingDraft(this.ids), "pokladnik");

        Invoice loaded = this.service.load(id);

        assertEquals("20270001", loaded.number());
        assertEquals("Sponzor s.r.o.", loaded.buyer().name());
        assertEquals(new BigDecimal("1500.00"), loaded.totals().payableAmount());
        assertEquals(1, loaded.lines().size());
    }

    @Test
    void failedValidationDoesNotConsumeNumber() {
        InvoiceDraft broken = new InvoiceDraft(this.ids.customerId(), null, null, null, null, null, null, null,
                List.of(InvoiceLine.standard("S DPH, hoci OZ nie je platiteľ", BigDecimal.ONE, BigDecimal.TEN, 23)));

        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> this.service.issue(broken, "pokladnik"));
        assertTrue(e.errors().stream().anyMatch(m -> m.contains("nesmie mať DPH")), e.errors().toString());

        long id = this.service.issue(TestData.advertisingDraft(this.ids), "pokladnik");
        assertEquals("20270001", this.invoices.findSummary(id).orElseThrow().number(), "žiadna medzera v rade");
    }

    @Test
    void concurrentIssuingIsGaplessAndUnique() throws Exception {
        int count = 24;
        List<Future<Long>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> this.service.issue(TestData.advertisingDraft(this.ids), "pokladnik")));
            }
        }
        List<String> numbers = new ArrayList<>();
        for (Future<Long> f : futures) {
            numbers.add(this.invoices.findSummary(f.get()).orElseThrow().number());
        }
        List<String> expected = IntStream.rangeClosed(1, count).mapToObj(n -> String.format("2027%04d", n)).toList();
        assertEquals(expected, numbers.stream().sorted().toList());
    }

    @Test
    void issuedInvoiceCannotBeChangedOrDeleted() {
        long id = this.service.issue(TestData.advertisingDraft(this.ids), "pokladnik");

        assertThrows(DataAccessException.class, () -> this.jdbc
                .sql("UPDATE invoice SET total_payable = 1 WHERE id = :id").param("id", id).update());
        assertThrows(DataAccessException.class, () -> this.jdbc
                .sql("DELETE FROM invoice WHERE id = :id").param("id", id).update());

        this.service.markPaid(id, LocalDate.of(2027, 1, 15), "pokladnik");
        assertEquals(LocalDate.of(2027, 1, 15), this.invoices.findSummary(id).orElseThrow().paidOn());
    }

    @Test
    void auditLogIsAppendOnly() {
        this.service.issue(TestData.advertisingDraft(this.ids), "pokladnik");

        assertEquals(1L, this.jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'VYSTAVENIE'")
                .query(Long.class).single());
        assertThrows(DataAccessException.class, () -> this.jdbc.sql("DELETE FROM audit_log").update());
        assertThrows(DataAccessException.class, () -> this.jdbc.sql("UPDATE audit_log SET actor = 'x'").update());
    }

    @Test
    void refusesFutureAndBackdatedInvoices() {
        InvoiceDraft d = TestData.advertisingDraft(this.ids);
        InvoiceDraft future = new InvoiceDraft(d.customerId(), d.projectId(), LocalDate.of(2027, 2, 1), null, null,
                null, null, null, d.lines());
        assertThrows(InvoiceValidationException.class, () -> this.service.issue(future, "pokladnik"));

        this.service.issue(d, "pokladnik");
        InvoiceDraft backdated = new InvoiceDraft(d.customerId(), d.projectId(), LocalDate.of(2027, 1, 10), null,
                null, null, null, null, d.lines());
        InvoiceValidationException e = assertThrows(InvoiceValidationException.class,
                () -> this.service.issue(backdated, "pokladnik"));
        assertTrue(e.errors().get(0).contains("starší než posledná faktúra"), e.errors().toString());
    }

    @Test
    void buyerWithoutDicGetsPdfButNoEInvoice() {
        long personId = this.customers.insert(new sk.firstglobal.hq.web.customer.Customer(null, "Ján Novák",
                "Lesná 2", "Nitra", "94901", "SK", null, null, null, null, null));
        InvoiceDraft d = TestData.advertisingDraft(this.ids);
        long id = this.service.issue(new InvoiceDraft(personId, null, null, null, null, null, null, null, d.lines()),
                "pokladnik");

        assertTrue(this.invoices.findUbl(id).isEmpty());
        assertNotNull(this.invoices.findPdf(id).orElse(null));
        assertNull(this.invoices.findSummary(id).orElseThrow().projectCode());
    }

    @Test
    void longInvoiceContinuesOnNextPages() throws Exception {
        List<InvoiceLine> lines = IntStream.rangeClosed(1, 70)
                .mapToObj(i -> InvoiceLine.notSubjectToVat("Položka " + i + " s dlhším popisom, ktorý sa musí "
                        + "zalomiť do viacerých riadkov, aby sme overili stránkovanie tabuľky položiek",
                        BigDecimal.ONE, BigDecimal.TEN))
                .toList();
        InvoiceDraft d = TestData.advertisingDraft(this.ids);
        long id = this.service.issue(new InvoiceDraft(d.customerId(), d.projectId(), null, null, null, null, "X",
                "Poznámka s emoji 🤖 a tabulátorom\tnesmie zhodiť PDF.", lines), "pokladnik");

        try (PDDocument pdf = Loader.loadPDF(this.invoices.findPdf(id).orElseThrow())) {
            assertTrue(pdf.getNumberOfPages() >= 3, "strán: " + pdf.getNumberOfPages());
            String text = new PDFTextStripper().getText(pdf);
            assertTrue(text.contains("Položka 70"), "posledná položka nesmie chýbať");
            assertTrue(text.contains("700,00 €"));
            assertTrue(text.contains("Strana 1 / " + pdf.getNumberOfPages()));
        }
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
