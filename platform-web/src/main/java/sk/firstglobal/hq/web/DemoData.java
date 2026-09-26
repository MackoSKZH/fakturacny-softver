package sk.firstglobal.hq.web;

import sk.firstglobal.hq.web.access.AccessRepository;
import sk.firstglobal.hq.web.access.AccessService;
import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.activity.StaffingRepository;
import sk.firstglobal.hq.web.activity.TaskRepository;
import sk.firstglobal.hq.web.asset.AssetService;
import sk.firstglobal.hq.web.donation.DonationRepository;
import sk.firstglobal.hq.web.ledger.LedgerEntry;
import sk.firstglobal.hq.web.ledger.LedgerInput;
import sk.firstglobal.hq.web.ledger.LedgerService;
import sk.firstglobal.hq.web.organization.Organization;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.partner.PartnerService;
import sk.firstglobal.hq.web.people.Person;
import sk.firstglobal.hq.web.people.PersonRepository;
import sk.firstglobal.hq.web.schedule.ScheduleService;
import sk.firstglobal.hq.web.timesheet.TimesheetService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Ukazkove data na vyskusanie (profil "demo"). Zapisu sa len do prazdnej databazy - existujuce udaje
 * nikdy neprepise. Datumy su relativne k dnesku, aby termin, upozornenia a potvrdenia vzdy davali zmysel.
 */
@Component
public class DemoData {
    private static final Logger LOG = LoggerFactory.getLogger(DemoData.class);
    private static final String ACTOR = "demo";

    private final OrganizationRepository organizations;
    private final PersonRepository people;
    private final ActivityRepository activities;
    private final StaffingRepository staffing;
    private final TaskRepository tasks;
    private final ScheduleService schedule;
    private final LedgerService ledger;
    private final PartnerService partners;
    private final AssetService assets;
    private final TimesheetService timesheets;
    private final DonationRepository donations;
    private final AccessService access;
    private final AccessRepository accessRepo;
    private final Clock clock;

    public DemoData(OrganizationRepository organizations, PersonRepository people, ActivityRepository activities,
                    StaffingRepository staffing, TaskRepository tasks, ScheduleService schedule, LedgerService ledger,
                    PartnerService partners, AssetService assets, TimesheetService timesheets,
                    DonationRepository donations, AccessService access, AccessRepository accessRepo, Clock clock) {
        this.access = access;
        this.accessRepo = accessRepo;
        this.organizations = organizations;
        this.people = people;
        this.activities = activities;
        this.staffing = staffing;
        this.tasks = tasks;
        this.schedule = schedule;
        this.ledger = ledger;
        this.partners = partners;
        this.assets = assets;
        this.timesheets = timesheets;
        this.donations = donations;
        this.clock = clock;
    }

    @Bean
    @Profile("demo")
    static ApplicationRunner demoDataRunner(DemoData demo) {
        return args -> demo.seed();
    }

