package sk.firstglobal.hq.web.access;

import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.activity.StaffingRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Projektovy manazer pozyva dobrovolnikov na smenu svojej aktivity. */
class ShiftInviteTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ActivityRepository activities;
    @Autowired
    StaffingRepository staffing;
    @Autowired
    AccessService access;
    @Autowired
    AccessRepository repo;

    long own;
    long other;
    long judges;
    long otherShift;
    AccessInfo pm;

    @BeforeEach
    void setUp() {
        this.own = this.activities.create(new ActivityRepository.Fields("NK-2027", "Národné kolo", "NARODNE_KOLO",
                "PRIPRAVA", LocalDate.of(2027, 4, 24), null, null, null, BigDecimal.ZERO));
        this.other = this.activities.create(new ActivityRepository.Fields("FGC-2027", "Cesta na FGC", "CESTA",
                "PRIPRAVA", LocalDate.of(2027, 9, 1), null, null, null, BigDecimal.ZERO));
        this.judges = this.staffing.addRole(this.own, "Rozhodca", LocalDateTime.of(2027, 1, 20, 8, 0),
                LocalDateTime.of(2027, 1, 20, 13, 0), 1, null);
        this.otherShift = this.staffing.addRole(this.other, "Tlmočník", null, null, 2, null);
        grant("pm@fgs.example", "PROJEKTOVY_MANAZER");
        this.access.addOwner(this.repo.userByEmail("pm@fgs.example").orElseThrow().id(), this.own, "admin");
        this.pm = this.access.resolve("pm@fgs.example", null).access();
    }

    private static RequestPostProcessor as(String who) {
        return user(who).roles("USER");
    }

    private long role(String code) {
        return this.repo.roleByCode(code).orElseThrow().id();
    }

    private String token(MvcResult r) {
        String link = (String)r.getFlashMap().get("inviteLink");
        return link.substring(link.lastIndexOf('/') + 1);
    }

    @Test
    void ownerInvitesVolunteersWhoJoinTheShiftUpToCapacity() throws Exception {
        var me = as("pm@fgs.example");
        this.mvc.perform(get("/aktivity/" + this.own).with(me)).andExpect(content().string(containsString("Pozvať do tímu")));
        MvcResult r = this.mvc.perform(post("/aktivity/" + this.own + "/pozvanky").with(me).with(csrf())
                        .param("shiftId", String.valueOf(this.judges)).param("maxUses", "5").param("days", "30"))
                .andExpect(redirectedUrl("/aktivity/" + this.own + "#pozvanky")).andReturn();
        String token = token(r);

        AccessRepository.Invite i = this.access.invite(token).orElseThrow();
        assertEquals(List.of(role("DOBROVOLNIK")), i.roleIds());
        assertEquals(LocalDateTime.of(2027, 1, 20, 8, 0).atZone(ZoneId.of("Europe/Bratislava")).toInstant(),
                i.expiresAt().toInstant(), "odkaz neplatí dlhšie ako do začiatku smeny");

        this.mvc.perform(get("/pozvanka/" + token)).andExpect(content().string(containsString("Rozhodca")))
                .andExpect(content().string(containsString("NK-2027 Národné kolo")));

        assertEquals("POTVRDENY", this.access.accept(token, "a@x.example", "Anna").shiftStatus());
        AccessService.Accepted second = this.access.accept(token, "b@x.example", "Boris");
        assertEquals("POZVANY", second.shiftStatus(), "nad kapacitu len čaká");
        assertTrue(second.message().contains("obsadená"));
        assertEquals(null, this.access.accept(token, "b@x.example", "Boris").shiftStatus(),
                "druhé prijatie tým istým človekom nič nemení");

        StaffingRepository.Role shift = this.staffing.rolesOf(this.own).getFirst();
        assertEquals(2, shift.seats().size());
        assertEquals(1, shift.confirmed());
        assertTrue(this.access.resolve("a@x.example", null).access().permissions().isEmpty(), "dobrovoľník nič navyše");

        this.mvc.perform(post("/pozvanka/" + this.access.createShiftInvite(this.own, this.judges, "c@x.example", null, null,
                        null, null, this.pm).token() + "/prijat").with(as("c@x.example")).with(csrf()))
                .andExpect(redirectedUrl("/moj-program"))
                .andExpect(flash().attribute("message", containsString("obsadená")));
    }

    @Test
    void ownerCannotHandOutSensitiveRolesOrInviteElsewhere() throws Exception {
        AccessException fin = assertThrows(AccessException.class, () -> this.access.createShiftInvite(this.own, this.judges,
                "f@x.example", role("FINANCIE"), null, null, null, this.pm));
        assertTrue(fin.errors().getFirst().contains("len s rolou na čítanie"));
        assertThrows(AccessException.class, () -> this.access.createShiftInvite(this.own, this.judges, null,
                role("KOORDINATOR"), null, null, null, this.pm));
        this.access.createShiftInvite(this.own, this.judges, null, role("MENTOR"), null, null, 3, this.pm);

        assertThrows(AccessException.class, () -> this.access.createShiftInvite(this.own, this.otherShift, null, null,
                null, null, null, this.pm), "smena inej aktivity");
        assertThrows(AccessException.class, () -> this.access.createShiftInvite(this.other, this.otherShift, null, null,
                null, null, null, this.pm), "nie je vlastník");

        var me = as("pm@fgs.example");
        this.mvc.perform(post("/aktivity/" + this.other + "/pozvanky").with(me).with(csrf())
                .param("shiftId", String.valueOf(this.otherShift))).andExpect(status().isForbidden());

        grant("koord@fgs.example", "KOORDINATOR");
        AccessInfo koord = this.access.resolve("koord@fgs.example", null).access();
        long foreign = this.access.createShiftInvite(this.other, this.otherShift, null, null, null, null, 5, koord).id();
        this.mvc.perform(post("/aktivity/" + this.own + "/pozvanky/" + foreign + "/zrusit").with(me).with(csrf()))
                .andExpect(status().isNotFound());

        grant("mentor@fgs.example", "MENTOR");
        this.mvc.perform(get("/aktivity/" + this.own).with(as("mentor@fgs.example")))
                .andExpect(content().string(not(containsString("Pozvať do tímu"))));
    }

    @Test
    void startedShiftOrClosedActivityRejectsInvites() {
        long past = this.staffing.addRole(this.own, "Včerajšia", LocalDateTime.of(2027, 1, 14, 8, 0), null, 1, null);
        AccessException e = assertThrows(AccessException.class, () -> this.access.createShiftInvite(this.own, past, null,
                null, null, null, null, this.pm));
        assertTrue(e.errors().getFirst().contains("už začala"));

        var f = this.activities.findById(this.own, LocalDate.of(2027, 1, 15)).orElseThrow();
        this.activities.update(this.own, new ActivityRepository.Fields(f.code(), f.name(), f.kind(), "ZRUSENA",
                f.startsOn(), f.endsOn(), f.location(), f.description(), f.budget()));
        assertThrows(AccessException.class, () -> this.access.createShiftInvite(this.own, this.judges, null, null, null,
                null, null, this.pm));
    }
}
