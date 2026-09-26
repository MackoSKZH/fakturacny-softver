package com.fakturacnysoftver.web.timesheet;

import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.people.Person;
import com.fakturacnysoftver.web.people.PersonRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test bezi 15. 1. 2027 (piatok). */
class TimesheetTest extends IntegrationTest {
    @Autowired
    TimesheetService service;
    @Autowired
    TimesheetRepository repo;
    @Autowired
    PersonRepository people;
    @Autowired
    MockMvc mvc;

    long paid;
    long volunteer;

    @BeforeEach
    void setUp() {
        this.paid = this.people.insert(new Person(null, "Jana Koordinátorka", "jana@fgs.example", null, null,
                List.of("PLATENY"), false, null, null, LocalDate.of(2026, 9, 1), false, false, null));
        this.volunteer = this.people.insert(new Person(null, "Dobrovoľník Bez Zmluvy", "dobro@fgs.example", null, null,
                List.of("DOBROVOLNIK"), false, null, null, LocalDate.of(2026, 9, 1), false, false, null));
    }

    private long contract(String kind, String rate, LocalDate from, LocalDate to) {
        return this.service.createContract(this.paid, kind, "Koordinátorka", "Organizácia národného kola", rate, from, to,
                null, null, "pokladnik");
    }

    private List<String> errorsOf(Runnable r) {
        return assertThrows(TimesheetService.TimesheetException.class, r::run).errors();
    }

