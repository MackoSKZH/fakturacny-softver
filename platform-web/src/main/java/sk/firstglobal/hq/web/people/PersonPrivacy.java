package sk.firstglobal.hq.web.people;

import sk.firstglobal.hq.web.access.AccessException;
import sk.firstglobal.hq.web.access.AccessInfo;
import sk.firstglobal.hq.web.access.AccessRepository;
import sk.firstglobal.hq.web.access.AccessService;
import sk.firstglobal.hq.web.access.Permission;
import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.export.Table;
import sk.firstglobal.hq.web.organization.OrganizationRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * GDPR pre jednu osobu: vypis vsetkych udajov (cl. 15 a 20) a anonymizacia (cl. 17).
 *
 * Anonymizacia nemaze kartu - ucasti a hodiny ostanu v statistikach, ale bez mena a kontaktov. Kde zakon prikazuje
 * udaje uchovat (mzdove podklady k dohodam, uctovne doklady), meno ostane a vymazu sa len kontakty a suhlasy
 * (cl. 17 ods. 3 pism. b).
 */
@Service
public class PersonPrivacy {
    static final String REDACTED = "[anonymizované]";

    private final JdbcClient jdbc;
    private final PersonRepository people;
    private final AccessRepository access;
    private final AccessService accessService;
    private final OrganizationRepository organizations;
    private final AuditLog audit;
    private final Clock clock;

    public PersonPrivacy(JdbcClient jdbc, PersonRepository people, AccessRepository access, AccessService accessService,
                         OrganizationRepository organizations, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.people = people;
        this.access = access;
        this.accessService = accessService;
        this.organizations = organizations;
        this.audit = audit;
        this.clock = clock;
    }

    /** Co anonymizacia urobi a co jej brani. */
    public record Assessment(boolean anonymized, OffsetDateTime anonymizedAt, boolean nameRetained, List<String> blockers,
                             List<String> effects) {
        public boolean possible() {
            return !this.anonymized && this.blockers.isEmpty();
        }
    }

    private record State(OffsetDateTime anonymizedAt, boolean nameRetained) {
    }

    private record LinkedUser(long id, String email, boolean active) {
    }

    public Assessment assess(long personId) {
        Person p = this.person(personId);
        State st = this.state(personId);
        if (st.anonymizedAt() != null) {
            return new Assessment(true, st.anonymizedAt(), st.nameRetained(), List.of(), List.of());
        }
        List<String> blockers = new ArrayList<>();
        List<String> effects = new ArrayList<>();
        LocalDate today = LocalDate.now(this.clock);

        long loans = this.count("SELECT count(*) FROM asset_loan WHERE person_id = :id AND returned_on IS NULL", personId);
        if (loans > 0) {
            blockers.add("Má požičaný majetok (" + loans + ") - najprv ho vráťte v Majetku.");
        }
        for (LinkedUser u : this.users(personId, p.email())) {
            if (this.accessService.isBootstrapAdmin(u.email())) {
                blockers.add("Účet " + u.email() + " je admin z konfigurácie (APP_EDITOR_EMAILS) - najprv ho odtiaľ odstráňte.");
            } else if (u.active() && this.access.permissionsOf(u.id()).contains(Permission.ADMIN.name())
                    && this.access.activeAdmins() <= 1) {
                blockers.add("Účet " + u.email() + " je posledný aktívny admin.");
            } else {
                effects.add("Účet " + u.email() + " sa deaktivuje, stratí roly a vlastníctvo aktivít.");
            }
        }
        long contracts = this.count("SELECT count(*) FROM work_contract WHERE person_id = :id", personId);
        if (contracts > 0) {
            effects.add("Meno ostane: " + contracts + " zmluva(y)/dohoda(y) a výkazy sú mzdové podklady, ktoré sa musia "
                    + "uchovať (zákon o účtovníctve, sociálne poistenie). Vymažú sa kontakty, súhlasy a poznámka.");
        } else {
            effects.add("Meno sa nahradí textom „Anonymizovaná osoba " + personId + "“, vymažú sa kontakty, zástupca, "
                    + "súhlasy a poznámka.");
        }
        long future = this.jdbc.sql("""
                        SELECT count(*) FROM assignment a JOIN activity_role r ON r.id = a.role_id
                        JOIN project p ON p.id = r.project_id
                        WHERE a.person_id = :id AND COALESCE(r.starts_at::date, p.starts_on, :today) >= :today""")
                .param("id", personId).param("today", today).query(Long.class).single();
        long past = this.count("SELECT count(*) FROM assignment WHERE person_id = :id", personId) - future;
        if (future > 0) {
            effects.add("Odhlási sa z " + future + " budúcich (alebo nedatovaných) smien.");
        }
        if (past > 0) {
            effects.add(past + " minulých účastí ostane v štatistikách aktivít bez mena.");
        }
        long tasks = this.count("SELECT count(*) FROM task WHERE assignee_person_id = :id AND NOT done", personId);
        if (tasks > 0) {
            effects.add(tasks + " otvorených úloh ostane bez riešiteľa.");
        }
        effects.add("V audit logu sa meno a e-mail nahradia textom " + REDACTED + " - záznamy o tom, čo sa stalo, ostanú.");
        effects.add("Účtovné doklady a položky, kde osoba vystupuje ako protistrana, sa nemenia (povinnosť ich uchovávať).");
        return new Assessment(false, null, contracts > 0, blockers, effects);
    }

