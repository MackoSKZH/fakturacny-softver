package sk.firstglobal.hq.web.donation;

import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.TestData;
import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.activity.StaffingRepository;
import sk.firstglobal.hq.web.customer.CustomerRepository;
import sk.firstglobal.hq.web.ledger.LedgerInput;
import sk.firstglobal.hq.web.ledger.LedgerService;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.people.Person;
import sk.firstglobal.hq.web.people.PersonRepository;
import sk.firstglobal.hq.web.project.ProjectRepository;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verejna podpora aktivity a potvrdenia o dobrovolnickej cinnosti. */
class DonationTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    OrganizationRepository organizations;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProjectRepository projects;
    @Autowired
    ActivityRepository activities;
    @Autowired
    StaffingRepository staffing;
    @Autowired
    PersonRepository people;
    @Autowired
    LedgerService ledger;

    long project;
    String vs;
    final org.springframework.test.web.servlet.request.RequestPostProcessor editor = user("pokladnik").roles("USER", "EDITOR");

    @BeforeEach
    void setUp() {
        this.project = TestData.setUp(this.organizations, this.customers, this.projects).projectId();
        this.vs = String.valueOf(DonationRepository.VS_BASE + this.project);
        this.activities.update(this.project, new ActivityRepository.Fields(null, "FIRST Global Challenge 2027", "CESTA",
                "PRIPRAVA", LocalDate.of(2027, 4, 24), LocalDate.of(2027, 4, 24), "Singapur",
                "INTERNY POPIS - nezverejnovat", new BigDecimal("12000")));
    }

    @Test
    void publicPageIsOptInAndShowsOnlyPaymentData() throws Exception {
        this.mvc.perform(get("/podpora/FGC-2027").with(anonymous())).andExpect(status().isNotFound());

        this.mvc.perform(post("/aktivity/" + this.project + "/podpora").with(this.editor).with(csrf())
                .param("enabled", "true").param("goal", "-5")).andExpect(flash().attributeExists("errors"));
        this.mvc.perform(post("/aktivity/" + this.project + "/podpora").with(this.editor).with(csrf())
                        .param("enabled", "true").param("goal", "1 000").param("text", "Letenky pre 5 študentov."))
                .andExpect(flash().attribute("message", "Verejná stránka podpory je zapnutá."));
        this.ledger.create(new LedgerInput("2027-01-14", "Dar - Ján Darca", "PRIJEM", "50", this.project, List.of(),
                null, "Ján Darca", " " + this.vs + " ", "BANKA", null, null), "pokladnik");

        this.mvc.perform(get("/podpora/fgc-2027").with(anonymous()).param("suma", "20")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Letenky pre 5 študentov.")))
                .andExpect(content().string(containsString(this.vs)))
                .andExpect(content().string(containsString("SK31 1200 0000 1987 4263 7541")))
                .andExpect(content().string(containsString("<svg")))
                .andExpect(content().string(containsString("50 € z 1 000 €")))
                .andExpect(content().string(not(containsString("INTERNY POPIS"))))
                .andExpect(content().string(not(containsString("12 000"))));

        this.activities.update(this.project, new ActivityRepository.Fields(null, "FIRST Global Challenge 2027", "CESTA",
                "ZRUSENA", null, null, null, null, BigDecimal.ZERO));
        this.mvc.perform(get("/podpora/FGC-2027").with(anonymous())).andExpect(status().isNotFound());
    }

    @Test
    void volunteerConfirmationsForPersonSelfAndActivity() throws Exception {
        long eva = this.people.insert(new Person(null, "Eva Nováková", "eva@fgs.example", null, null, List.of("ROZHODCA"),
                false, null, null, LocalDate.of(2027, 1, 1), false, false, null));
        long role = this.staffing.addRole(this.project, "Rozhodca", LocalDateTime.of(2027, 1, 10, 8, 0), null, 2, null);
        this.staffing.assign(role, eva);
        long seat = this.staffing.rolesOf(this.project).get(0).seats().get(0).assignmentId();
        this.staffing.update(this.project, seat, "ZUCASTNIL_SA", new BigDecimal("6.5"));

        byte[] pdf = this.mvc.perform(get("/ludia/" + eva + "/potvrdenie.pdf").param("rok", "2027").with(this.editor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("Potvrdenie o výkone dobrovoľníckej činnosti"), text);
            assertTrue(text.contains("zákona č. 406/2011 Z. z."));
            assertTrue(text.contains("Dobrovoľník: Eva Nováková"));
            assertTrue(text.contains("v celkovom rozsahu 6,50 hodín"), text);
            assertTrue(text.contains("Organizácia: Robotické združenie, o. z., Hlavná 1, 81101 Bratislava, IČO 12345678"), text);
            assertTrue(text.contains("vykonával(a) bezodplatne"));
            assertTrue(doc.getPage(0).getMediaBox().getHeight() > doc.getPage(0).getMediaBox().getWidth(), "na výšku");
        }
        this.mvc.perform(get("/ludia/" + eva + "/potvrdenie.pdf").param("rok", "2026").with(this.editor))
                .andExpect(status().isNotFound());
        long future = this.staffing.addRole(this.project, "Technik", LocalDateTime.of(2027, 4, 24, 8, 0), null, 1, null);
        this.staffing.assign(future, eva);
        this.jdbc.sql("UPDATE assignment SET status = 'ZUCASTNIL_SA', hours = 10 WHERE role_id = :r").param("r", future).update();
        byte[] again = this.mvc.perform(get("/ludia/" + eva + "/potvrdenie.pdf").with(this.editor))
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(again)) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("6,50 hodín") && !text.contains("Technik"), "budúca akcia sa nepotvrdzuje: " + text);
        }

        var self = user("eva@fgs.example").roles("USER");
        this.mvc.perform(get("/moj-program").with(self)).andExpect(content().string(containsString("Rok 2027 (PDF)")));
        this.mvc.perform(get("/moj-program/potvrdenie.pdf").param("rok", "2027").with(self)).andExpect(status().isOk());
        this.mvc.perform(get("/aktivity/" + this.project + "/potvrdenia.zip").with(self)).andExpect(status().isForbidden());

        byte[] zip = this.mvc.perform(get("/aktivity/" + this.project + "/potvrdenia.zip").with(this.editor))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            assertEquals("potvrdenie-Eva-Novakova-" + eva + ".pdf", z.getNextEntry().getName());
        }
    }
}
