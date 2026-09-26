package com.fakturacnysoftver.web.access;

import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.activity.ActivityRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Opravnenia cez HTTP: vlastnictvo, okamzite odobratie roly, deaktivacia, dobrovolnik. */
class AuthorizationTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ActivityRepository activities;
    @Autowired
    AccessService access;
    @Autowired
    AccessRepository repo;

    long own;
    long other;

    @BeforeEach
    void setUp() {
        this.own = this.activities.create(new ActivityRepository.Fields("NK-2027", "Národné kolo", "NARODNE_KOLO",
                "PRIPRAVA", LocalDate.of(2027, 4, 24), null, null, null, new BigDecimal("6543")));
        this.other = this.activities.create(new ActivityRepository.Fields("FGC-2027", "Cesta na FGC", "CESTA",
                "PRIPRAVA", LocalDate.of(2027, 9, 1), null, null, null, new BigDecimal("9876")));
    }

    private static RequestPostProcessor as(String who) {
        return user(who).roles("USER");
    }

    @Test
    void projectManagerEditsOnlyOwnedActivityAndSeesOnlyItsBudget() throws Exception {
        grant("pm@fgs.example", "PROJEKTOVY_MANAZER");
        long pm = this.repo.userByEmail("pm@fgs.example").orElseThrow().id();
        this.access.addOwner(pm, this.own, "admin");
        var me = as("pm@fgs.example");

        this.mvc.perform(post("/aktivity/" + this.own + "/program").with(me).with(csrf())
                .param("title", "Registrácia").param("startsAt", "2027-04-24T08:00")).andExpect(status().is3xxRedirection());
        this.mvc.perform(post("/aktivity/" + this.other + "/program").with(me).with(csrf())
                .param("title", "Cudzí bod").param("startsAt", "2027-09-01T08:00")).andExpect(status().isForbidden());
        this.mvc.perform(post("/aktivity").with(me).with(csrf()).param("code", "NOVA").param("name", "X")
                .param("kind", "INE")).andExpect(status().isForbidden());
        this.mvc.perform(get("/aktivity/" + this.own).with(me)).andExpect(status().isOk())
                .andExpect(content().string(containsString("6 543")))
                .andExpect(content().string(containsString("Upraviť aktivitu")));
        this.mvc.perform(get("/aktivity/" + this.other).with(me)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("9 876"))))
                .andExpect(content().string(not(containsString("Upraviť aktivitu"))));
        this.mvc.perform(get("/polozky").with(me)).andExpect(status().isForbidden());
        this.mvc.perform(get("/aktivity/" + this.own + "/rozpis.xlsx").with(me)).andExpect(status().isOk());
        this.mvc.perform(get("/aktivity/" + this.other + "/rozpis.xlsx").with(me)).andExpect(status().isForbidden());
        this.mvc.perform(post("/aktivity/" + this.own + "/vlastnici").with(me).with(csrf()).param("userId", String.valueOf(pm)))
                .andExpect(status().isForbidden());
        this.mvc.perform(get("/aktivity/abc").with(me)).andExpect(status().isForbidden());
    }

    @Test
    void revokedRoleAndDeactivationApplyOnTheNextRequest() throws Exception {
        grant("jana@fgs.example", "FINANCIE");
        var jana = as("jana@fgs.example");
        this.mvc.perform(get("/polozky").with(jana)).andExpect(status().isOk());

        long id = this.repo.userByEmail("jana@fgs.example").orElseThrow().id();
        AccessInfo admin = this.access.resolve("pokladnik", null).access();
        this.access.setRoles(id, List.of(this.repo.roleByCode("MENTOR").orElseThrow().id()), admin);
        this.mvc.perform(get("/polozky").with(jana)).andExpect(status().isForbidden());
        this.mvc.perform(get("/aktivity").with(jana)).andExpect(status().isOk());

        this.access.setActive(id, false, admin);
        this.mvc.perform(get("/aktivity").with(jana)).andExpect(redirectedUrl("/login?disabled"));
    }

    @Test
    void volunteerLandsOnOwnProgramAndSeesNoInternalMenus() throws Exception {
        grant("eva@fgs.example", "DOBROVOLNIK");
        var eva = as("eva@fgs.example");
        this.mvc.perform(get("/").with(eva)).andExpect(redirectedUrl("/moj-program"));
        this.mvc.perform(get("/aktivity").with(eva)).andExpect(status().isForbidden());
        this.mvc.perform(get("/aktivity/" + this.own).with(eva)).andExpect(status().isForbidden());
        this.mvc.perform(get("/majetok").with(eva)).andExpect(status().isForbidden());
        this.mvc.perform(get("/moj-program").with(eva)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString(">Položky<"))))
                .andExpect(content().string(not(containsString(">Aktivity<"))))
                .andExpect(content().string(not(containsString(">Správa<"))));
    }

    @Test
    void coordinatorAssignsOwnersAndUnknownAccountHasNoAccess() throws Exception {
        grant("koord@fgs.example", "KOORDINATOR");
        grant("pm@fgs.example", "PROJEKTOVY_MANAZER");
        long pm = this.repo.userByEmail("pm@fgs.example").orElseThrow().id();
        this.mvc.perform(post("/aktivity/" + this.own + "/vlastnici").with(as("koord@fgs.example")).with(csrf())
                .param("userId", String.valueOf(pm))).andExpect(redirectedUrl("/aktivity/" + this.own + "#vlastnici"));
        this.mvc.perform(get("/aktivity/" + this.own).with(as("koord@fgs.example")))
                .andExpect(content().string(containsString("pm@fgs.example")))
                .andExpect(content().string(not(containsString("6 543"))));

        this.mvc.perform(get("/aktivity").with(as("neznamy@example.com"))).andExpect(status().isForbidden());
        this.mvc.perform(get("/nastavenia").with(as("koord@fgs.example"))).andExpect(status().isForbidden());
    }
}
