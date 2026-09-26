package sk.firstglobal.hq.web.activity;

import sk.firstglobal.hq.web.IntegrationTest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ActivityTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ActivityRepository activities;
    @Autowired
    StaffingRepository staffing;
    @Autowired
    TaskRepository tasks;

    private long createNationalRound() throws Exception {
        MvcResult r = this.mvc.perform(post("/aktivity").with(csrf())
                        .param("code", "fgs-2027").param("name", "FIRST Global Slovensko 2027")
                        .param("kind", "NARODNE_KOLO").param("startsOn", "2027-04-24").param("endsOn", "2027-04-24")
                        .param("location", "Bratislava").param("budget", "6 000").param("useTemplate", "true"))
                .andExpect(status().is3xxRedirection()).andReturn();
        return Long.parseLong(r.getResponse().getRedirectedUrl().replace("/aktivity/", ""));
    }

    private long createPerson(String name, boolean minor, String consentOn, boolean byGuardian) throws Exception {
        var req = post("/ludia").with(csrf()).param("fullName", name).param("roles", "DOBROVOLNIK", "ROZHODCA");
        if (minor) {
            req = req.param("minor", "true").param("guardianName", "Rodič " + name).param("guardianContact", "0900 000 000");
        }
        if (consentOn != null) {
            req = req.param("dataConsentOn", consentOn);
        }
        if (byGuardian) {
            req = req.param("consentByGuardian", "true");
        }
        MvcResult r = this.mvc.perform(req).andExpect(status().is3xxRedirection()).andReturn();
        return Long.parseLong(r.getResponse().getRedirectedUrl().replace("/ludia/", ""));
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void templateCreatesDatedChecklistForNationalRound() throws Exception {
        long id = createNationalRound();

        List<TaskRepository.Task> list = this.tasks.tasksOf(id);
        assertEquals(22, list.size());
        TaskRepository.Task consents = list.stream().filter(t -> t.title().startsWith("Zbierať súhlasy rodičov"))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2027, 4, 3), consents.dueOn(), "21 dní pred akciou");
        assertEquals("Deti a GDPR", consents.section());

        this.mvc.perform(post("/aktivity/" + id + "/sablona").with(csrf())).andExpect(status().is3xxRedirection());
        assertEquals(22, this.tasks.tasksOf(id).size(), "šablóna sa nezdvojí");
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void staffingCountsOnlyConfirmedAndFlagsMissingGuardianConsent() throws Exception {
        long id = createNationalRound();
        long adult = createPerson("Eva Dospelá", false, "2027-01-10", false);
        long minor = createPerson("Tomáš Študent", true, "2027-01-10", false);

        this.mvc.perform(post("/aktivity/" + id + "/roly").with(csrf()).param("name", "Rozhodca").param("needed", "2")
                .param("startsAt", "2027-04-24T09:00").param("endsAt", "2027-04-24T13:00")).andExpect(status().is3xxRedirection());
        long roleId = this.staffing.rolesOf(id).get(0).id();
        this.mvc.perform(post("/aktivity/" + id + "/roly/" + roleId + "/priradit").with(csrf()).param("personId", String.valueOf(adult)));
        this.mvc.perform(post("/aktivity/" + id + "/roly/" + roleId + "/priradit").with(csrf()).param("personId", String.valueOf(minor)));
        StaffingRepository.Role role = this.staffing.rolesOf(id).get(0);
        this.mvc.perform(post("/aktivity/" + id + "/obsadenie/" + role.seats().get(0).assignmentId()).with(csrf())
                        .param("status", "ZUCASTNIL_SA").param("hours", "4,5"))
                .andExpect(flash().attribute("errors", List.of("Účasť sa dá potvrdiť až v deň akcie alebo po nej (24. 4. 2027).")));
        this.mvc.perform(post("/aktivity/" + id + "/obsadenie/" + role.seats().get(0).assignmentId()).with(csrf())
                .param("status", "POTVRDENY").param("hours", "4,5"));

        Activity a = this.activities.findById(id, LocalDate.of(2027, 1, 15)).orElseThrow();
        assertEquals(2, a.seatsNeeded());
        assertEquals(1, a.seatsFilled(), "pozvaný sa nepočíta");
        StaffingRepository.Role after = this.staffing.rolesOf(id).get(0);
        assertTrue(after.seats().stream().anyMatch(s -> s.personName().equals("Tomáš Študent") && s.consentMissing()));

        this.mvc.perform(get("/aktivity/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("chýba súhlas")))
                .andExpect(content().string(containsString("1 / 2")));
        this.mvc.perform(get("/ludia/" + adult)).andExpect(status().isOk())
                .andExpect(content().string(containsString("4.50")));
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void rejectsMinorWithoutGuardianAndInvalidRoleTimes() throws Exception {
        this.mvc.perform(post("/ludia").with(csrf()).param("fullName", "Dieťa").param("minor", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("zákonného zástupcu")));
        long id = createNationalRound();
        this.mvc.perform(post("/aktivity/" + id + "/roly").with(csrf()).param("name", "Moderátor")
                .param("startsAt", "2027-04-24T13:00").param("endsAt", "2027-04-24T09:00"));
        assertTrue(this.staffing.rolesOf(id).isEmpty());
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void tasksCanBeCompletedAndReopened() throws Exception {
        long id = createNationalRound();
        long taskId = this.tasks.tasksOf(id).get(0).id();

        this.mvc.perform(post("/aktivity/" + id + "/ulohy/" + taskId + "/hotovo").with(csrf()));
        assertTrue(this.tasks.tasksOf(id).stream().anyMatch(t -> t.id() == taskId && t.done() && "pokladnik".equals(t.doneBy())));
        this.mvc.perform(post("/aktivity/" + id + "/ulohy/" + taskId + "/hotovo").with(csrf()));
        assertTrue(this.tasks.tasksOf(id).stream().anyMatch(t -> t.id() == taskId && !t.done()));
    }

    @Test
    @WithMockUser(value = "clen", roles = "USER")
    void memberSeesActivitiesButNotPeopleDirectory() throws Exception {
        grant("clen", "MENTOR");
        this.mvc.perform(get("/aktivity")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Nová aktivita"))))
                .andExpect(content().string(not(containsString("href=\"/ludia\""))));
        this.mvc.perform(get("/ludia")).andExpect(status().isForbidden());
        this.mvc.perform(post("/aktivity").with(csrf()).param("code", "X1").param("name", "X").param("kind", "INE"))
                .andExpect(status().isForbidden());
    }
}
