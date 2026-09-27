package sk.firstglobal.hq.web.bank;

import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.TestData;
import sk.firstglobal.hq.web.customer.CustomerRepository;
import sk.firstglobal.hq.web.donation.DonationRepository;
import sk.firstglobal.hq.web.invoice.InvoiceService;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.project.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BankImportTest extends IntegrationTest {
    @Autowired
    BankImportService service;
    @Autowired
    InvoiceService invoices;
    @Autowired
    DonationRepository donations;
    @Autowired
    OrganizationRepository organizations;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProjectRepository projects;
    @Autowired
    MockMvc mvc;
    @Autowired
    sk.firstglobal.hq.web.partner.PartnerService partners;

    TestData.Ids ids;
    long invoiceId;

    @BeforeEach
    void setUp() {
        this.ids = TestData.setUp(this.organizations, this.customers, this.projects);
        this.invoiceId = this.invoices.issue(TestData.advertisingDraft(this.ids), "pokladnik");
    }

    private static String entry(String ref, String amount, String cdi, String status, String date, String name,
                                String iban, String e2e, String msg) {
        String party = "CRDT".equals(cdi) ? "Dbtr" : "Cdtr";
        return """
                <Ntry><NtryRef>%s</NtryRef><Amt Ccy="EUR">%s</Amt><CdtDbtInd>%s</CdtDbtInd>%s
                  <BookgDt><Dt>%s</Dt></BookgDt><AcctSvcrRef>%s</AcctSvcrRef>
                  <NtryDtls><TxDtls><Refs><EndToEndId>%s</EndToEndId></Refs>
                    <RltdPties><%s><Nm>%s</Nm></%s><%sAcct><Id><IBAN>%s</IBAN></Id></%sAcct></RltdPties>
                    <RmtInf><Ustrd>%s</Ustrd></RmtInf></TxDtls></NtryDtls></Ntry>
                """.formatted(ref, amount, cdi, status, date, ref, e2e, party, name, party, party, iban, party, msg);
    }

    private String statement() {
        String vsDonation = String.valueOf(DonationRepository.VS_BASE + this.ids.projectId());
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt>
                  <Acct><Id><IBAN>%s</IBAN></Id></Acct>
                """.formatted(TestData.IBAN)
                + entry("B1", "1500.00", "CRDT", "<Sts>BOOK</Sts>", "2027-01-12", "Sponzor s.r.o.", "SK8975000000000012345671",
                "/VS20270001/SS/KS0308", "Faktura 20270001")
                + entry("B2", "50.00", "CRDT", "<Sts><Cd>BOOK</Cd></Sts>", "2027-01-13", "Ján Darca", "SK0511000000002600000054",
                "/VS" + vsDonation, "Dar")
                + entry("B3", "700.00", "CRDT", "<Sts>BOOK</Sts>", "2027-01-13", "Iná firma", "SK0511000000002600000055",
                "NOTPROVIDED", "VS20270001 zaloha")
                + entry("B4", "89.90", "DBIT", "<Sts>BOOK</Sts>", "2027-01-14", "Alza.sk", "SK0511000000002600000056",
                "NOTPROVIDED", "Diely pre robota")
                + entry("B5", "10.00", "DBIT", "<Sts>PDNG</Sts>", "2027-01-15", "Čaká", "SK0511000000002600000057",
                "NOTPROVIDED", "cakajuca")
                + "</Stmt></BkToCstmrStmt></Document>";
    }

    private List<BankImportService.Action> actions(BankImportService.Preview p) {
        return p.proposals().stream().map(BankImportService.Proposal::action).toList();
    }

    @Test
    void matchesInvoiceAndDonationAndImportsOnlyOnce() {
        Camt053Parser.Statement st = Camt053Parser.parse(statement().getBytes(StandardCharsets.UTF_8));
        assertEquals(4, st.lines().size());
        assertEquals(1, st.skippedPending());
        assertEquals("20270001", st.lines().get(0).vs());
        assertEquals("0308", st.lines().get(0).ks());

        BankImportService.Preview p = this.service.preview(st);
        assertEquals(List.of(BankImportService.Action.FAKTURA, BankImportService.Action.DAR,
                BankImportService.Action.POLOZKA, BankImportService.Action.POLOZKA), actions(p));
        assertTrue(p.proposals().get(2).reason().contains("suma nesedí"), "čiastočná platba neuhradí faktúru");
        assertTrue(p.warnings().get(0).contains("nezaúčtovaných"));

        List<BankImportService.Decision> d = List.of(
                new BankImportService.Decision(0, BankImportService.Action.FAKTURA, null),
                new BankImportService.Decision(1, BankImportService.Action.DAR, null),
                new BankImportService.Decision(2, BankImportService.Action.POLOZKA, this.ids.projectId()),
                new BankImportService.Decision(3, BankImportService.Action.POLOZKA, this.ids.projectId()));
        BankImportService.Result r = this.service.apply(p, d, "pokladnik");
        assertEquals(new BankImportService.Result(1, 0, 1, 2, 0), r);

        assertEquals(LocalDate.of(2027, 1, 12), this.jdbc.sql("SELECT paid_on FROM invoice WHERE id = :i")
                .param("i", this.invoiceId).query(LocalDate.class).single());
        assertEquals(new BigDecimal("50.00"), this.donations.of(this.ids.projectId()).orElseThrow().collected());
        assertEquals(4, this.jdbc.sql("SELECT count(*) FROM ledger_entry WHERE bank_ref IS NOT NULL").query(Long.class).single());
        assertEquals("Alza.sk", this.jdbc.sql("SELECT counterparty FROM ledger_entry WHERE bank_ref = 'B4'")
                .query(String.class).single());

        BankImportService.Preview again = this.service.preview(Camt053Parser.parse(statement().getBytes(StandardCharsets.UTF_8)));
        assertTrue(actions(again).stream().allMatch(a -> a == BankImportService.Action.DUPLIKAT), actions(again).toString());
        assertEquals(new BankImportService.Result(0, 0, 0, 0, 4), this.service.apply(again, d, "pokladnik"),
                "ani keď niekto pošle staré rozhodnutia, nič sa nezdvojí");
    }

    @Test
    void rejectsXxeAndGarbage() {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE Document [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <Document><BkToCstmrStmt><Stmt><Ntry><Amt Ccy="EUR">&x;</Amt></Ntry></Stmt></BkToCstmrStmt></Document>""";
        assertThrows(BankImportException.class, () -> Camt053Parser.parse(xxe.getBytes(StandardCharsets.UTF_8)));
        assertThrows(BankImportException.class, () -> Camt053Parser.parse("Dátum;Suma\n1.1.2027;10".getBytes()));
        assertThrows(BankImportException.class, () -> Camt053Parser.parse("<Document/>".getBytes()));
    }

    @Test
    void financeWriterUsesPagesAndOthersCannot() throws Exception {
        grant("financie@fgs.example", "FINANCIE");
        grant("rada@fgs.example", "VEDENIE");
        var fin = user("financie@fgs.example").roles("USER");
        MockHttpSession session = new MockHttpSession();
        this.mvc.perform(multipart("/polozky/banka").file(new MockMultipartFile("file", "vypis.xml", "text/xml",
                        statement().getBytes(StandardCharsets.UTF_8))).session(session).with(fin).with(csrf()))
                .andExpect(redirectedUrl("/polozky/banka/nahlad"));
        this.mvc.perform(get("/polozky/banka/nahlad").session(session).with(fin)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Úhrada 20270001")))
                .andExpect(content().string(containsString("VS zbierky aktivity")));
        this.mvc.perform(post("/polozky/banka/potvrdit").session(session).with(fin).with(csrf())
                        .param("action_3", "PRESKOCIT"))
                .andExpect(redirectedUrl("/polozky"))
                .andExpect(flash().attribute("message", "Výpis je naimportovaný: uhradené faktúry (vydané aj došlé) 1, dary 1, nové položky 1, preskočené 1."));
        this.mvc.perform(get("/polozky").with(fin).flashAttr("message", "Výpis je naimportovaný: test"))
                .andExpect(content().string(containsString("Výpis je naimportovaný: test")));
        this.mvc.perform(multipart("/polozky/banka").file(new MockMultipartFile("file", "x.xml", "text/xml",
                        "nie je xml".getBytes())).session(session).with(fin).with(csrf()))
                .andExpect(redirectedUrl("/polozky/banka")).andExpect(flash().attributeExists("errors"));

        var rada = user("rada@fgs.example").roles("USER");
        this.mvc.perform(get("/polozky/banka").with(rada)).andExpect(status().isForbidden());
        this.mvc.perform(get("/polozky").with(rada)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Výpis z banky"))));
    }

    @Test
    void sponsorAndGrantPaymentsMatchDealVsAndSettleTheDeal() {
        long company = this.partners.createPartner(new sk.firstglobal.hq.web.partner.PartnerService.PartnerInput(
                "Tech s.r.o.", "FIRMA", null, null, null, null, null, null, null, null, null), "pokladnik");
        long foundation = this.partners.createPartner(new sk.firstglobal.hq.web.partner.PartnerService.PartnerInput(
                "Nadácia", "NADACIA", null, null, null, null, null, null, null, null, null), "pokladnik");
        long gift = this.partners.createDeal(new sk.firstglobal.hq.web.partner.PartnerService.DealInput(company,
                this.ids.projectId(), "Dar na robota", "DAR", "DOHODNUTE", "1000", null, null, null, null, null, null,
                null, null, null, null, null), "pokladnik");
        long grant = this.partners.createDeal(new sk.firstglobal.hq.web.partner.PartnerService.DealInput(foundation,
                null, "Grant cesta", "GRANT", "DOHODNUTE", "5000", null, null, null, "Veda", null, "2027-01-01",
                "2027-12-31", null, null, null, "0042017"), "pokladnik");
        assertEquals("42017", this.partners.deal(grant).paymentVs(), "vlastný VS bez úvodných núl");
        String giftVs = this.partners.deal(gift).paymentVs();
        assertEquals(String.valueOf(700000 + gift), giftVs);

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02"><BkToCstmrStmt><Stmt>
                  <Acct><Id><IBAN>%s</IBAN></Id></Acct>
                """.formatted(TestData.IBAN)
                + entry("D1", "600.00", "CRDT", "<Sts>BOOK</Sts>", "2027-01-12", "Tech s.r.o.", "SK0511000000002600000054",
                "/VS" + giftVs, "1. splatka")
                + entry("D2", "400.00", "CRDT", "<Sts>BOOK</Sts>", "2027-01-13", "Tech s.r.o.", "SK0511000000002600000054",
                "/VS" + giftVs, "2. splatka")
                + entry("D3", "2500.00", "CRDT", "<Sts>BOOK</Sts>", "2027-01-14", "Nadácia", "SK0511000000002600000055",
                "/VS0042017", "grant")
                + "</Stmt></BkToCstmrStmt></Document>";
        BankImportService.Preview p = this.service.preview(Camt053Parser.parse(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of(BankImportService.Action.DOHODA, BankImportService.Action.DOHODA, BankImportService.Action.DOHODA),
                actions(p));
        assertEquals(this.ids.projectId(), p.proposals().getFirst().projectId(), "aktivita podľa dohody");
        assertTrue(p.proposals().getFirst().reason().contains("Tech s.r.o. - Dar na robota"));

        BankImportService.Result r = this.service.apply(p, List.of(
                new BankImportService.Decision(0, BankImportService.Action.DOHODA, null),
                new BankImportService.Decision(1, BankImportService.Action.DOHODA, null),
                new BankImportService.Decision(2, BankImportService.Action.DOHODA, null)), "pokladnik");
        assertEquals(new BankImportService.Result(0, 3, 0, 0, 0), r);
        var g = this.partners.deal(gift);
        assertEquals(0, new BigDecimal("1000.00").compareTo(g.received()));
        assertEquals("ZAPLATENE", g.stage(), "plná suma prijatá = zaplatené");
        var gr = this.partners.deal(grant);
        assertEquals(0, new BigDecimal("2500.00").compareTo(gr.received()));
        assertEquals("DOHODNUTE", gr.stage(), "polovica grantu ešte nie je zaplatená");

        assertThrows(sk.firstglobal.hq.web.partner.PartnerException.class, () -> this.partners.createDeal(
                new sk.firstglobal.hq.web.partner.PartnerService.DealInput(company, null, "Iný", "DAR", "ROKUJEME", "1",
                        null, null, null, null, null, null, null, null, null, null, "42017"), "pokladnik"),
                "VS už má iná dohoda");
        assertThrows(sk.firstglobal.hq.web.partner.PartnerException.class, () -> this.partners.createDeal(
                new sk.firstglobal.hq.web.partner.PartnerService.DealInput(company, null, "Iný", "DAR", "ROKUJEME", "1",
                        null, null, null, null, null, null, null, null, null, null, "800123"), "pokladnik"),
                "vyhradený rozsah");
    }
}