    @Transactional
    public void anonymize(long personId, String confirmName, AccessInfo actor) {
        if (!actor.isAdmin()) {
            throw new AccessException(List.of("Anonymizovať môže len admin."));
        }
        Person p = this.person(personId);
        Assessment a = this.assess(personId);
        if (a.anonymized()) {
            throw new AccessException(List.of("Osoba je už anonymizovaná."));
        }
        if (!a.blockers().isEmpty()) {
            throw new AccessException(a.blockers());
        }
        if (confirmName == null || !confirmName.trim().equalsIgnoreCase(p.fullName().trim())) {
            throw new AccessException(List.of("Na potvrdenie napíšte presne meno osoby: " + p.fullName()));
        }
        LocalDate today = LocalDate.now(this.clock);
        boolean keepName = a.nameRetained();

        // identifikatory, ktore treba prepisat v audite
        Set<String> idents = new LinkedHashSet<>();
        addIdent(idents, p.email());
        addIdent(idents, p.phone());
        addIdent(idents, p.guardianContact());
        addIdent(idents, p.guardianName());
        if (!keepName) {
            addIdent(idents, p.fullName());
        }

        this.jdbc.sql("""
                        DELETE FROM assignment a USING activity_role r, project p
                        WHERE a.role_id = r.id AND p.id = r.project_id AND a.person_id = :id
                          AND COALESCE(r.starts_at::date, p.starts_on, :today) >= :today""")
                .param("id", personId).param("today", today).update();
        this.jdbc.sql("UPDATE task SET assignee_person_id = NULL WHERE assignee_person_id = :id").param("id", personId).update();
        this.jdbc.sql("UPDATE agenda_item SET owner_person_id = NULL WHERE owner_person_id = :id").param("id", personId).update();
        this.jdbc.sql("UPDATE partner SET owner_person_id = NULL WHERE owner_person_id = :id").param("id", personId).update();

        List<String[]> actors = new ArrayList<>();
        for (LinkedUser u : this.users(personId, p.email())) {
            String anon = "anonym-" + u.id() + "@anonymized.invalid";
            this.jdbc.sql("DELETE FROM user_role WHERE user_id = :u").param("u", u.id()).update();
            this.jdbc.sql("DELETE FROM activity_owner WHERE user_id = :u").param("u", u.id()).update();
            this.jdbc.sql("UPDATE app_user SET active = false, email = :e, display_name = NULL WHERE id = :u")
                    .param("e", anon).param("u", u.id()).update();
            actors.add(new String[]{u.email(), "anonymizovaný používateľ #" + u.id()});
            addIdent(idents, u.email());
        }
        for (String e : idents) {
            if (e.contains("@")) {
                this.jdbc.sql("UPDATE invite SET email = NULL, revoked_at = COALESCE(revoked_at, now()) WHERE email = :e")
                        .param("e", e.toLowerCase(Locale.ROOT)).update();
            }
        }

        this.jdbc.sql("""
                        UPDATE person SET full_name = CASE WHEN :keep THEN full_name ELSE :anon END, email = NULL,
                               phone = NULL, organization = NULL, guardian_name = NULL, guardian_contact = NULL,
                               data_consent_on = NULL, consent_by_guardian = false, photo_consent = false, is_minor = false,
                               note = NULL, calendar_token = NULL, anonymized_at = now(), name_retained = :keep,
                               updated_at = now()
                        WHERE id = :id""")
                .param("keep", keepName).param("anon", "Anonymizovaná osoba " + personId).param("id", personId).update();

        this.redactAudit(personId, idents, actors, keepName);
        this.audit.record(actor.email(), "ANONYMIZACIA", "osoba", personId,
                keepName ? "meno ponechané - mzdové podklady" : null);
    }

