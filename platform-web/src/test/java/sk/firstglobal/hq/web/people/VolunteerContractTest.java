package sk.firstglobal.hq.web.people;

import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.access.AccessRepository;
import sk.firstglobal.hq.web.access.AccessService;
import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.activity.StaffingRepository;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VolunteerContractTest extends IntegrationTest {
    @Autowired
    VolunteerContract contracts;
    @Autowired
    PersonRepository people;
    @Autowired
    ActivityRepository activities;
    @Autowired
    StaffingRepository staffing;
    @Autowired
    AccessService access;
    @Autowired
    AccessRepository accessRepo;
    @Autowired
    MockMvc mvc;

    long project;
    long other;
    long minor;
    long adult;

    @BeforeEach
    void setUp() {
        this.project = this.activities.create(new ActivityRepository.Fields("NK-2027", "Národné kolo", "NARODNE_KOLO",
                "PRIPRAVA", LocalDate.of(2027, 4, 24), LocalDate.of(2027, 4, 25), "Bratislava, STU", null, BigDecimal.ZERO));
        this.other = this.activities.create(new ActivityRepository.Fields("FGC-2027", "Cesta", "CESTA",
                "PRIPRAVA", LocalDate.of(2027, 9, 1), null, null, null, BigDecimal.ZERO));
        long judge = this.staffing.addRole(this.project, "Rozhodca", LocalDateTime.of(2027, 4, 24, 8, 0),
                LocalDateTime.of(2027, 4, 24, 13, 30), 2, "hodnotí zápasy");
        long declined = this.staffing.addRole(this.project, "Moderátor", LocalDateTime.of(2027, 4, 25, 9, 0), null, 1, null);
        this.minor = this.people.insert(new Person(null, "Jakub Mladý", "jakub@example.com", null, "Gymnázium",
                List.of("DOBROVOLNIK"), true, "Peter Mladý", "0900 123 456", LocalDate.of(2026, 9, 1), true, false, null));
        this.adult = this.people.insert(new Person(null, "Anna Dospelá", null, "0911 222 333", null,
                List.of("DOBROVOLNIK"), false, null, null, null, false, false, null));
        this.staffing.assign(judge, this.minor);
        this.staffing.assign(declined, this.minor);
        long seat = this.staffing.rolesOf(this.project).stream().filter(r -> r.id() == declined).findFirst().orElseThrow()
                .seats().getFirst().assignmentId();
        this.staffing.update(this.project, seat, "ODMIETOL", null);
        this.staffing.assign(judge, this.adult);
    }

    private static String text(byte[] pdf) throws Exception {
        try (var doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    @Test
    void contractForActivityListsShiftsAndGuardianForMinor() throws Exception {
        byte[] pdf = this.contracts.pdf(this.minor, this.project, null, null);
        Files.write(Path.of("build", "zmluva-test.pdf"), pdf);
        String t = text(pdf);
        assertTrue(t.contains("Zmluva o dobrovoľníckej činnosti"), t);
        assertTrue(t.contains("Jakub Mladý") && t.contains("Peter Mladý"), "maloletý a zástupca");
        assertTrue(t.contains("Rozhodca - hodnotí zápasy") && t.contains("8:00 - 13:30"), t);
        assertFalse(t.contains("Moderátor"), "odmietnutá smena nepatrí do zmluvy");
        assertTrue(t.contains("od 24.4.2027 do 25.4.2027"), t);
        assertTrue(t.contains("zákonný zástupca"), "podpis zástupcu");

        String adult = text(this.contracts.pdf(this.adult, null, null, null));
        assertTrue(adult.contains("do 31.12.2027") && adult.contains("Rozhodca"), adult);
        assertFalse(adult.contains("Zákonný zástupca maloletého"));
        String empty = text(this.contracts.pdf(this.adult, this.other, null, null));
        assertTrue(empty.contains("priebežne"), "bez smien všeobecný rozsah");
    }

    @Test
    void teamZipIsForTeamContactsOnly() throws Exception {
        grant("koord@fgs.example", "KOORDINATOR");
        this.mvc.perform(get("/aktivity/" + this.project + "/zmluvy.zip").with(user("koord@fgs.example").roles("USER")))
                .andExpect(status().isOk());
        this.mvc.perform(get("/ludia/" + this.minor + "/zmluva.pdf").param("aktivita", String.valueOf(this.project))
                .with(user("koord@fgs.example").roles("USER"))).andExpect(status().isOk());
        grant("mentor@fgs.example", "MENTOR");
        this.mvc.perform(get("/aktivity/" + this.project + "/zmluvy.zip").with(user("mentor@fgs.example").roles("USER")))
                .andExpect(status().isForbidden());
        this.mvc.perform(get("/ludia/" + this.minor + "/zmluva.pdf").with(user("mentor@fgs.example").roles("USER")))
                .andExpect(status().isForbidden());
        this.mvc.perform(get("/ludia/" + this.minor + "/zmluva.pdf").param("od", "2027-05-01").param("doDna", "2027-04-01")
                .with(user("koord@fgs.example").roles("USER"))).andExpect(status().isBadRequest());
    }
}