    /** @return true, ak data zapisal; false, ak databaza uz nieco obsahuje */
    @Transactional
    public boolean seed() {
        if (this.organizations.find().isPresent() || !this.people.findAll().isEmpty()) {
            LOG.info("Demo dáta sa nezapisujú - databáza už obsahuje údaje.");
            return false;
        }
        LocalDate today = LocalDate.now(this.clock);
        this.organizations.save(new Organization("FIRST Global Slovakia (DEMO)", "Mlynská dolina 1", "Bratislava", "84248",
                "SK", "12345678", "2120000000", "", "info@demo.example", "", "SK3112000000198742637541", "TATRSKBX",
                "Ukážkové údaje - nie skutočné združenie", "{YYYY}{NNNN}", 14));

        long eva = person("Eva Nováková", "eva@demo.example", List.of("ROZHODCA", "DOBROVOLNIK"), false, null);
        long peter = person("Peter Horváth", "peter@demo.example", List.of("HODNOTITEL"), false, null);
        long marek = person("Marek Technik", "marek@demo.example", List.of("DOBROVOLNIK"), false, null);
        long jana = person("Jana Koordinátorka", "jana@demo.example", List.of("PLATENY"), false, null);
        long tomas = person("Tomáš Študent", null, List.of("STUDENT"), true, "Mária Študentová");

        // Minula akcia: ucast s hodinami -> potvrdenia o dobrovolnictve
        LocalDate campDay = today.minusDays(30);
        long camp = this.activities.create(new ActivityRepository.Fields("SUST-DEMO", "Sústredenie tímu (ukážka)",
                "SUSTREDENIE", "UKONCENA", campDay, campDay.plusDays(2), "Poprad", null, new BigDecimal("1500")));
        long mentor = this.staffing.addRole(camp, "Mentor", campDay.atTime(9, 0), campDay.atTime(17, 0), 2, null);
        attend(camp, mentor, eva, "8");
        attend(camp, mentor, marek, "6.5");

        // Buduca akcia: narodne kolo s timom, harmonogramom, checklistom a verejnou podporou
        LocalDate day = today.plusDays(45);
        long round = this.activities.create(new ActivityRepository.Fields("NK-DEMO", "Národné kolo FIRST Global (ukážka)",
                "NARODNE_KOLO", "PRIPRAVA", day, day, "STU Bratislava", "Interný popis - na verejnej stránke nie je.",
                new BigDecimal("6000")));
        this.tasks.applyTemplate(round, "NARODNE_KOLO", day);
        long referee = this.staffing.addRole(round, "Rozhodca", day.atTime(8, 0), day.atTime(15, 0), 3,
                "Hlási sa pri ihrisku 1");
        long judge = this.staffing.addRole(round, "Hodnotiteľ NTE", day.atTime(9, 0), day.atTime(12, 0), 2, null);
        long tech = this.staffing.addRole(round, "Technik", day.minusDays(1).atTime(14, 0), day.minusDays(1).atTime(19, 0),
                1, null);
        confirm(round, referee, eva);
        confirm(round, judge, peter);
        confirm(round, tech, marek);
        this.staffing.assign(referee, tomas);
        agenda(round, day.minusDays(1).atTime(14, 0), day.minusDays(1).atTime(19, 0), "Stavba ihrísk a testovanie",
                marek, "Technik");
        agenda(round, day.minusDays(1).atTime(17, 0), day.minusDays(1).atTime(18, 30), "Školenie rozhodcov k pravidlám",
                eva, "Rozhodca");
        agenda(round, day.atTime(7, 30), day.atTime(8, 30), "Registrácia tímov", null, "všetci");
        agenda(round, day.atTime(9, 0), day.atTime(12, 0), "Hodnotenie NTE prezentácií", peter, "Hodnotiteľ NTE");
        agenda(round, day.atTime(15, 30), null, "Vyhlásenie výsledkov", null, "všetci");
        this.donations.update(round, true, "Pomôžte nám poslať študentov na svetové finále. Každé euro ide na diely a "
                + "cestovné. (Ukážka - neposielajte skutočné peniaze.)", new BigDecimal("2000"));

        // Financie: grant a sponzor s prepojenymi polozkami
        LocalDate recent = today.minusDays(5);
        long foundation = this.partners.createPartner(new PartnerService.PartnerInput("Nadácia Pre Vedu (demo)", "NADACIA",
                null, null, "Eva Grantová", "granty@nadacia.example", null, null, null, "granty", null), ACTOR);
        long company = this.partners.createPartner(new PartnerService.PartnerInput("Tech s.r.o. (demo)", "FIRMA",
                "87654321", null, "Ján Novák", "jan@tech.example", null, null, jana, "tech, hlavný partner", null), ACTOR);
        this.partners.addNote(company, recent.toString(), "Stretnutie s CEO - súhlasia s 3 000 €, chcú logo na robote "
                + "a report po akcii.", ACTOR);
        String iso = DateTimeFormatter.ISO_LOCAL_DATE.format(today);
        long grant = this.partners.createDeal(new PartnerService.DealInput(foundation, round, "Grant na národné kolo",
                "GRANT", "DOHODNUTE", "4000", null, null, null, "Veda pre mladých", today.minusDays(40).toString(),
                today.minusDays(60).toString(), today.plusDays(120).toString(), today.plusDays(20).toString(), null, null),
                ACTOR);
        long sponsor = this.partners.createDeal(new PartnerService.DealInput(company, round, "Hlavný partner kola",
                "DAR", "DOHODNUTE", "3000", today.plusDays(10).toString(), "Poslať poďakovanie a fotky", iso, null, null,
                null, null, null, null, null), ACTOR);
        this.partners.addDeliverable(sponsor, "Logo na robote a dresoch", day.minusDays(7).toString());
        LedgerEntry grantIn = entry(today.minusDays(20), "Grant Nadácia Pre Vedu - 1. splátka", "PRIJEM", "2000", round, "VBU-07");
        LedgerEntry parts = entry(today.minusDays(12), "REV Control Hub 2x", "VYDAVOK", "780", round, "FA-2026-114");
        LedgerEntry old = entry(today.minusDays(80), "Hliníkové profily", "VYDAVOK", "240", round, "FA-2026-051");
        entry(recent, "Dar od verejnosti", "PRIJEM", "50", round, String.valueOf(DonationRepository.VS_BASE + round));
        for (LedgerEntry e : List.of(grantIn, parts, old)) {
            this.partners.link(grant, e.id(), ACTOR);
        }

        long hub = this.assets.create(new AssetService.AssetInput(null, "REV Control Hub", "ROBOTIKA", "RCH-0042",
                parts.entryDate().toString(), "390", parts.id(), grant, today.plusYears(3).toString(), "dielňa", null), ACTOR);
        this.assets.lend(hub, eva, round, today.plusDays(14).toString(), "s nabíjačkou", ACTOR);
        this.assets.create(new AssetService.AssetInput(null, "Notebook na prezentácie", "POCITAC", null,
                today.minusDays(200).toString(), "899", null, null, null, "sklad", null), ACTOR);

        // Plateny clovek: dohoda o pracovnej cinnosti a par zapisanych hodin tento tyzden
        long contract = this.timesheets.createContract(jana, "DOPC", "Koordinátorka dobrovoľníkov",
                "Nábor a koordinácia dobrovoľníkov, harmonogram, komunikácia s partnermi.", "8,50",
                today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(11).minusDays(1), round, null, ACTOR);
        LocalDate first = today.with(DayOfWeek.MONDAY).isBefore(today.withDayOfMonth(1)) ? today.withDayOfMonth(1)
                : today.with(DayOfWeek.MONDAY);
        this.timesheets.logHours(contract, first, "3", round, "Nábor dobrovoľníkov", ACTOR);
        if (first.isBefore(today)) {
            this.timesheets.logHours(contract, first.plusDays(1), "2,5", round, "Harmonogram a pokyny pre rozhodcov", ACTOR);
        }
        // Pouzivatelia v roznych rolach (v deme sa vsetci prihlasuju spolocnym heslom)
        long pmUser = this.access.createUser("peter@demo.example", "Peter Horváth", roles("PROJEKTOVY_MANAZER"), ACTOR);
        this.access.addOwner(pmUser, round, ACTOR);
        this.access.createUser("jana@demo.example", "Jana Koordinátorka", roles("KOORDINATOR"), ACTOR);
        this.access.createUser("eva@demo.example", "Eva Nováková", roles("DOBROVOLNIK"), ACTOR);
        this.access.createUser("marek@demo.example", "Marek Technik", roles("MENTOR"), ACTOR);
        this.access.createUser("financie@demo.example", "Pokladníčka", roles("FINANCIE"), ACTOR);
        this.access.createUser("rada@demo.example", "Predseda rady", roles("VEDENIE"), ACTOR);
        AccessService.CreatedInvite invite = this.access.createInvite(null, roles("DOBROVOLNIK"), null,
                "Demo - otvorená pozvánka pre dobrovoľníkov", 30, 50, ACTOR);
        LOG.info("Demo dáta sú pripravené. Pozvánka pre dobrovoľníkov: /pozvanka/{}", invite.token());
        return true;
    }

