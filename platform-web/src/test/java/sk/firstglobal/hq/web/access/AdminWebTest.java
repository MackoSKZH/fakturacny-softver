package sk.firstglobal.hq.web.access;

import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.security.SecurityConfig;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminWebTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    AccessRepository repo;
    @Autowired
    ActivityRepository activities;

    final RequestPostProcessor admin = user("pokladnik").roles("USER");
    long project;

    @BeforeEach
    void setUp() {
        this.project = this.activities.create(new ActivityRepository.Fields("NK-2027", "Národné kolo", "NARODNE_KOLO",
                "PRIPRAVA", LocalDate.of(2027, 4, 24), null, null, null, BigDecimal.ZERO));
    }

    private long role(String code) {
        return this.repo.roleByCode(code).orElseThrow().id();
    }

    @Test
    void adminManagesUsersRolesAndOwnership() throws Exception {
        for (String page : List.of("/sprava/pouzivatelia", "/sprava/role", "/sprava/pozvanky")) {
            this.mvc.perform(get(page).with(this.admin)).andExpect(status().isOk());
        }
        this.mvc.perform(post("/sprava/pouzivatelia").with(this.admin).with(csrf()).param("email", "Mentor@FGS.example")
                        .param("displayName", "Mária Mentorka").param("roleIds", String.valueOf(role("MENTOR"))))
                .andExpect(flash().attributeExists("message"));
        long id = this.repo.userByEmail("mentor@fgs.example").orElseThrow().id();
        this.mvc.perform(post("/sprava/pouzivatelia/" + id + "/roly").with(this.admin).with(csrf())
                .param("roleIds", String.valueOf(role("KOORDINATOR")), String.valueOf(role("MENTOR"))));
        this.mvc.perform(post("/sprava/pouzivatelia/" + id + "/vlastnictvo").with(this.admin).with(csrf())
                .param("projectId", String.valueOf(this.project)));
        AccessRepository.User u = this.repo.user(id).orElseThrow();
        assertEquals(List.of("KOORDINATOR", "MENTOR"), u.roleCodes());
        assertEquals(List.of("NK-2027"), u.ownedCodes());
        this.mvc.perform(get("/sprava/pouzivatelia").with(this.admin))
                .andExpect(content().string(containsString("Mária Mentorka")))
                .andExpect(content().string(containsString("/vlastnictvo/" + this.project + "/odobrat")));

        long self = this.repo.userByEmail("pokladnik").orElseThrow().id();
        this.mvc.perform(post("/sprava/pouzivatelia/" + self + "/aktivny").with(this.admin).with(csrf()).param("active", "false"))
                .andExpect(flash().attribute("errors", List.of("Seba deaktivovať nemôžete.")));

        this.mvc.perform(post("/sprava/role").with(this.admin).with(csrf()).param("name", "Hodnotiteľ NTE")
                .param("permissions", "ACTIVITIES_READ").param("personRole", "HODNOTITEL"));
        this.mvc.perform(get("/sprava/role").with(this.admin)).andExpect(content().string(containsString("Hodnotiteľ NTE")));

        this.mvc.perform(get("/sprava/pouzivatelia").with(user("mentor@fgs.example").roles("USER")))
                .andExpect(status().isForbidden());
        this.mvc.perform(get("/moj-program").with(this.admin))
                .andExpect(content().string(containsString(">Správa<")));
    }

    @Test
    void emailInviteFlowFromLinkThroughLoginToAccess() throws Exception {
        MvcResult created = this.mvc.perform(post("/sprava/pozvanky").with(this.admin).with(csrf())
                        .param("email", "pm@fgs.example").param("roleIds", String.valueOf(role("PROJEKTOVY_MANAZER")))
                        .param("projectId", String.valueOf(this.project)).param("days", "7"))
                .andExpect(redirectedUrl("/sprava/pozvanky")).andReturn();
        String link = (String)created.getFlashMap().get("inviteLink");
        String token = link.substring(link.lastIndexOf('/') + 1);
        assertEquals(43, token.length());
        assertFalse(this.jdbc.sql("SELECT token_hash FROM invite").query(String.class).single().contains(token),
                "v DB je len odtlačok");

        MockHttpSession session = new MockHttpSession();
        this.mvc.perform(get("/pozvanka/" + token).session(session).with(anonymous())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Projektový manažér")))
                .andExpect(content().string(containsString("p***@fgs.example")))
                .andExpect(content().string(containsString("Prihlásiť sa")));
        assertEquals(token, session.getAttribute(SecurityConfig.PENDING_INVITE));

        this.mvc.perform(post("/login").session(session).with(csrf()).param("username", "iny@fgs.example")
                .param("password", PASSWORD)).andExpect(redirectedUrl("/login?error"));
        this.mvc.perform(post("/login").session(session).with(csrf()).param("username", "pm@fgs.example")
                .param("password", PASSWORD)).andExpect(redirectedUrl("/"));

        var pm = user("pm@fgs.example").roles("USER");
        this.mvc.perform(get("/").session(session).with(pm)).andExpect(redirectedUrl("/pozvanka/" + token));
        this.mvc.perform(post("/pozvanka/" + token + "/prijat").session(session).with(pm).with(csrf()))
                .andExpect(redirectedUrl("/aktivity"))
                .andExpect(flash().attribute("message", "Vitajte! Pozvánka je prijatá a prístup je nastavený."));
        assertTrue(session.getAttribute(SecurityConfig.PENDING_INVITE) == null);
        this.mvc.perform(get("/aktivity/" + this.project).with(pm)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Upraviť aktivitu")));
        this.mvc.perform(get("/pozvanka/" + token).with(anonymous())).andExpect(content().string(containsString("využitá")));
    }

    @Test
    void sensitiveOpenInviteIsRefusedInTheForm() throws Exception {
        this.mvc.perform(post("/sprava/pozvanky").with(this.admin).with(csrf())
                        .param("roleIds", String.valueOf(role("ADMIN"))).param("maxUses", "50"))
                .andExpect(redirectedUrl("/sprava/pozvanky"))
                .andExpect(flash().attributeExists("errors"));
        assertEquals(0, this.jdbc.sql("SELECT count(*) FROM invite").query(Long.class).single());
        this.mvc.perform(get("/pozvanka/" + "x".repeat(43)).with(anonymous())).andExpect(status().isNotFound());
    }
}
