package sk.firstglobal.hq.web.people;

import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.access.AccessException;
import sk.firstglobal.hq.web.access.AccessInfo;
import sk.firstglobal.hq.web.access.AccessRepository;
import sk.firstglobal.hq.web.access.AccessService;
import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.activity.StaffingRepository;
import sk.firstglobal.hq.web.asset.AssetService;
import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.timesheet.TimesheetService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonPrivacyTest extends IntegrationTest {
    @Autowired
    PersonPrivacy privacy;
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
    AuditLog audit;
    @Autowired
    AssetService assets;
    @Autowired
    TimesheetService timesheets;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    MockMvc mvc;

    long eva;
    long past;
    long future;

    @BeforeEach
    void setUp() {
        long project = this.activities.create(new ActivityRepository.Fields("NK-2027", "Národné kolo", "NARODNE_KOLO",
                "PRIPRAVA", LocalDate.of(2027, 4, 24), null, null, null, BigDecimal.ZERO));
        this.past = this.staffing.addRole(project, "Registrácia", LocalDateTime.of(2027, 1, 10, 8, 0), null, 2, null);
        this.future = this.staffing.addRole(project, "Rozhodca", LocalDateTime.of(2027, 4, 24, 8, 0), null, 2, null);
        this.eva = this.people.insert(new Person(null, "Eva Kováčová", "eva.kovacova@example.com", "+421900111222", "Gymnázium",
                List.of("DOBROVOLNIK"), true, "Mária Kováčová", "maria@example.com", LocalDate.of(2026, 9, 1), true, true,
                "alergia na orechy"));
        this.staffing.assign(this.past, this.eva);
        this.staffing.assign(this.future, this.eva);
        this.audit.record("koord@fgs.example", "VYTVORENIE", "osoba", this.eva, "Eva Kováčová");
        this.audit.record("koord@fgs.example", "ZMENA", "aktivita", project, "pridaná eva.kovacova@example.com na smenu");
        this.access.createUser("eva.kovacova@example.com", "Eva", List.of(this.accessRepo.roleByCode("DOBROVOLNIK")
                .orElseThrow().id()), "pokladnik");
        this.audit.record("eva.kovacova@example.com", "ZMENA", "program", 1, "vlastná zmena");
    }

    private AccessInfo admin() {
        return this.access.resolve("pokladnik", null).access();
    }

    @Test
    void exportListsEverythingAndAnonymizationRemovesPersonalDataEverywhere() throws Exception {
        String csv = new String(this.privacy.export(this.eva).bytes(sk.firstglobal.hq.web.export.Table.Format.CSV),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("Mária Kováčová") && csv.contains("Registrácia") && csv.contains("Prihlasovací e-mail"));

        PersonPrivacy.Assessment a = this.privacy.assess(this.eva);
        assertTrue(a.possible());
        assertFalse(a.nameRetained());
        assertTrue(a.effects().stream().anyMatch(e -> e.contains("1 budúcich")));

        assertThrows(AccessException.class, () -> this.privacy.anonymize(this.eva, "Eva", admin()), "potvrdenie menom");
        AccessInfo koord = new AccessInfo(1L, "koord@fgs.example",
                java.util.Set.of(sk.firstglobal.hq.web.access.Permission.PEOPLE), java.util.Set.of(), false);
        assertThrows(AccessException.class, () -> this.privacy.anonymize(this.eva, "Eva Kováčová", koord), "len admin");

        this.privacy.anonymize(this.eva, " eva kováčová ", admin());

        Person p = this.people.findById(this.eva).orElseThrow();
        assertEquals("Anonymizovaná osoba " + this.eva, p.fullName());
        assertNull(p.email());
        assertNull(p.phone());
        assertNull(p.guardianContact());
        assertNull(p.note());
        assertFalse(p.photoConsent());
        assertTrue(this.people.findAll().stream().noneMatch(x -> x.id() == this.eva), "zmizne z adresára");
        assertEquals(List.of("Registrácia"), this.people.participation(this.eva).stream()
                .map(PersonRepository.Participation::roleName).toList(), "minulá účasť ostane, budúca nie");

        assertTrue(this.accessRepo.userByEmail("eva.kovacova@example.com").isEmpty());
        assertFalse(this.access.mayLogIn("eva.kovacova@example.com"));

        String log = String.join("\n", this.jdbc.sql("SELECT actor || ' ' || COALESCE(detail, '') FROM audit_log")
                .query(String.class).list());
        assertFalse(log.toLowerCase().contains("kováčová") || log.contains("eva.kovacova@"), log);
        assertTrue(log.contains("pridaná [anonymizované] na smenu"));
        assertTrue(log.contains("anonymizovaný používateľ #"));
        assertTrue(log.contains("ANONYMIZACIA") || this.audit.search(new AuditLog.Filter(null, "osoba", "ANONYMIZACIA",
                null, null, null), null, 5).size() == 1);

        assertThrows(Exception.class, () -> this.jdbc.sql("UPDATE audit_log SET detail = 'x'").update(),
                "mimo anonymizácie ostáva audit len na zápis");
        assertThrows(Exception.class, () -> this.jdbc.sql("DELETE FROM audit_log").update());
        assertThrows(AccessException.class, () -> this.privacy.anonymize(this.eva, p.fullName(), admin()));
    }

    @Test
    void payrollRecordsKeepTheNameAndOpenLoansBlock() {
        Person e = this.people.findById(this.eva).orElseThrow();
        this.people.update(this.eva, new Person(this.eva, e.fullName(), e.email(), e.phone(), e.organization(),
                List.of("DOBROVOLNIK", "PLATENY"), false, null, null, e.dataConsentOn(), false, false, null));
        this.timesheets.createContract(this.eva, "DOBPS", "Technik", "stavba arény", "8", LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 3, 31), null, null, "pokladnik");
        long asset = this.assets.create(new AssetService.AssetInput("R-1", "Robot", "ROBOTIKA", null, "2027-01-10", "300",
                null, null, null, null, null), "pokladnik");
        this.assets.lend(asset, this.eva, null, null, null, "pokladnik");

        PersonPrivacy.Assessment a = this.privacy.assess(this.eva);
        assertFalse(a.possible());
        assertTrue(a.blockers().getFirst().contains("požičaný"));
        assertThrows(AccessException.class, () -> this.privacy.anonymize(this.eva, "Eva Kováčová", admin()));

        this.assets.giveBack(asset, "2027-01-15", "pokladnik");
        assertTrue(this.privacy.assess(this.eva).nameRetained());
        this.privacy.anonymize(this.eva, "Eva Kováčová", admin());
        Person p = this.people.findById(this.eva).orElseThrow();
        assertEquals("Eva Kováčová", p.fullName(), "meno ostáva pri mzdových podkladoch");
        assertNull(p.email());
        assertNull(p.guardianName());
    }

    @Test
    void webFlowOnlyForAdminWithExportForPeopleRole() throws Exception {
        grant("koord@fgs.example", "KOORDINATOR");
        var koord = user("koord@fgs.example").roles("USER");
        this.mvc.perform(get("/ludia/" + this.eva).with(koord)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Ochrana údajov")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Anonymizovať natrvalo"))));
        this.mvc.perform(get("/ludia/" + this.eva + "/udaje.pdf").with(koord)).andExpect(status().isOk());
        this.mvc.perform(post("/ludia/" + this.eva + "/anonymizovat").with(koord).with(csrf())
                .param("confirmName", "Eva Kováčová")).andExpect(flash().attribute("errors", List.of("Anonymizovať môže len admin.")));

        var admin = user("pokladnik").roles("USER");
        this.mvc.perform(get("/ludia/" + this.eva).with(admin)).andExpect(content().string(containsString("Anonymizovať natrvalo")));
        this.mvc.perform(post("/ludia/" + this.eva + "/anonymizovat").with(admin).with(csrf())
                .param("confirmName", "Eva Kováčová")).andExpect(flash().attribute("message", containsString("anonymizovaná")));
        this.mvc.perform(get("/ludia/" + this.eva).with(admin)).andExpect(content().string(containsString("Anonymizované")));
        this.mvc.perform(get("/ludia/" + this.eva + "/udaje.csv").with(user("vedenie").roles("USER"))).andExpect(status().isForbidden());
    }
}
