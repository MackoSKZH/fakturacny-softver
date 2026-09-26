package sk.firstglobal.hq.web.partner;

import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.TestData;
import sk.firstglobal.hq.web.customer.CustomerRepository;
import sk.firstglobal.hq.web.export.Table;
import sk.firstglobal.hq.web.ledger.LedgerEntry;
import sk.firstglobal.hq.web.ledger.LedgerInput;
import sk.firstglobal.hq.web.ledger.LedgerRepository;
import sk.firstglobal.hq.web.ledger.LedgerService;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.project.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PartnerTest extends IntegrationTest {
    @Autowired
    PartnerService service;
    @Autowired
    PartnerRepository repo;
    @Autowired
    LedgerService ledger;
    @Autowired
    LedgerRepository ledgerRepo;
    @Autowired
    OrganizationRepository organizations;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProjectRepository projects;
    @Autowired
    MockMvc mvc;

    TestData.Ids ids;
    long foundation;
    long company;

    @BeforeEach
    void setUp() {
        this.ids = TestData.setUp(this.organizations, this.customers, this.projects);
        this.foundation = this.service.createPartner(new PartnerService.PartnerInput("Nadácia Pre Vedu", "NADACIA", null,
                null, "Eva Grantová", "eva@nadacia.example", null, null, null, "granty", null), "pokladnik");
        this.company = this.service.createPartner(new PartnerService.PartnerInput("Tech s.r.o.", "FIRMA", "12345678",
                null, null, null, null, this.ids.customerId(), null, null, null), "pokladnik");
    }

    private PartnerService.DealInput deal(long partner, String kind, String stage, String amount, String from, String to,
                                          String reportDue) {
        return new PartnerService.DealInput(partner, this.ids.projectId(), kind + " test", kind, stage, amount, null, null,
                null, "Veda pre mladých", null, from, to, reportDue, null, null, null);
    }

    private LedgerEntry entry(String date, String direction, String amount) {
        return this.ledger.create(new LedgerInput(date, "Položka " + amount, direction, amount, this.ids.projectId(),
                List.of(), null, null, "D-" + amount, "BANKA", null, null), "pokladnik");
    }

    @Test
    void grantTracksSpendingPeriodAndSettlement() {
        PartnerException noPeriod = assertThrows(PartnerException.class, () -> this.service.createDeal(
                deal(this.foundation, "GRANT", "DOHODNUTE", "5000", null, null, null), "pokladnik"));
        assertTrue(noPeriod.errors().get(0).contains("oprávnené obdobie"), noPeriod.errors().toString());

        long grant = this.service.createDeal(deal(this.foundation, "GRANT", "DOHODNUTE", "5 000,00", "2027-01-01",
                "2027-06-30", "2027-02-01"), "pokladnik");
        LedgerEntry payment = entry("2027-01-10", "PRIJEM", "5000");
        LedgerEntry inPeriod = entry("2027-01-12", "VYDAVOK", "1200");
        LedgerEntry before = entry("2026-12-20", "VYDAVOK", "300");
        for (LedgerEntry e : List.of(payment, inPeriod, before)) {
            this.service.link(grant, e.id(), "pokladnik");
        }

        Deal d = this.service.deal(grant);
        assertEquals(new BigDecimal("5000.00"), d.received());
        assertEquals(new BigDecimal("1500.00"), d.spent());
        assertEquals(new BigDecimal("3500.00"), d.remainingToSpend());
        assertEquals(1, d.outOfPeriod());
        List<String> w = d.warnings(this.service.today());
        assertTrue(w.stream().anyMatch(x -> x.contains("mimo oprávneného obdobia")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.startsWith("Vyúčtovanie treba odovzdať do 1. 2. 2027")), w.toString());

        LedgerEntry after = this.ledgerRepo.findById(inPeriod.id()).orElseThrow();
        assertEquals(inPeriod.version() + 1, after.version(), "otvorená tabuľka položiek dostane konflikt, nie prepis");
        LedgerRepository.HistoryItem last = this.ledgerRepo.history(inPeriod.id()).getLast();
        assertEquals("UPDATE", last.op());
        assertEquals("pokladnik", last.actor(), "prepojenie je v histórii položky");

        Table t = this.service.settlement(grant);
        assertEquals(3, t.rows().size());
        assertEquals("NIE", t.rows().get(0).get(7), "výdavok z decembra je mimo obdobia");
        assertTrue(t.notes().get(0).contains("čerpané 1 500,00 €"), t.notes().toString());
        assertTrue(t.notes().stream().noneMatch(n -> n.contains("neuzná")), "interné upozornenia nejdú grantorovi");

        PartnerException linked = assertThrows(PartnerException.class, () -> this.service.deleteDeal(grant, "x"));
        assertTrue(linked.errors().get(0).contains("prepojené položky"));
        assertThrows(PartnerException.class, () -> this.service.updateDeal(grant,
                deal(this.foundation, "DAR", "DOHODNUTE", "5000", null, null, null), "x"), "grant s položkami nezmení typ");

        this.service.unlink(grant, before.id(), "pokladnik");
        assertEquals(0, this.service.deal(grant).outOfPeriod());
    }

    @Test
    void sponsorWarningsAndLinkRules() {
        long gift = this.service.createDeal(new PartnerService.DealInput(this.company, this.ids.projectId(), "Dar na robota",
                "DAR", "DOHODNUTE", "3000", "2027-01-10", "Poslať poďakovanie", "2027-01-12", null, null, null, null, null,
                null, null, null), "pokladnik");
        this.service.addDeliverable(gift, "Logo na robote", "2027-01-05");
        List<String> w = this.service.deal(gift).warnings(this.service.today());
        assertTrue(w.stream().anyMatch(x -> x.startsWith("Dar s protiplnením")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.startsWith("Platba mala prísť do 10. 1. 2027")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.startsWith("Ďalší krok po termíne")), w.toString());
        assertTrue(w.stream().anyMatch(x -> x.startsWith("1 protiplnenie")), w.toString());

        LedgerEntry expense = entry("2027-01-11", "VYDAVOK", "100");
        PartnerException e = assertThrows(PartnerException.class, () -> this.service.link(gift, expense.id(), "x"));
        assertTrue(e.errors().get(0).contains("Výdavky sa priraďujú ku grantu"));

        LedgerEntry income = entry("2027-01-14", "PRIJEM", "3000");
        this.service.link(gift, income.id(), "x");
        long other = this.service.createDeal(deal(this.company, "REKLAMA", "ROKUJEME", "1000", null, null, null), "x");
        assertThrows(PartnerException.class, () -> this.service.link(other, income.id(), "x"), "položka patrí inej dohode");
        assertTrue(this.service.deal(gift).warnings(this.service.today()).stream()
                .noneMatch(x -> x.startsWith("Platba mala prísť")), "po prijatí platby upozornenie zmizne");

        this.service.createDeal(deal(this.company, "INVESTICIA", "DOHODNUTE", "20000", null, null, null), "x");
        PartnerRepository.ActivityFunding f = this.repo.fundingByActivity().get(0);
        assertEquals(new BigDecimal("3000.00"), f.secured(), "investícia do startupu nekryje rozpočet združenia");
        assertEquals(new BigDecimal("1000.00"), f.negotiating());
        assertEquals(new BigDecimal("9000.00"), f.missing());
        assertEquals(25, f.securedPercent());

        Partner p = this.repo.findById(this.company).orElseThrow();
        assertEquals(new BigDecimal("3000.00"), p.received());
        assertTrue(p.isNeglected(this.service.today()), "žiadny zapísaný kontakt");
    }

    @Test
    void validatesPartnersAndNotes() {
        PartnerException e = assertThrows(PartnerException.class, () -> this.service.createPartner(
                new PartnerService.PartnerInput("nadácia pre vedu", "NIECO", "12", null, null, "zly-email", null, 999L,
                        null, null, null), "x"));
        assertTrue(e.errors().contains("Partner „nadácia pre vedu“ už existuje."), e.errors().toString());
        assertTrue(e.errors().contains("Neznámy typ partnera."));
        assertTrue(e.errors().contains("IČO má 6 až 8 číslic."));
        assertTrue(e.errors().contains("E-mail kontaktu nie je platný."));
        assertTrue(e.errors().contains("Odberateľ neexistuje."));
        assertThrows(PartnerException.class, () -> this.service.addNote(this.company, "2027-02-01", "Stretnutie", "x"),
                "komunikácia nie je z budúcnosti");
        this.service.addNote(this.company, null, "Telefonát s CEO", "pokladnik");
        assertEquals(this.service.today(), this.repo.findById(this.company).orElseThrow().lastContact());
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void editorWorksWithPartnerAndDealPages() throws Exception {
        this.mvc.perform(post("/partneri/" + this.company + "/komunikacia").with(csrf()).param("text", "Poslali sme ponuku"))
                .andExpect(status().is3xxRedirection());
        String url = this.mvc.perform(post("/financovanie").with(csrf()).param("partnerId", String.valueOf(this.company))
                        .param("title", "Hlavný partner").param("kind", "REKLAMA").param("stage", "ROKUJEME")
                        .param("amount", "2500").param("projectId", String.valueOf(this.ids.projectId()))
                        .param("back", "/partneri/" + this.company))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertTrue(url.matches("/financovanie/\\d+"), url);

        this.mvc.perform(get("/partneri/" + this.company)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Poslali sme ponuku")))
                .andExpect(content().string(containsString("Hlavný partner")));
        this.mvc.perform(get(url)).andExpect(status().isOk());
        this.mvc.perform(post(url).with(csrf()).param("title", "Hlavný partner").param("kind", "REKLAMA")
                        .param("stage", "DOHODNUTE").param("amount", "0"))
                .andExpect(redirectedUrl(url)).andExpect(flash().attributeExists("errors"));
        this.mvc.perform(post("/financovanie").with(csrf()).param("title", "Bez partnera").param("kind", "DAR")
                        .param("back", "https://zly.example"))
                .andExpect(redirectedUrl("/financovanie"));
        this.mvc.perform(get("/financovanie/99999")).andExpect(status().isNotFound());
        this.mvc.perform(get("/financovanie")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Krytie rozpočtu aktivít")));
        this.mvc.perform(get(url + "/vyuctovanie.pdf")).andExpect(status().isOk());
        this.mvc.perform(get("/partneri").param("typ", "FIRMA")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Tech s.r.o.")));
    }

    @Test
    @WithMockUser(value = "clen", roles = "USER")
    void membersSeeFundingButNotPartnerContacts() throws Exception {
        grant("clen", "VEDENIE");
        long d = this.service.createDeal(deal(this.foundation, "GRANT", "ROKUJEME", "5000", null, null, null), "x");
        this.mvc.perform(get("/financovanie")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Nadácia Pre Vedu")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("eva@nadacia.example"))));
        this.mvc.perform(get("/financovanie/" + d)).andExpect(status().isOk());
        this.mvc.perform(get("/partneri")).andExpect(status().isForbidden());
        this.mvc.perform(get("/partneri/" + this.foundation)).andExpect(status().isForbidden());
        this.mvc.perform(post("/financovanie/" + d + "/protiplnenia").with(csrf()).param("title", "x"))
                .andExpect(status().isForbidden());
    }
}