    /** Jediny povoleny prepis audit logu - len v tejto transakcii (trigger kontroluje fgs.audit_redaction). */
    private void redactAudit(long personId, Set<String> idents, List<String[]> actors, boolean keepName) {
        this.jdbc.sql("SELECT set_config('fgs.audit_redaction', 'on', true)").query(String.class).single();
        for (String[] a : actors) {
            this.jdbc.sql("UPDATE audit_log SET actor = :anon WHERE lower(actor) = lower(:e)")
                    .param("anon", a[1]).param("e", a[0]).update();
        }
        for (String ident : idents) {
            String pattern = ident.replaceAll("[\\\\\\[\\]\\^$.|?*+(){}]", "\\\\$0");
            this.jdbc.sql("""
                            UPDATE audit_log SET detail = regexp_replace(detail, :p, :r, 'gi')
                            WHERE detail ~* :p""")
                    .param("p", pattern).param("r", REDACTED).update();
        }
        if (!keepName) {
            // zaznamy o samotnej karte osoby maju v detaile meno - aj ked bolo prilis kratke na bezpecne hladanie
            this.jdbc.sql("""
                            UPDATE audit_log SET detail = :r
                            WHERE entity = 'osoba' AND entity_id = :id AND detail IS NOT NULL AND action <> 'POTVRDENIE'""")
                    .param("r", REDACTED).param("id", String.valueOf(personId)).update();
        }
        this.jdbc.sql("SELECT set_config('fgs.audit_redaction', 'off', true)").query(String.class).single();
    }

    // ---------- vypis udajov ----------

    private record Contract(String kind, String title, BigDecimal hourlyRate, LocalDate validFrom, LocalDate validTo,
                            BigDecimal hours) {
    }

    private record Loan(String inventoryNo, String name, LocalDate lentOn, LocalDate dueOn, LocalDate returnedOn) {
    }

