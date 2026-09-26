package com.fakturacnysoftver.web.access;

import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.activity.ActivityRepository;
import com.fakturacnysoftver.web.people.Person;
import com.fakturacnysoftver.web.people.PersonRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "app.security.local-readers=eva@fgs.example")
class AccessServiceTest extends IntegrationTest {
    @Autowired
    AccessService service;
    @Autowired
    AccessRepository repo;
    @Autowired
    PersonRepository people;
    @Autowired
    ActivityRepository activities;

    long project;

    @BeforeEach
    void setUp() {
        this.project = this.activities.create(new ActivityRepository.Fields("NK-2027", "Národné kolo", "NARODNE_KOLO",
                "PRIPRAVA", LocalDate.of(2027, 4, 24), null, null, null, BigDecimal.ZERO));
    }

    private long role(String code) {
        return this.repo.roleByCode(code).orElseThrow().id();
    }

    private AccessInfo admin() {
        return this.service.resolve("pokladnik", null).access();
    }

    @Test
    void bootstrapAdminAndAllowedAccountsAreProvisionedWithLeastPrivilege() {
        AccessInfo boot = admin();
        assertTrue(boot.isAdmin() && boot.isFinanceWrite() && boot.bootstrapAdmin(), "lokálny admin má všetko");
        assertEquals(List.of("ADMIN"), this.repo.userByEmail("pokladnik").orElseThrow().roleCodes());

        AccessInfo eva = this.service.resolve("Eva@FGS.example", "Eva").access();
        assertEquals(List.of("DOBROVOLNIK"), this.repo.userByEmail("eva@fgs.example").orElseThrow().roleCodes());
        assertTrue(eva.permissions().isEmpty(), "dobrovoľník má len svoj program");

        AccessInfo stranger = this.service.resolve("cudzi@example.com", null).access();
        assertTrue(stranger.permissions().isEmpty());
        assertTrue(this.repo.userByEmail("cudzi@example.com").isEmpty(), "nepozvaného nezakladáme");
        assertFalse(this.service.mayLogIn("cudzi@example.com"));
    }

    @Test
    void roleChangesAndDeactivationApplyImmediatelyAndCannotLockOut() {
        AccessInfo admin = admin();
        long eva = this.service.createUser("eva@fgs.example", "Eva", List.of(role("MENTOR")), "pokladnik");
        assertTrue(this.service.resolve("eva@fgs.example", null).access().isActivitiesRead());

        this.service.setRoles(eva, List.of(role("FINANCIE")), admin);
        AccessInfo now = this.service.resolve("eva@fgs.example", null).access();
        assertTrue(now.isFinanceWrite() && now.isFinanceRead(), "zápis zahŕňa čítanie");
        assertFalse(now.isPeople());

        this.service.setActive(eva, false, admin);
        assertTrue(this.service.resolve("eva@fgs.example", null).disabled());
        assertFalse(this.service.mayLogIn("eva@fgs.example"));

        long boss = this.repo.userByEmail("pokladnik").orElseThrow().id();
        AccessException self = assertThrows(AccessException.class,
                () -> this.service.setRoles(boss, List.of(role("MENTOR")), admin));
        assertTrue(self.errors().get(0).startsWith("Sebe rolu Admin zobrať nemôžete"));
        assertThrows(AccessException.class, () -> this.service.setActive(boss, false, admin));
        assertTrue(this.service.resolve("pokladnik", null).access().isAdmin(), "núdzový admin z konfigurácie ostáva");
    }

    @Test
    void ownershipGivesEditRightsOnlyForThatActivity() {
        long pm = this.service.createUser("pm@fgs.example", null, List.of(role("PROJEKTOVY_MANAZER")), "pokladnik");
        long other = this.activities.create(new ActivityRepository.Fields("SUST", "Sústredenie", "SUSTREDENIE",
                "PRIPRAVA", null, null, null, null, BigDecimal.ZERO));
        this.service.addOwner(pm, this.project, "pokladnik");

        AccessInfo a = this.service.resolve("pm@fgs.example", null).access();
        assertTrue(a.canEditActivity(this.project) && a.canSeeActivityBudget(this.project));
        assertFalse(a.canEditActivity(other));
        assertFalse(a.isFinanceRead(), "vidí rozpočet svojej akcie, nie celé financie");
        assertEquals(List.of("NK-2027"), this.repo.user(pm).orElseThrow().ownedCodes());
    }

