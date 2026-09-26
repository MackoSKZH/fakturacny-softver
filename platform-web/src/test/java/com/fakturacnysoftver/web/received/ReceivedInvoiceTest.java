package com.fakturacnysoftver.web.received;

import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.TestData;
import com.fakturacnysoftver.web.bank.BankImportService;
import com.fakturacnysoftver.web.bank.Camt053Parser;
import com.fakturacnysoftver.web.customer.CustomerRepository;
import com.fakturacnysoftver.web.invoice.InvoiceService;
import com.fakturacnysoftver.web.ledger.LedgerEntry;
import com.fakturacnysoftver.web.ledger.LedgerException;
import com.fakturacnysoftver.web.ledger.LedgerInput;
import com.fakturacnysoftver.web.ledger.LedgerRepository;
import com.fakturacnysoftver.web.ledger.LedgerService;
import com.fakturacnysoftver.web.organization.OrganizationRepository;
import com.fakturacnysoftver.web.project.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReceivedInvoiceTest extends IntegrationTest {
    @Autowired
    ReceivedInvoiceService service;
    @Autowired
    ReceivedInvoiceRepository repo;
    @Autowired
    LedgerService ledger;
    @Autowired
    LedgerRepository ledgerRepo;
    @Autowired
    InvoiceService invoices;
    @Autowired
    BankImportService bank;
    @Autowired
    OrganizationRepository organizations;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProjectRepository projects;
    @Autowired
    MockMvc mvc;

    TestData.Ids ids;

    @BeforeEach
    void setUp() {
        this.ids = TestData.setUp(this.organizations, this.customers, this.projects);
    }

    /** Minimalna e-faktura Peppol BIS 3.0 od dodavatela pre nase zdruzenie (IČO 12345678). */
    static String ubl(String type, String number, String buyerIco, String payable, String vs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <%1$s xmlns="urn:oasis:names:specification:ubl:schema:xsd:%1$s-2"
                  xmlns:cac="urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2"
                  xmlns:cbc="urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2">
                  <cbc:CustomizationID>urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0</cbc:CustomizationID>
                  <cbc:ID>%2$s</cbc:ID>
                  <cbc:IssueDate>2027-01-10</cbc:IssueDate>
                  <cbc:DueDate>2027-01-24</cbc:DueDate>
                  <cbc:DocumentCurrencyCode>EUR</cbc:DocumentCurrencyCode>
                  <cac:AccountingSupplierParty><cac:Party>
                    <cac:PartyName><cbc:Name>Robo Diely</cbc:Name></cac:PartyName>
                    <cac:PartyTaxScheme><cbc:CompanyID>SK2020123456</cbc:CompanyID><cac:TaxScheme><cbc:ID>VAT</cbc:ID></cac:TaxScheme></cac:PartyTaxScheme>
                    <cac:PartyLegalEntity><cbc:RegistrationName>Robo Diely s.r.o.</cbc:RegistrationName><cbc:CompanyID>44556677</cbc:CompanyID></cac:PartyLegalEntity>
                  </cac:Party></cac:AccountingSupplierParty>
                  <cac:AccountingCustomerParty><cac:Party>
                    <cac:PartyLegalEntity><cbc:RegistrationName>Robotické združenie</cbc:RegistrationName><cbc:CompanyID>%3$s</cbc:CompanyID></cac:PartyLegalEntity>
                  </cac:Party></cac:AccountingCustomerParty>
                  <cac:PaymentMeans><cbc:PaymentMeansCode>58</cbc:PaymentMeansCode><cbc:PaymentID>%5$s</cbc:PaymentID>
                    <cac:PayeeFinancialAccount><cbc:ID>SK89 7500 0000 0000 1234 5671</cbc:ID></cac:PayeeFinancialAccount></cac:PaymentMeans>
                  <cac:TaxTotal><cbc:TaxAmount currencyID="EUR">46.00</cbc:TaxAmount></cac:TaxTotal>
                  <cac:LegalMonetaryTotal><cbc:TaxExclusiveAmount currencyID="EUR">200.00</cbc:TaxExclusiveAmount>
                    <cbc:PayableAmount currencyID="EUR">%4$s</cbc:PayableAmount></cac:LegalMonetaryTotal>
                </%1$s>""".formatted(type, number, buyerIco, payable, vs);
    }

    @Test
    void importsEInvoiceAndPaymentLocksTheLedgerEntry() {
        long id = this.service.importUbl(ubl("Invoice", "FA-2027-17", "12345678", "246.00", "202717")
                .getBytes(StandardCharsets.UTF_8), this.ids.projectId(), "pokladnik");
        ReceivedInvoiceRepository.ReceivedInvoice r = this.repo.find(id).orElseThrow();
        assertEquals("Robo Diely s.r.o.", r.supplierName());
        assertEquals("44556677", r.supplierIco());
        assertEquals("SK2020123456", r.supplierDic());
        assertEquals("SK8975000000000012345671", r.supplierIban());
        assertEquals(new BigDecimal("246.00"), r.totalPayable());
        assertEquals(new BigDecimal("46.00"), r.totalVat());
        assertEquals(LocalDate.of(2027, 1, 24), r.dueDate());
        assertTrue(r.isEInvoice() && this.repo.xml(id).contains("FA-2027-17"), "XML sa archivuje");
        assertTrue(r.isOverdue(LocalDate.of(2027, 1, 25)));

        assertThrows(ReceivedInvoiceException.class, () -> this.service.importUbl(
                ubl("Invoice", "FA-2027-17", "12345678", "246.00", "202717").getBytes(StandardCharsets.UTF_8), null, "x"),
                "ten istý súbor");
        ReceivedInvoiceException twice = assertThrows(ReceivedInvoiceException.class, () -> this.service.importUbl(
                (ubl("Invoice", "FA-2027-17", "12345678", "246.00", "202717") + " ").getBytes(StandardCharsets.UTF_8), null, "x"));
        assertTrue(twice.getMessage().contains("už v knihe máte"), "rovnaké číslo od toho istého dodávateľa");

        this.service.markPaid(id, LocalDate.of(2027, 1, 14), "pokladnik");
        LedgerEntry e = this.ledgerRepo.findByReceivedInvoice(id).orElseThrow();
        assertEquals("VYDAVOK", e.direction());
        assertEquals(new BigDecimal("246.00"), e.amount());
        assertEquals(this.ids.projectId(), e.projectId(), "aktivita z faktúry");
        LedgerException locked = assertThrows(LedgerException.class, () -> this.ledger.update(e.id(), new LedgerInput(
                "2027-01-14", e.description(), "VYDAVOK", "1", null, List.of(), null, null, null, "BANKA", null, e.version()), "x"));
        assertTrue(locked.errors().get(0).contains("úhrada došlej faktúry FA-2027-17"), locked.errors().toString());
        assertThrows(LedgerException.class, () -> this.ledger.delete(e.id(), e.version(), "x"));
        assertThrows(ReceivedInvoiceException.class, () -> this.service.delete(id, "x"), "uhradenú nemazať");

        this.service.markPaid(id, null, "pokladnik");
        assertTrue(this.ledgerRepo.findByReceivedInvoice(id).isEmpty(), "zrušená úhrada odstráni výdavok");
    }

    @Test
    void rejectsInvoicesForSomeoneElseAndUnsafeXml() {
        long ours = this.invoices.issue(TestData.advertisingDraft(this.ids), "pokladnik");
        String issuedToSponsor = this.jdbc.sql("SELECT ubl_xml FROM invoice WHERE id = :i").param("i", ours)
                .query(String.class).single();
        ReceivedInvoiceException foreign = assertThrows(ReceivedInvoiceException.class, () -> this.service.importUbl(
                issuedToSponsor.getBytes(StandardCharsets.UTF_8), null, "x"));
        assertTrue(foreign.getMessage().contains("IČO 87654321"), "naša vlastná vydaná faktúra nie je došlá: " + foreign.getMessage());

        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE Invoice [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><Invoice>&x;</Invoice>";
        assertThrows(ReceivedInvoiceException.class, () -> this.service.importUbl(xxe.getBytes(StandardCharsets.UTF_8), null, "x"));
        assertThrows(ReceivedInvoiceException.class, () -> this.service.importUbl("<Order/>".getBytes(), null, "x"));

        long credit = this.service.importUbl(ubl("CreditNote", "DB-1", "12345678", "20.00", "")
                .getBytes(StandardCharsets.UTF_8), null, "x");
        assertTrue(this.repo.find(credit).orElseThrow().isCreditNote());
    }

    @Test
    void manualInvoicesValidateAndBankPaymentsMatchByVsOrIban() {
        ReceivedInvoiceService.ValidationErrors e = assertThrows(ReceivedInvoiceService.ValidationErrors.class,
                () -> this.service.createManual(new ReceivedInvoiceService.ManualInput("FAKTURA", " ", " ", "12",
                        null, "2027-02-01", "2027-01-01", "abc", null, null, null, null), "x"));
        assertTrue(e.errors().containsAll(List.of("Číslo faktúry dodávateľa je povinné.", "Dodávateľ je povinný.",
                "IČO má 6 až 8 číslic.", "Dátum vyhotovenia nemôže byť v budúcnosti.", "Suma na úhradu musí byť číslo.")),
                e.errors().toString());

        long byVs = this.service.createManual(new ReceivedInvoiceService.ManualInput("FAKTURA", "2027/05", "Tlačiareň",
                null, null, "2027-01-05", "2027-01-20", "120,00", "0020270005", this.ids.projectId(), "Tlač", null), "x");
        long byIban = this.service.createManual(new ReceivedInvoiceService.ManualInput("FAKTURA", "H-88", "Hotel Poprad",
                null, "SK05 1100 0000 0026 0000 0099", "2027-01-06", null, "480", null, null, "Ubytovanie", null), "x");

        String stmt = """
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt>
                <Ntry><Amt Ccy="EUR">120.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts><BookgDt><Dt>2027-01-12</Dt></BookgDt>
                  <AcctSvcrRef>P1</AcctSvcrRef><NtryDtls><TxDtls><Refs><EndToEndId>/VS20270005</EndToEndId></Refs>
                  <RltdPties><Cdtr><Nm>Tlačiareň</Nm></Cdtr></RltdPties></TxDtls></NtryDtls></Ntry>
                <Ntry><Amt Ccy="EUR">480.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts><BookgDt><Dt>2027-01-13</Dt></BookgDt>
                  <AcctSvcrRef>P2</AcctSvcrRef><NtryDtls><TxDtls><RltdPties><Cdtr><Nm>Hotel</Nm></Cdtr>
                  <CdtrAcct><Id><IBAN>SK0511000000002600000099</IBAN></Id></CdtrAcct></RltdPties></TxDtls></NtryDtls></Ntry>
                </Stmt></BkToCstmrStmt></Document>""";
        BankImportService.Preview p = this.bank.preview(Camt053Parser.parse(stmt.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(BankImportService.Action.DOSLA, BankImportService.Action.DOSLA),
                p.proposals().stream().map(BankImportService.Proposal::action).toList());
        assertTrue(p.proposals().get(1).reason().contains("IBAN a suma"));
        this.bank.apply(p, List.of(new BankImportService.Decision(0, BankImportService.Action.DOSLA, null),
                new BankImportService.Decision(1, BankImportService.Action.DOSLA, null)), "x");
        assertEquals(LocalDate.of(2027, 1, 12), this.repo.find(byVs).orElseThrow().paidOn());
        assertEquals(LocalDate.of(2027, 1, 13), this.repo.find(byIban).orElseThrow().paidOn());
        assertEquals("P1", this.jdbc.sql("SELECT bank_ref FROM ledger_entry WHERE received_invoice_id = :i")
                .param("i", byVs).query(String.class).single());
    }

    @Test
    void financeRolesUseThePages() throws Exception {
        grant("financie@fgs.example", "FINANCIE");
        grant("rada@fgs.example", "VEDENIE");
        grant("mentor@fgs.example", "MENTOR");
        var fin = user("financie@fgs.example").roles("USER");
        this.mvc.perform(multipart("/prijate-faktury/xml").file(new MockMultipartFile("file", "fa.xml", "text/xml",
                        ubl("Invoice", "W-1", "12345678", "246.00", "1").getBytes(StandardCharsets.UTF_8)))
                        .with(fin).with(csrf()))
                .andExpect(flash().attribute("message", "E-faktúra je zapísaná v knihe došlých faktúr."));
        long id = this.repo.findAll().get(0).id();
        this.mvc.perform(get("/prijate-faktury/" + id).with(fin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Robo Diely s.r.o.")));
        this.mvc.perform(get("/prijate-faktury/" + id + "/xml").with(fin)).andExpect(status().isOk());
        this.mvc.perform(get("/prijate-faktury").with(user("rada@fgs.example").roles("USER"))).andExpect(status().isOk())
                .andExpect(content().string(containsString("Robo Diely")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Nahrať e-faktúru"))));
        this.mvc.perform(multipart("/prijate-faktury/xml").file(new MockMultipartFile("file", "fa.xml", "text/xml",
                new byte[]{1})).with(user("rada@fgs.example").roles("USER")).with(csrf())).andExpect(status().isForbidden());
        this.mvc.perform(get("/prijate-faktury").with(user("mentor@fgs.example").roles("USER"))).andExpect(status().isForbidden());
        this.mvc.perform(get("/exporty/dosle-faktury.xlsx").with(fin)).andExpect(status().isOk());
    }
}