    /** Vypis vsetkeho, co o osobe vedieme (cl. 15 - pravo na pristup, cl. 20 - prenositelnost). */
    public Table export(long personId) {
        Person p = this.person(personId);
        String org = this.organizations.find().map(o -> o.name()).orElse("združenie");
        Table t = new Table("Údaje o osobe", "Oblasť", "Údaj", "Hodnota").portrait();
        t.subtitle(p.fullName() + " · stav k " + Table.text(LocalDate.now(this.clock)));
        t.preamble("Výpis osobných údajov, ktoré o vás vedie " + org + " v systéme FGS HQ (čl. 15 a 20 GDPR). "
                + "Účel: organizácia aktivít, dobrovoľníctvo, evidencia odmien. Opravu alebo výmaz si vyžiadajte u koordinátora.");
        t.row("Karta", "Meno", p.fullName());
        t.row("Karta", "E-mail", p.email());
        t.row("Karta", "Telefón", p.phone());
        t.row("Karta", "Organizácia / škola", p.organization());
        t.row("Karta", "Roly", String.join(", ", p.roleLabels()));
        t.row("Karta", "Maloletý", p.minor());
        t.row("Karta", "Zákonný zástupca", p.guardianName());
        t.row("Karta", "Kontakt zástupcu", p.guardianContact());
        t.row("Súhlasy", "Súhlas so spracovaním od", p.dataConsentOn());
        t.row("Súhlasy", "Súhlas dal zákonný zástupca", p.consentByGuardian());
        t.row("Súhlasy", "Súhlas s fotografiami", p.photoConsent());
        t.row("Karta", "Poznámka", p.note());
        for (PersonRepository.Participation x : this.people.participation(personId)) {
            t.row("Účasť", x.activityCode() + " " + x.activityName() + " - " + x.roleName(),
                    statusLabel(x.status()) + (x.startsAt() == null ? "" : ", " + Table.text(x.startsAt()))
                            + (x.hours() == null ? "" : ", " + Table.text(x.hours()) + " h"));
        }
        t.row("Účasť", "Dobrovoľnícke hodiny spolu", this.people.volunteerHours(personId));
        for (Contract c : this.jdbc.sql("""
                        SELECT c.kind, c.title, c.hourly_rate, c.valid_from, c.valid_to,
                               COALESCE((SELECT sum(hours) FROM work_log l WHERE l.contract_id = c.id), 0) AS hours
                        FROM work_contract c WHERE c.person_id = :id ORDER BY c.valid_from""")
                .param("id", personId).query(Contract.class).list()) {
            t.row("Zmluva", c.kind() + " " + c.title(), Table.text(c.validFrom()) + " - " + Table.text(c.validTo())
                    + ", " + Table.text(c.hourlyRate()) + " €/h, odpracované " + Table.text(c.hours()) + " h");
        }
        for (Loan l : this.jdbc.sql("""
                        SELECT a.inventory_no, a.name, x.lent_on, x.due_on, x.returned_on
                        FROM asset_loan x JOIN asset a ON a.id = x.asset_id WHERE x.person_id = :id ORDER BY x.lent_on""")
                .param("id", personId).query(Loan.class).list()) {
            t.row("Výpožička", l.inventoryNo() + " " + l.name(), Table.text(l.lentOn())
                    + (l.returnedOn() == null ? " - nevrátené" : " - vrátené " + Table.text(l.returnedOn())));
        }
        for (LinkedUser u : this.users(personId, p.email())) {
            AccessRepository.User user = this.access.user(u.id()).orElseThrow();
            t.row("Účet", "Prihlasovací e-mail", user.email());
            t.row("Účet", "Roly", String.join(", ", user.roleNames()));
            t.row("Účet", "Aktívny", user.active());
        }
        t.note("Záznamy v audit logu (kto čo zmenil) a účtovné doklady sa uchovávajú podľa zákona a nie sú súčasťou výpisu.");
        return t;
    }

    // ---------- pomocne ----------

    private Person person(long id) {
        return this.people.findById(id).orElseThrow(() -> new AccessException(List.of("Osoba neexistuje.")));
    }

    private State state(long id) {
        return this.jdbc.sql("SELECT anonymized_at, name_retained FROM person WHERE id = :id").param("id", id)
                .query(State.class).single();
    }

    private List<LinkedUser> users(long personId, String email) {
        return this.jdbc.sql("""
                        SELECT id, email, active FROM app_user
                        WHERE person_id = :id OR (:email <> '' AND email = lower(:email)) ORDER BY id""")
                .param("id", personId).param("email", email == null ? "" : email.trim()).query(LinkedUser.class).list();
    }

    private long count(String sql, long id) {
        return this.jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    /** Kratke retazce (napr. "Eva") sa v audite nehladaju - prepisali by aj cudzie texty. */
    private static void addIdent(Set<String> idents, String v) {
        if (v != null && v.trim().length() >= 5) {
            idents.add(v.trim());
        }
    }

    private static String statusLabel(String s) {
        return switch (s) {
            case "POTVRDENY" -> "potvrdený";
            case "ODMIETOL" -> "odmietol";
            case "ZUCASTNIL_SA" -> "zúčastnil sa";
            default -> "pozvaný";
        };
    }
}