    @Test
    void contractRulesFollowLabourCode() {
        assertTrue(errorsOf(() -> this.service.createContract(this.volunteer, "DOVP", "X", null, "10",
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30), null, null, "p")).get(0).contains("Platený spolupracovník"));
        assertTrue(errorsOf(() -> contract("DOVP", "5,00", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30)))
                .get(0).contains("minimálnou hodinovou mzdou"));
        assertTrue(errorsOf(() -> contract("DOPC", "10", LocalDate.of(2027, 1, 1), LocalDate.of(2028, 1, 1)))
                .get(0).contains("12 mesiacov"));
        long zivnost = contract("ZIVNOST", "5,00", LocalDate.of(2027, 1, 1), LocalDate.of(2028, 6, 30));
        assertEquals("ZIVNOST", this.repo.contract(zivnost).orElseThrow().kind(), "živnosť nemá minimálnu mzdu ani 12 mesiacov");
    }

    @Test
    void enforcesDailyAndWeeklyLimits() {
        long dopc = contract("DOPC", "12,40", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        long dovp = contract("DOVP", "12,40", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));

        this.service.logHours(dopc, LocalDate.of(2027, 1, 11), "6", null, "Príprava", "p");
        assertTrue(errorsOf(() -> this.service.logHours(dopc, LocalDate.of(2027, 1, 12), "4,5", null, "X", "p"))
                .get(0).contains("najviac 10 hodín týždenne"));
        this.service.logHours(dopc, LocalDate.of(2027, 1, 12), "4", null, "Príprava", "p");
        assertTrue(errorsOf(() -> this.service.logHours(dopc, LocalDate.of(2027, 1, 14), "0,5", null, "X", "p"))
                .get(0).contains("10 hodín týždenne"), "10 h už je vyčerpaných");
        this.service.logHours(dopc, LocalDate.of(2027, 1, 4), "8", null, "Predošlý týždeň má vlastný limit", "p");
        assertEquals(new BigDecimal("10.00"), this.repo.contractHours(dopc, LocalDate.of(2027, 1, 11), LocalDate.of(2027, 1, 17)));
    }

    @Test
    void dailyLimitCountsAllContractsOfPerson() {
        long a = contract("DOVP", "10", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        long b = contract("ZIVNOST", "10", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        this.service.logHours(a, LocalDate.of(2027, 1, 14), "8", null, "Ráno", "p");

        assertTrue(errorsOf(() -> this.service.logHours(b, LocalDate.of(2027, 1, 14), "4,5", null, "Večer", "p"))
                .get(0).contains("12 hodín"));
    }

    @Test
    void dovpYearLimitIs350HoursAcrossAllAgreements() {
        long first = contract("DOVP", "10", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 6, 30));
        long second = contract("DOVP", "10", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        for (int d = 0; d < 29; d++) {
            this.repo.insertLog(first, LocalDate.of(2027, 1, 1).plusDays(d % 14), new BigDecimal("12"), null, "Setup", "test");
        }
        // 29 x 12 = 348 h cez prvu dohodu

        assertTrue(errorsOf(() -> this.service.logHours(second, LocalDate.of(2027, 1, 15), "3", null, "X", "p"))
                .stream().anyMatch(e -> e.contains("350 hodín")));
        this.service.logHours(second, LocalDate.of(2027, 1, 15), "2", null, "Presne na limit", "p");
    }

    @Test
    void closedMonthIsLockedEvenInDatabase() {
        long c = contract("DOPC", "12,40", LocalDate.of(2026, 12, 1), LocalDate.of(2027, 11, 30));
        this.service.logHours(c, LocalDate.of(2026, 12, 3), "7,5", null, "Príprava sezóny", "p");

        TimesheetService.MonthReport r = this.service.close(c, YearMonth.of(2026, 12), "pokladnik");

        assertEquals(new BigDecimal("93.00"), r.reward(), "7,5 h x 12,40 €");
        assertTrue(errorsOf(() -> this.service.logHours(c, LocalDate.of(2026, 12, 4), "1", null, "Neskoro", "p"))
                .get(0).contains("uzavretý"));
        assertThrows(DataAccessException.class, () -> this.jdbc.sql("DELETE FROM work_log").update());
        assertTrue(errorsOf(() -> this.service.close(c, YearMonth.of(2027, 2), "p")).get(0).contains("Budúci"));
    }

    @Test
    void rejectsFutureAndOutOfContractDates() {
        long c = contract("DOPC", "12", LocalDate.of(2027, 1, 10), LocalDate.of(2027, 6, 30));
        assertTrue(errorsOf(() -> this.service.logHours(c, LocalDate.of(2027, 1, 16), "1", null, "X", "p"))
                .get(0).contains("budúcnosti"));
        assertTrue(errorsOf(() -> this.service.logHours(c, LocalDate.of(2027, 1, 9), "1", null, "X", "p"))
                .get(0).contains("mimo platnosti"));
    }

    @Test
    @WithMockUser(value = "jana@fgs.example", roles = "USER")
    void paidPersonLogsOwnHoursButCannotTouchOthers() throws Exception {
        long mine = contract("DOPC", "12", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        long other = this.service.createContract(this.people.insert(new Person(null, "Iný Platený", "iny@fgs.example",
                        null, null, List.of("PLATENY"), false, null, null, LocalDate.of(2026, 9, 1), false, false, null)),
                "DOPC", "Iná", null, "12", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), null, null, "p");

        this.mvc.perform(get("/moja-dochadzka")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Moja dochádzka")));
        this.mvc.perform(post("/moja-dochadzka/" + mine + "/hodiny").with(csrf()).param("workDate", "2027-01-14")
                .param("hours", "3").param("description", "Komunikácia so školami")).andExpect(status().is3xxRedirection());
        assertEquals(new BigDecimal("3.00"), this.repo.contractHours(mine, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)));

        this.mvc.perform(post("/moja-dochadzka/" + other + "/hodiny").with(csrf()).param("workDate", "2027-01-14")
                .param("hours", "3").param("description", "Cudzia")).andExpect(status().isForbidden());
        this.mvc.perform(get("/dochadzka")).andExpect(status().isForbidden());
        this.mvc.perform(post("/dochadzka/" + mine + "/uzavriet").with(csrf()).param("mesiac", "2027-01"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void monthlyExportForAccountant() throws Exception {
        long c = contract("DOPC", "12,40", LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));
        this.service.logHours(c, LocalDate.of(2027, 1, 14), "7,5", null, "Príprava", "p");

        String csv = new String(this.mvc.perform(get("/dochadzka/export.csv").param("mesiac", "2027-01"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        assertTrue(csv.contains("2027-01;Jana Koordinátorka;Koordinátorka;Dohoda o pracovnej činnosti;7,50;12,40;93,00;"), csv);
        assertTrue(csv.trim().endsWith("NIE"), "neuzavretý mesiac je zreteľne označený");
        this.mvc.perform(get("/dochadzka/" + c).param("mesiac", "2027-01")).andExpect(status().isOk())
                .andExpect(content().string(containsString("93,00 €")));
        this.mvc.perform(get("/dochadzka")).andExpect(status().isOk());
    }
}
