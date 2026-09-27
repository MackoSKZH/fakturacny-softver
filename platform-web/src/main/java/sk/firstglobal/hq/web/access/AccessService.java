package sk.firstglobal.hq.web.access;

import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.people.Person;
import sk.firstglobal.hq.web.people.PersonRepository;
import sk.firstglobal.hq.web.security.AppSecurityProperties;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Kto je kto a co smie. Opravnenia sa citaju z databazy pri kazdej poziadavke (AccessFilter), takze
 * odobratie roly alebo deaktivacia platia okamzite. Admini z konfiguracie (APP_EDITOR_EMAILS / lokalny ucet)
 * su nudzovy pristup - nemozno ich zamknut z aplikacie.
 */
@Service
public class AccessService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AccessRepository repo;
    private final PersonRepository people;
    private final AppSecurityProperties props;
    private final AuditLog audit;
    private final Clock clock;

    public AccessService(AccessRepository repo, PersonRepository people, AppSecurityProperties props, AuditLog audit,
                         Clock clock) {
        this.repo = repo;
        this.people = people;
        this.props = props;
        this.audit = audit;
        this.clock = clock;
    }

    /** Vysledok prihlasenia: opravnenia, alebo deaktivovany ucet. */
    public record Resolution(AccessInfo access, boolean disabled) {
    }

    public boolean isBootstrapAdmin(String email) {
        if (email == null) {
            return false;
        }
        String e = email.trim().toLowerCase(Locale.ROOT);
        return this.props.isEditor(e)
                || (this.props.mode() == AppSecurityProperties.Mode.LOCAL && e.equals(this.props.localUsername().toLowerCase(Locale.ROOT)));
    }

    /** Smie sa ucet prihlasit bez pozvanky? (zoznam v konfiguracii alebo existujuci aktivny pouzivatel) */
    public boolean mayLogIn(String email) {
        if (email == null) {
            return false;
        }
        Optional<AccessRepository.User> u = this.repo.userByEmail(email);
        if (u.isPresent()) {
            return u.get().active() || this.isBootstrapAdmin(email);
        }
        return this.isBootstrapAdmin(email) || this.props.isAllowed(email)
                || this.props.localReaders().contains(email.trim().toLowerCase(Locale.ROOT));
    }

    @Transactional
    public Resolution resolve(String email, String displayName) {
        if (email == null || email.isBlank()) {
            return new Resolution(AccessInfo.NONE, false);
        }
        String e = email.trim().toLowerCase(Locale.ROOT);
        boolean bootstrap = this.isBootstrapAdmin(e);
        AccessRepository.User user = this.repo.userByEmail(e).orElse(null);
        if (user == null && (bootstrap || this.mayLogIn(e))) {
            user = this.provision(e, displayName, bootstrap ? "ADMIN" : this.defaultRole(), "prvé prihlásenie");
        }
        if (user == null) {
            return new Resolution(new AccessInfo(null, e, Set.of(), Set.of(), false), false);
        }
        if (!user.active() && !bootstrap) {
            return new Resolution(AccessInfo.NONE, true);
        }
        List<String> granted = new ArrayList<>(this.repo.permissionsOf(user.id()));
        if (bootstrap) {
            granted.add(Permission.ADMIN.name());
        }
        this.repo.touch(user.id());
        return new Resolution(new AccessInfo(user.id(), e, Permission.expand(Permission.parse(granted)),
                this.repo.ownedProjects(user.id()), bootstrap), false);
    }

    private String defaultRole() {
        return this.props.defaultRole() == null || this.props.defaultRole().isBlank() ? "DOBROVOLNIK"
                : this.props.defaultRole().trim().toUpperCase(Locale.ROOT);
    }

    private AccessRepository.User provision(String email, String displayName, String roleCode, String why) {
        Long person = this.people.findByEmail(email).map(Person::id).orElse(null);
        long id = this.repo.insertUser(email, displayName, person, "system");
        this.repo.roleByCode(roleCode).ifPresent(r -> this.repo.addRoles(id, List.of(r.id())));
        this.audit.record("system", "VYTVORENIE", "pouzivatel", id, email + " - " + why + " (" + roleCode + ")");
        return this.repo.user(id).orElseThrow();
    }

    // ---------- sprava pouzivatelov ----------

    public long createUser(String email, String displayName, List<Long> roleIds, String actor) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        List<String> errors = new ArrayList<>();
        if (!e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            errors.add("Zadajte platný e-mail (ten, ktorým sa človek prihlasuje cez Google).");
        } else if (this.repo.userByEmail(e).isPresent()) {
            errors.add("Používateľ " + e + " už existuje.");
        }
        List<Long> roles = this.validRoles(roleIds, errors);
        if (!errors.isEmpty()) {
            throw new AccessException(errors);
        }
        Long person = this.people.findByEmail(e).map(Person::id).orElse(null);
        long id = this.repo.insertUser(e, blank(displayName), person, actor);
        this.repo.addRoles(id, roles);
        this.audit.record(actor, "VYTVORENIE", "pouzivatel", id, e + " " + this.roleCodes(roles));
        return id;
    }

    @Transactional
    public void setRoles(long userId, List<Long> roleIds, AccessInfo actor) {
        AccessRepository.User u = this.user(userId);
        List<String> errors = new ArrayList<>();
        List<Long> roles = this.validRoles(roleIds, errors);
        boolean willBeAdmin = roles.stream().anyMatch(r -> this.repo.role(r).orElseThrow().permissions()
                .contains(Permission.ADMIN.name()));
        boolean isAdmin = u.roleCodes().stream().anyMatch(c -> this.repo.roleByCode(c).orElseThrow().permissions()
                .contains(Permission.ADMIN.name()));
        if (isAdmin && !willBeAdmin && u.email().equals(actor.email())) {
            errors.add("Sebe rolu Admin zobrať nemôžete - požiadajte iného admina.");
        } else if (isAdmin && !willBeAdmin && u.active() && this.repo.activeAdmins() <= 1) {
            errors.add("Toto je posledný admin. Najprv pridajte admina niekomu inému.");
        }
        if (!errors.isEmpty()) {
            throw new AccessException(errors);
        }
        this.repo.setRoles(userId, roles);
        this.audit.record(actor.email(), "ROLY", "pouzivatel", userId, u.email() + " -> " + this.roleCodes(roles));
    }

    @Transactional
    public void setActive(long userId, boolean active, AccessInfo actor) {
        AccessRepository.User u = this.user(userId);
        if (!active && u.email().equals(actor.email())) {
            throw new AccessException(List.of("Seba deaktivovať nemôžete."));
        }
        boolean isAdmin = this.repo.permissionsOf(userId).contains(Permission.ADMIN.name());
        if (!active && isAdmin && this.repo.activeAdmins() <= 1) {
            throw new AccessException(List.of("Toto je posledný aktívny admin."));
        }
        this.repo.setActive(userId, active);
        this.audit.record(actor.email(), active ? "AKTIVACIA" : "DEAKTIVACIA", "pouzivatel", userId, u.email());
    }

    public void addOwner(long userId, long projectId, String actor) {
        AccessRepository.User u = this.user(userId);
        if (!u.active()) {
            throw new AccessException(List.of("Deaktivovaný používateľ nemôže vlastniť aktivitu."));
        }
        if (this.repo.addOwner(userId, projectId, actor)) {
            this.audit.record(actor, "VLASTNIK", "aktivita", projectId, "pridaný " + u.email());
        }
    }

    public void removeOwner(long userId, long projectId, String actor) {
        if (this.repo.removeOwner(userId, projectId) == 1) {
            this.audit.record(actor, "VLASTNIK", "aktivita", projectId, "odobratý používateľ " + userId);
        }
    }

    public AccessRepository.User user(long id) {
        return this.repo.user(id).orElseThrow(() -> new AccessException(List.of("Používateľ neexistuje.")));
    }

    // ---------- roly ----------

    public long createRole(String name, String description, List<String> permissions, String personRole, String actor) {
        List<String> errors = new ArrayList<>();
        String n = blank(name);
        String code = n == null ? "" : code(n);
        if (n == null) {
            errors.add("Názov roly je povinný.");
        } else if (code.length() < 2 || this.repo.roleByCode(code).isPresent()) {
            errors.add("Rola s podobným názvom už existuje.");
        }
        List<String> perms = this.validPermissions(permissions, errors);
        if (!errors.isEmpty()) {
            throw new AccessException(errors);
        }
        long id = this.repo.insertRole(code, n, blank(description), perms, blank(personRole));
        this.audit.record(actor, "VYTVORENIE", "rola", id, n + " " + perms);
        return id;
    }

    public void updateRole(long id, String name, String description, List<String> permissions, String personRole,
                           String actor) {
        AccessRepository.Role r = this.repo.role(id).orElseThrow(() -> new AccessException(List.of("Rola neexistuje.")));
        List<String> errors = new ArrayList<>();
        if (blank(name) == null) {
            errors.add("Názov roly je povinný.");
        }
        List<String> perms = this.validPermissions(permissions, errors);
        if ("ADMIN".equals(r.code()) && !perms.contains(Permission.ADMIN.name())) {
            errors.add("Rola Admin musí mať oprávnenie Admin.");
        }
        if (!errors.isEmpty()) {
            throw new AccessException(errors);
        }
        this.repo.updateRole(id, name.trim(), blank(description), perms, blank(personRole));
        this.audit.record(actor, "ZMENA", "rola", id, name.trim() + " " + perms);
    }

    public void deleteRole(long id, String actor) {
        AccessRepository.Role r = this.repo.role(id).orElseThrow(() -> new AccessException(List.of("Rola neexistuje.")));
        if (r.system()) {
            throw new AccessException(List.of("Základné roly sa nemažú - môžete im upraviť oprávnenia."));
        }
        if (this.repo.deleteRole(id) == 0) {
            throw new AccessException(List.of("Rolu má ešte niekto pridelenú (aj neaktívny) - najprv ju odoberte."));
        }
        this.audit.record(actor, "ZMAZANIE", "rola", id, r.name());
    }

    // ---------- pozvanky ----------

    public record CreatedInvite(long id, String token) {
    }

    public CreatedInvite createInvite(String email, List<Long> roleIds, Long projectId, String note, Integer days,
                                      Integer maxUses, String actor) {
        List<String> errors = new ArrayList<>();
        List<Long> roles = this.validRoles(roleIds, errors);
        if (roles.isEmpty() && projectId == null) {
            errors.add("Pozvánka musí dať aspoň jednu rolu alebo vlastníctvo aktivity.");
        }
        return this.create(email, roles, projectId, null, note, days, maxUses, null, errors, actor);
    }

    /**
     * Pozvanka na smenu aktivity - smie ju vytvorit aj jej vlastnik (projektovy manazer). Moze dat len rolu, ktora
     * neotvara financie, ludi ani spravu, a ktoru ma sam; vlastnictvo nikdy. Plati najdlhsie do zaciatku smeny.
     */
    public CreatedInvite createShiftInvite(long projectId, Long shiftId, String email, Long roleId, String note,
                                           Integer days, Integer maxUses, AccessInfo actor) {
        List<String> errors = new ArrayList<>();
        if (!actor.canEditActivity(projectId)) {
            throw new AccessException(List.of("Pozývať na túto aktivitu môže len jej vlastník alebo koordinátor."));
        }
        AccessRepository.Shift shift = shiftId == null ? null
                : this.repo.shift(shiftId).filter(x -> x.projectId() == projectId).orElse(null);
        if (shift == null) {
            throw new AccessException(List.of("Vyberte rolu/smenu tejto aktivity, na ktorú pozývate."));
        }
        AccessRepository.Role role = (roleId == null ? this.repo.roleByCode("DOBROVOLNIK") : this.repo.role(roleId))
                .orElse(null);
        if (role == null) {
            errors.add("Neznáma rola.");
        } else if (role.isSensitive() || !actor.permissions().containsAll(role.permissionList())) {
            errors.add("Z aktivity sa dá pozvať len s rolou na čítanie (napr. Dobrovoľník, Mentor). "
                    + "Iné roly dáva admin v Správe.");
        }
        OffsetDateTime deadline = shift.startsAt() != null ? shift.startsAt().atZone(this.clock.getZone()).toOffsetDateTime()
                : shift.activityEndsOn() != null ? shift.activityEndsOn().plusDays(1).atStartOfDay(this.clock.getZone()).toOffsetDateTime()
                : null;
        if ("ZRUSENA".equals(shift.activityStatus()) || "UKONCENA".equals(shift.activityStatus())) {
            errors.add("Aktivita je ukončená alebo zrušená - pozvánka by nemala zmysel.");
        } else if (deadline != null && !deadline.isAfter(OffsetDateTime.now(this.clock))) {
            errors.add("Smena „" + shift.name() + "“ už začala.");
        }
        return this.create(email, role == null ? List.of() : List.of(role.id()), null, shift.id(), note, days, maxUses,
                deadline, errors, actor.email());
    }

    private CreatedInvite create(String email, List<Long> roles, Long projectId, Long shiftId, String note, Integer days,
                                 Integer maxUses, OffsetDateTime deadline, List<String> errors, String actor) {
        String e = blank(email) == null ? null : email.trim().toLowerCase(Locale.ROOT);
        if (e != null && !e.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            errors.add("E-mail pozvaného nie je platný.");
        }
        boolean sensitive = roles.stream().anyMatch(r -> this.repo.role(r).orElseThrow().isSensitive()) || projectId != null;
        if (e == null && sensitive) {
            errors.add("Otvorený odkaz (bez e-mailu) môže dať len rolu na čítanie aktivít. Na financie, ľudí, "
                    + "admina alebo vlastníctvo aktivity zadajte e-mail - ak odkaz niekto prepošle, nič nezíska.");
        }
        int d = days == null ? 7 : days;
        if (d < 1 || d > 30) {
            errors.add("Platnosť pozvánky je 1 až 30 dní.");
        }
        int uses = e != null ? 1 : maxUses == null ? 1 : maxUses;
        if (uses < 1 || uses > 200) {
            errors.add("Počet použití je 1 až 200.");
        }
        if (!errors.isEmpty()) {
            throw new AccessException(errors);
        }
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        OffsetDateTime expires = OffsetDateTime.now(this.clock).plusDays(d);
        if (deadline != null && deadline.isBefore(expires)) {
            expires = deadline;
        }
        long id = this.repo.insertInvite(hash(token), e, roles, projectId, shiftId, blank(note), uses, expires, actor);
        this.audit.record(actor, "POZVANKA", "pozvanka", id, (e == null ? "otvorený odkaz x" + uses : e) + " "
                + this.roleCodes(roles) + (projectId == null ? "" : " vlastník aktivity " + projectId)
                + (shiftId == null ? "" : " smena " + shiftId));
        return new CreatedInvite(id, token);
    }

    public Optional<AccessRepository.Invite> invite(String token) {
        return token == null || !token.matches("[A-Za-z0-9_-]{43}") ? Optional.empty() : this.repo.inviteByHash(hash(token));
    }

    /** Smie sa s touto pozvankou prihlasit tento e-mail? (Google prihlasenie pred prijatim) */
    public boolean inviteAllows(String token, String email) {
        return this.invite(token).filter(i -> i.isUsable(OffsetDateTime.now(this.clock)))
                .filter(i -> i.email() == null || i.email().equalsIgnoreCase(email == null ? "" : email.trim()))
                .isPresent();
    }

    /** Co sa stalo pri prijati - pre privitaciu hlasku. shiftStatus je null, ak pozvanka nebola na smenu. */
    public record Accepted(String shiftName, String shiftStatus) {
        public String message() {
            if (this.shiftStatus == null) {
                return "Vitajte! Pozvánka je prijatá a prístup je nastavený.";
            }
            if ("UZ_V_TIME".equals(this.shiftStatus)) {
                return "Vitajte! Na „" + this.shiftName + "“ ste už zapísaný.";
            }
            return "POTVRDENY".equals(this.shiftStatus)
                    ? "Vitajte! Ste zapísaný na „" + this.shiftName + "“. Program nájdete v Mojom programe."
                    : "Vitajte! „" + this.shiftName + "“ je už obsadená - ste na zozname a koordinátor sa vám ozve.";
        }
    }

    @Transactional
    public Accepted accept(String token, String email, String displayName) {
        AccessRepository.Invite i = this.invite(token)
                .orElseThrow(() -> new AccessException(List.of("Pozvánka neexistuje.")));
        String e = email.trim().toLowerCase(Locale.ROOT);
        OffsetDateTime now = OffsetDateTime.now(this.clock);
        if (!i.isUsable(now)) {
            throw new AccessException(List.of("Pozvánka je " + i.state(now) + ". Požiadajte o novú."));
        }
        if (i.email() != null && !i.email().equals(e)) {
            throw new AccessException(List.of("Pozvánka je pre " + mask(i.email()) + ", ste prihlásený ako " + e + "."));
        }
        AccessRepository.User existing = this.repo.userByEmail(e).orElse(null);
        if (existing != null && !existing.active()) {
            throw new AccessException(List.of("Váš účet je deaktivovaný - kontaktujte admina."));
        }
        long userId = existing != null ? existing.id() : this.repo.insertUser(e, blank(displayName), null, "pozvánka " + i.id());
        if (!this.repo.recordUse(i.id(), userId)) {
            return new Accepted(null, null);
        }
        if (!this.repo.consume(i.id(), now)) {
            throw new AccessException(List.of("Pozvánka sa medzitým minula."));
        }
        this.repo.addRoles(userId, i.roleIds());
        if (i.projectId() != null) {
            this.repo.addOwner(userId, i.projectId(), "pozvánka " + i.id());
        }
        long personId = this.linkPerson(userId, e, displayName, i.roleIds());
        String shiftStatus = null;
        if (i.shiftId() != null) {
            String joined = this.repo.joinShift(i.shiftId(), personId);
            shiftStatus = joined == null ? "UZ_V_TIME" : joined;
            if (joined != null) {
                this.audit.record(e, "PRIHLASENIE", "aktivita", i.shiftProjectId(), i.shiftName() + " - "
                        + ("POTVRDENY".equals(joined) ? "potvrdený" : "nad kapacitu, čaká"));
            }
        }
        this.audit.record(e, "PRIJATIE", "pozvanka", i.id(), this.roleCodes(i.roleIds()));
        return new Accepted(i.shiftName(), shiftStatus);
    }

    /** Kazdy pozvany ma kartu v adresari ludi - aby sa dal priradit do timu a aby sa videlo, ze chyba suhlas. */
    private long linkPerson(long userId, String email, String displayName, List<Long> roleIds) {
        Set<String> personRoles = new LinkedHashSet<>();
        roleIds.forEach(r -> this.repo.role(r).map(AccessRepository.Role::personRole).ifPresent(personRoles::add));
        Person p = this.people.findByEmail(email).orElse(null);
        if (p == null) {
            String name = blank(displayName) == null ? email : displayName.trim();
            long id = this.people.insert(new Person(null, name, email, null, null, List.copyOf(personRoles), false, null,
                    null, null, false, false, "Pridaný cez pozvánku - doplňte súhlas so spracovaním údajov."));
            this.repo.setPerson(userId, id);
            return id;
        }
        this.repo.setPerson(userId, p.id());
        if (!p.roles().containsAll(personRoles)) {
            Set<String> merged = new LinkedHashSet<>(p.roles());
            merged.addAll(personRoles);
            this.people.update(p.id(), new Person(p.id(), p.fullName(), p.email(), p.phone(), p.organization(),
                    List.copyOf(merged), p.minor(), p.guardianName(), p.guardianContact(), p.dataConsentOn(),
                    p.consentByGuardian(), p.photoConsent(), p.note()));
        }
        return p.id();
    }

    public void revokeInvite(long id, String actor) {
        this.repo.revoke(id);
        this.audit.record(actor, "ZRUSENIE", "pozvanka", id, null);
    }

    // ---------- pomocne ----------

    private List<Long> validRoles(List<Long> roleIds, List<String> errors) {
        List<Long> out = new ArrayList<>();
        for (Long r : roleIds == null ? List.<Long>of() : roleIds) {
            if (r == null || this.repo.role(r).isEmpty()) {
                errors.add("Neznáma rola.");
            } else if (!out.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    private List<String> validPermissions(List<String> permissions, List<String> errors) {
        List<String> in = permissions == null ? List.of() : permissions;
        List<String> known = Permission.parse(in).stream().map(Enum::name).distinct().toList();
        if (known.size() != in.stream().distinct().count()) {
            errors.add("Neznáme oprávnenie.");
        }
        return known;
    }

    private String roleCodes(List<Long> roleIds) {
        return roleIds.stream().map(r -> this.repo.role(r).map(AccessRepository.Role::code).orElse("?")).toList().toString();
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String code(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    /** j***@gmail.com - pozvanku neuvidi cudzi e-mail cely. */
    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? email : email.charAt(0) + "***" + email.substring(at);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
