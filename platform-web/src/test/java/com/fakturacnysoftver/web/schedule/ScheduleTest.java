package com.fakturacnysoftver.web.schedule;

import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.activity.ActivityRepository;
import com.fakturacnysoftver.web.activity.StaffingRepository;
import com.fakturacnysoftver.web.activity.TaskRepository;
import com.fakturacnysoftver.web.people.Person;
import com.fakturacnysoftver.web.people.PersonRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScheduleTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ScheduleService service;
    @Autowired
    ScheduleRepository repo;
    @Autowired
    ActivityRepository activities;
    @Autowired
    StaffingRepository staffing;
    @Autowired
    TaskRepository tasks;
    @Autowired
    PersonRepository people;

    long round;
    long jana;
    long peter;
    long declined;

    private long person(String name, String email) {
        return this.people.insert(new Person(null, name, email, null, null, List.of("DOBROVOLNIK"), false, null, null,
                LocalDate.of(2027, 1, 1), false, false, null));
    }

    private long activity(String code, String status) {
        return this.activities.create(new ActivityRepository.Fields(code, "Národné kolo " + code, "NARODNE_KOLO", status,
                LocalDate.of(2027, 4, 24), LocalDate.of(2027, 4, 24), "STU Bratislava", null, BigDecimal.ZERO));
    }

    @BeforeEach
    void setUp() {
        this.round = activity("FGS-2027", "PRIPRAVA");
        this.jana = person("Jana Rozhodkyňa", "jana@fgs.example");
        this.peter = person("Peter Technik", "peter@fgs.example");
        this.declined = person("Odmietla Rola", "nie@fgs.example");

        long referee = this.staffing.addRole(this.round, "Rozhodca", LocalDateTime.of(2027, 4, 24, 8, 0),
                LocalDateTime.of(2027, 4, 24, 13, 0), 2, "Hlási sa pri ihrisku 1");
        this.staffing.assign(referee, this.jana);
        this.staffing.assign(referee, this.declined);
        List<StaffingRepository.Seat> seats = this.staffing.rolesOf(this.round).get(0).seats();
        for (StaffingRepository.Seat s : seats) {
            this.staffing.update(this.round, s.assignmentId(), s.personId() == this.jana ? "POTVRDENY" : "ODMIETOL", null);
        }

        this.service.add(this.round, "2027-04-23T17:00", "2027-04-23T19:00", "Školenie rozhodcov", "Zasadačka", null,
                "rozhodca", null);
        this.service.add(this.round, "2027-04-24T08:30", null, "Otvorenie", null, null, "všetci", null);
        this.service.add(this.round, "2027-04-23T14:00", "2027-04-23T18:00", "Stavba ihriska", null, this.peter,
                "Technici", "Náradie, predlžovačky");
        this.service.add(this.round, "2027-04-24T12:00", null, "Obed hodnotiteľov", null, null, "Hodnotiteľ NTE", null);
        this.tasks.add(this.round, "Ľudia", "Prečítať pravidlá rozhodovania", LocalDate.of(2027, 4, 20), this.jana);
    }

    private static List<String> titles(List<ScheduleEntry> entries) {
        return entries.stream().map(ScheduleEntry::title).toList();
    }

    @Test
    void personalProgramFollowsRolesOwnershipAndTasks() {
        assertEquals(List.of("Prečítať pravidlá rozhodovania", "Školenie rozhodcov", "Rozhodca", "Otvorenie"),
                titles(this.repo.ofPerson(this.jana, null)), "tag roly nezávisí od veľkosti písmen; všetci = celý tím");
        assertEquals(List.of("Stavba ihriska"), titles(this.repo.ofPerson(this.peter, null)),
                "vlastník vidí svoj bod, ale nie body tímu, v ktorom nemá rolu");
        assertTrue(this.repo.ofPerson(this.declined, null).isEmpty(), "kto rolu odmietol, nemá ani program");

        this.activities.update(this.round, new ActivityRepository.Fields(null, "Národné kolo", "NARODNE_KOLO", "ZRUSENA",
                null, null, null, null, BigDecimal.ZERO));
        assertTrue(this.repo.ofPerson(this.jana, null).isEmpty(), "zrušená akcia zmizne z osobného kalendára");
    }

    @Test
    void activityScheduleFiltersByAudienceAndOwner() {
        List<ScheduleEntry> all = this.service.activity(this.round, ScheduleService.Filter.NONE);
        assertEquals(5, all.size(), "4 body programu + 1 smena");
        ScheduleEntry shift = all.stream().filter(e -> e.kind() == ScheduleEntry.Kind.SMENA).findFirst().orElseThrow();
        assertEquals("Jana Rozhodkyňa - obsadené 1/2", shift.people());

        assertEquals(List.of("Školenie rozhodcov", "Rozhodca", "Otvorenie"),
                titles(this.service.activity(this.round, new ScheduleService.Filter("Rozhodca", null, null))));
        assertEquals(List.of("Stavba ihriska"),
                titles(this.service.activity(this.round, new ScheduleService.Filter(null, this.peter, null))));
    }

    @Test
    void rejectsInvalidAgendaItems() {
        ScheduleException e = assertThrows(ScheduleException.class, () -> this.service.add(this.round,
                "2027-04-24T10:00", "2027-04-24T09:00", " ", null, 999L, null, null));
        assertTrue(e.errors().contains("Koniec musí byť po začiatku."), e.errors().toString());
        assertTrue(e.errors().contains("Názov bodu programu je povinný."));
        assertTrue(e.errors().contains("Zodpovedná osoba neexistuje."));
        assertThrows(ScheduleException.class, () -> this.service.add(this.round, "24.4.2027", null, "X", null, null,
                null, null));
        assertThrows(ScheduleException.class, () -> this.service.add(this.round, null, null, "X", null, null, null, null));
    }

    @Test
    void icsUsesUtcWithSummerTimeEscapesAndFolds() {
        String ics = Ics.calendar("FGS - Jana", this.repo.ofPerson(this.jana, null), Instant.parse("2027-01-15T09:00:00Z"));

        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"));
        assertTrue(ics.contains("DTSTART:20270424T063000Z"), "8:30 letného času = 6:30 UTC");
        assertTrue(ics.contains("DTSTART:20270423T150000Z\r\nDTEND:20270423T170000Z"));
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20270420"), "termín úlohy je celodenný");
        assertTrue(ics.contains("UID:smena-"));
        for (String line : ics.split("\r\n")) {
            assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75, "riadok dlhší než 75 bajtov: " + line);
        }
        assertEquals("a\\, b\\; c\\\\ d\\ne", Ics.text("a, b; c\\ d\ne"));
        String winter = Ics.calendar("x", List.of(new ScheduleEntry(ScheduleEntry.Kind.PROGRAM, 1, 1, "A", "A",
                LocalDateTime.of(2027, 1, 20, 9, 0), null, "Zimný bod", null, null, null, List.of(), null, null)),
                Instant.EPOCH);
        assertTrue(winter.contains("DTSTART:20270120T080000Z"), "v zime je posun len hodina");
    }

    @Test
    @WithMockUser(value = "jana@fgs.example", roles = "USER")
    void memberSeesOwnProgramAndSubscribesWithoutEditorRights() throws Exception {
        this.mvc.perform(get("/moj-program")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Školenie rozhodcov")))
                .andExpect(content().string(not(containsString("Stavba ihriska"))));
        this.mvc.perform(post("/moj-program/kalendar").with(csrf())).andExpect(status().is3xxRedirection());
        String token = this.repo.calendarToken(this.jana).orElseThrow();
        assertEquals(43, token.length());

        this.mvc.perform(get("/kalendar/" + token + ".ics").with(anonymous())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/calendar")))
                .andExpect(content().string(containsString("SUMMARY:Školenie rozhodcov [FGS-2027]")));
        this.mvc.perform(get("/kalendar/" + "A".repeat(43) + ".ics").with(anonymous())).andExpect(status().isNotFound());

        this.mvc.perform(post("/moj-program/kalendar/zrusit").with(csrf())).andExpect(status().is3xxRedirection());
        this.mvc.perform(get("/kalendar/" + token + ".ics").with(anonymous())).andExpect(status().isNotFound());

        this.mvc.perform(get("/moj-program/program.xlsx")).andExpect(status().isOk());
        this.mvc.perform(get("/aktivity/" + this.round + "/harmonogram")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Obed hodnotiteľov")))
                .andExpect(content().string(not(containsString("Pridať bod programu"))));
        this.mvc.perform(get("/aktivity/" + this.round + "/harmonogram.pdf").param("pre", "Rozhodca"))
                .andExpect(status().isOk());
        this.mvc.perform(post("/aktivity/" + this.round + "/program").with(csrf()).param("title", "X")
                .param("startsAt", "2027-04-24T10:00")).andExpect(status().isForbidden());
        this.mvc.perform(get("/aktivity/" + this.round + "/rozpis.xlsx")).andExpect(status().isForbidden());
        this.mvc.perform(get("/ludia/" + this.jana + "/program.ics")).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void editorManagesAgendaAndExportsPerPersonBreakdown() throws Exception {
        this.mvc.perform(post("/aktivity/" + this.round + "/program").with(csrf())
                        .param("title", "Vyhlásenie výsledkov").param("startsAt", "2027-04-24T16:00")
                        .param("tags", "všetci, Rozhodca, všetci"))
                .andExpect(status().is3xxRedirection());
        ScheduleEntry added = this.service.activity(this.round, ScheduleService.Filter.NONE).stream()
                .filter(e -> e.title().equals("Vyhlásenie výsledkov")).findFirst().orElseThrow();
        assertEquals(List.of("všetci", "Rozhodca"), added.tags(), "tagy bez duplicít");

        this.mvc.perform(post("/aktivity/" + this.round + "/program/" + added.sourceId()).with(csrf())
                        .param("title", "Vyhlásenie výsledkov a foto").param("startsAt", "2027-04-24T16:30")
                        .param("ownerId", String.valueOf(this.peter)))
                .andExpect(status().is3xxRedirection());
        assertTrue(titles(this.repo.ofPerson(this.peter, null)).contains("Vyhlásenie výsledkov a foto"));
        assertFalse(titles(this.repo.ofPerson(this.jana, null)).contains("Vyhlásenie výsledkov a foto"),
                "po úprave bez tagov už nie je pre rozhodcov");

        String rozpis = this.mvc.perform(get("/aktivity/" + this.round + "/rozpis.csv")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(rozpis.contains("Jana Rozhodkyňa;jana@fgs.example;Rozhodca;2027-04-23;17:00;19:00;Program;Školenie rozhodcov"),
                rozpis);
        assertFalse(rozpis.contains("Odmietla"), rozpis);

        this.mvc.perform(post("/ludia/" + this.jana + "/kalendar").with(csrf())).andExpect(status().is3xxRedirection());
        this.mvc.perform(get("/ludia/" + this.jana)).andExpect(status().isOk())
                .andExpect(content().string(containsString("/kalendar/" + this.repo.calendarToken(this.jana).orElseThrow())));

        this.mvc.perform(post("/aktivity/" + this.round + "/program/" + added.sourceId() + "/zmazat").with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertEquals(5, this.service.activity(this.round, ScheduleService.Filter.NONE).size());
    }
}