    private List<Long> roles(String... codes) {
        return java.util.Arrays.stream(codes).map(c -> this.accessRepo.roleByCode(c).orElseThrow().id()).toList();
    }

    private long person(String name, String email, List<String> roles, boolean minor, String guardian) {
        LocalDate consent = LocalDate.now(this.clock).minusMonths(2);
        return this.people.insert(new Person(null, name, email, null, null, roles, minor, guardian,
                guardian == null ? null : "0900 000 000", consent, minor, !minor, null));
    }

    private void confirm(long project, long role, long person) {
        this.staffing.assign(role, person);
        long seat = this.seat(project, role, person);
        this.staffing.update(project, seat, "POTVRDENY", null);
    }

    private void attend(long project, long role, long person, String hours) {
        this.staffing.assign(role, person);
        this.staffing.update(project, this.seat(project, role, person), "ZUCASTNIL_SA", new BigDecimal(hours));
    }

    private long seat(long project, long role, long person) {
        return this.staffing.rolesOf(project).stream().filter(r -> r.id() == role).flatMap(r -> r.seats().stream())
                .filter(s -> s.personId() == person).findFirst().orElseThrow().assignmentId();
    }

    private void agenda(long project, LocalDateTime start, LocalDateTime end, String title, Long owner, String tags) {
        this.schedule.add(project, start.toString(), end == null ? null : end.toString(), title, null, owner, tags, null);
    }

    private LedgerEntry entry(LocalDate date, String description, String direction, String amount, long project,
                              String document) {
        return this.ledger.create(new LedgerInput(date.toString(), description, direction, amount, project, List.of(),
                null, null, document, "BANKA", null, null), ACTOR);
    }
}