    @Test
    void customRoleFromPermissions() {
        long id = this.service.createRole("Hodnotiteľ NTE", "Číta aktivity", List.of("ACTIVITIES_READ"), "HODNOTITEL",
                "pokladnik");
        assertEquals("HODNOTITEL_NTE", this.repo.role(id).orElseThrow().code());
        assertThrows(AccessException.class, () -> this.service.createRole("Hodnotitel NTE", null, List.of(), null, "x"),
                "rovnaký kód");
        assertThrows(AccessException.class, () -> this.service.createRole("Zlá", null, List.of("ROOT"), null, "x"));
        long admin = role("ADMIN");
        assertThrows(AccessException.class, () -> this.service.updateRole(admin, "Admin", null, List.of(), null, "x"),
                "Admin musí ostať adminom");
        assertThrows(AccessException.class, () -> this.service.deleteRole(role("MENTOR"), "x"), "systémová rola");
        this.service.deleteRole(id, "pokladnik");
    }

    @Test
    void openInvitesCannotGrantSensitiveRolesAndEmailInvitesAreBound() {
        AccessException open = assertThrows(AccessException.class, () -> this.service.createInvite(null,
                List.of(role("FINANCIE")), null, null, 7, 10, "pokladnik"));
        assertTrue(open.errors().get(0).startsWith("Otvorený odkaz"));
        assertThrows(AccessException.class, () -> this.service.createInvite(null, List.of(role("MENTOR")), this.project,
                null, 7, 10, "pokladnik"), "vlastníctvo len cez e-mail");

        AccessService.CreatedInvite pm = this.service.createInvite("PM@fgs.example", List.of(role("PROJEKTOVY_MANAZER")),
                this.project, "vedie národné kolo", 7, 5, "pokladnik");
        assertTrue(this.service.inviteAllows(pm.token(), "pm@fgs.example"));
        assertFalse(this.service.inviteAllows(pm.token(), "iny@fgs.example"));
        AccessException wrong = assertThrows(AccessException.class,
                () -> this.service.accept(pm.token(), "iny@fgs.example", "Iný"));
        assertTrue(wrong.errors().get(0).contains("p***@fgs.example"), "cudzí e-mail neuvidí celý");

        this.service.accept(pm.token(), "pm@fgs.example", "Peter Manažér");
        AccessInfo a = this.service.resolve("pm@fgs.example", null).access();
        assertTrue(a.owns(this.project) && a.isActivitiesRead());
        Person card = this.people.findByEmail("pm@fgs.example").orElseThrow();
        assertEquals("Peter Manažér", card.fullName());
        assertEquals(List.of("ORGANIZATOR"), card.roles());
        assertTrue(card.consentIssue() != null, "súhlas treba doplniť");
        AccessException used = assertThrows(AccessException.class,
                () -> this.service.accept(pm.token(), "pm@fgs.example", "Peter"));
        assertTrue(used.errors().get(0).contains("využitá"), "e-mailová pozvánka je na jedno použitie");
    }

    @Test
    void openInviteForVolunteersHasLimitedUses() {
        AccessService.CreatedInvite link = this.service.createInvite(null, List.of(role("MENTOR")), null,
                "mentori 2027", 7, 2, "pokladnik");
        this.service.accept(link.token(), "a@x.example", "A");
        this.service.accept(link.token(), "a@x.example", "A");
        this.service.accept(link.token(), "b@x.example", "B");
        AccessException full = assertThrows(AccessException.class, () -> this.service.accept(link.token(), "c@x.example", "C"));
        assertTrue(full.errors().get(0).contains("využitá"), "opakované prijatie tým istým sa nepočíta, tretí už nie");
        assertTrue(this.service.invite("neplatny-token").isEmpty());

        AccessService.CreatedInvite revoked = this.service.createInvite(null, List.of(role("DOBROVOLNIK")), null, null,
                1, 1, "pokladnik");
        this.service.revokeInvite(revoked.id(), "pokladnik");
        assertFalse(this.service.inviteAllows(revoked.token(), "d@x.example"));

        long deactivated = this.service.createUser("zly@x.example", null, List.of(), "pokladnik");
        this.service.setActive(deactivated, false, admin());
        AccessService.CreatedInvite again = this.service.createInvite(null, List.of(role("MENTOR")), null, null, 7, 5, "pokladnik");
        assertThrows(AccessException.class, () -> this.service.accept(again.token(), "zly@x.example", "Zlý"),
                "deaktivovaný sa pozvánkou nevráti");
    }
}
