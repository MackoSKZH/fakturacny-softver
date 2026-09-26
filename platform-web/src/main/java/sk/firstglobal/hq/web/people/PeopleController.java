package sk.firstglobal.hq.web.people;

import sk.firstglobal.hq.web.asset.AssetRepository;
import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.schedule.ScheduleService;
import sk.firstglobal.hq.web.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Adresar ludi. Obsahuje kontakty na maloletych a ich zastupcov - pristup len pre editorov (SecurityConfig). */
@Controller
@RequestMapping("/ludia")
class PeopleController {
    private final PersonRepository people;
    private final ScheduleService schedule;
    private final AssetRepository assets;
    private final VolunteerConfirmation confirmations;
    private final AuditLog audit;
    private final Clock clock;
    private final PersonPrivacy privacy;
    private final VolunteerContract contracts;

    PeopleController(PersonRepository people, ScheduleService schedule, AssetRepository assets,
                     VolunteerConfirmation confirmations, AuditLog audit, Clock clock, PersonPrivacy privacy,
                     VolunteerContract contracts) {
        this.contracts = contracts;
        this.privacy = privacy;
        this.confirmations = confirmations;
        this.people = people;
        this.schedule = schedule;
        this.assets = assets;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping
    String list(@RequestParam(required = false) String role, @RequestParam(required = false) String q, Model model) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Person> all = this.people.findAll().stream()
                .filter(p -> role == null || role.isBlank() || p.roles().contains(role))
                .filter(p -> query.isEmpty() || (p.fullName() + " " + nz(p.email()) + " " + nz(p.organization()))
                        .toLowerCase(Locale.ROOT).contains(query))
                .toList();
        model.addAttribute("people", all);
        model.addAttribute("roles", PersonRole.values());
        model.addAttribute("role", role);
        model.addAttribute("q", q);
        model.addAttribute("p", empty());
        model.addAttribute("errors", List.of());
        return "people/list";
    }

    @PostMapping
    String create(@RequestParam String fullName, @RequestParam(required = false) String email,
                  @RequestParam(required = false) String phone, @RequestParam(required = false) String organization,
                  @RequestParam(required = false) List<String> roles, @RequestParam(defaultValue = "false") boolean minor,
                  @RequestParam(required = false) String guardianName, @RequestParam(required = false) String guardianContact,
                  @RequestParam(required = false) LocalDate dataConsentOn,
                  @RequestParam(defaultValue = "false") boolean consentByGuardian,
                  @RequestParam(defaultValue = "false") boolean photoConsent, @RequestParam(required = false) String note,
                  Authentication auth, Model model, RedirectAttributes redirect) {
        Person p = new Person(null, t(fullName), t(email), t(phone), t(organization), clean(roles), minor, t(guardianName),
                t(guardianContact), dataConsentOn, consentByGuardian, photoConsent, t(note));
        List<String> errors = this.validate(p, null);
        if (!errors.isEmpty()) {
            model.addAttribute("people", this.people.findAll());
            model.addAttribute("roles", PersonRole.values());
            model.addAttribute("p", p);
            model.addAttribute("errors", errors);
            return "people/list";
        }
        long id = this.people.insert(p);
        this.audit.record(CurrentUser.name(auth), "VYTVORENIE", "osoba", id, p.fullName());
        redirect.addFlashAttribute("message", p.fullName() + " je pridaný(á).");
        return "redirect:/ludia/" + id;
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Model model) {
        Person p = this.people.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("p", p);
        model.addAttribute("roles", PersonRole.values());
        List<PersonRepository.Participation> participation = this.people.participation(id);
        model.addAttribute("participation", participation);
        java.util.Map<Long, String> acts = new java.util.LinkedHashMap<>();
        participation.forEach(x -> acts.putIfAbsent(x.projectId(), x.activityCode() + " " + x.activityName()));
        model.addAttribute("contractActivities", acts);
        model.addAttribute("hours", this.people.volunteerHours(id));
        this.schedule.calendarToken(id).ifPresent(t -> model.addAllAttributes(ScheduleService.feedUrls(t)));
        model.addAttribute("lent", this.assets.lentTo(id));
        model.addAttribute("confirmationYears", this.confirmations.years(id));
        model.addAttribute("today", LocalDate.now(this.clock));
        model.addAttribute("privacy", this.privacy.assess(id));
        return "people/detail";
    }

    /** Zmluva o dobrovolnickej cinnosti - na aktivitu alebo na obdobie (predvolene do konca roka). */
    @GetMapping("/{id}/zmluva.pdf")
    ResponseEntity<byte[]> contract(@PathVariable long id, @RequestParam(required = false) Long aktivita,
                                    @RequestParam(required = false) LocalDate od, @RequestParam(required = false) LocalDate
                                            doDna, Authentication auth) {
        Person p = this.people.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        byte[] pdf;
        try {
            pdf = this.contracts.pdf(id, aktivita, od, doDna);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        this.audit.record(CurrentUser.name(auth), "ZMLUVA", "osoba", id, aktivita == null ? "dobrovoľnícka zmluva"
                : "dobrovoľnícka zmluva, aktivita " + aktivita);
        return VolunteerConfirmation.response(pdf, "zmluva-dobrovolnik-" + VolunteerConfirmation.fileSafe(p.fullName()));
    }

    /** Vypis vsetkych udajov o osobe - odpoved na ziadost podla cl. 15 GDPR. */
    @GetMapping("/{id}/udaje.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> personalData(@PathVariable long id, @PathVariable String format, Authentication auth) {
        this.people.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        ResponseEntity<byte[]> r = this.privacy.export(id).response(format, "udaje-osoby-" + id);
        this.audit.record(CurrentUser.name(auth), "EXPORT", "osoba", id, "výpis údajov (" + format + ")");
        return r;
    }

    @PostMapping("/{id}/anonymizovat")
    String anonymize(@PathVariable long id, @RequestParam(required = false) String confirmName,
                     jakarta.servlet.http.HttpServletRequest request, RedirectAttributes redirect) {
        this.people.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            this.privacy.anonymize(id, confirmName, sk.firstglobal.hq.web.access.AccessFilter.of(request));
            redirect.addFlashAttribute("message", "Osoba je anonymizovaná. Údaje sa nedajú obnoviť (okrem záloh - "
                    + "tie sa prepíšu podľa rotácie záloh).");
        } catch (sk.firstglobal.hq.web.access.AccessException e) {
            redirect.addFlashAttribute("errors", e.errors());
        }
        return "redirect:/ludia/" + id;
    }

    @PostMapping("/{id}")
    String update(@PathVariable long id, @RequestParam String fullName, @RequestParam(required = false) String email,
                  @RequestParam(required = false) String phone, @RequestParam(required = false) String organization,
                  @RequestParam(required = false) List<String> roles, @RequestParam(defaultValue = "false") boolean minor,
                  @RequestParam(required = false) String guardianName, @RequestParam(required = false) String guardianContact,
                  @RequestParam(required = false) LocalDate dataConsentOn,
                  @RequestParam(defaultValue = "false") boolean consentByGuardian,
                  @RequestParam(defaultValue = "false") boolean photoConsent, @RequestParam(required = false) String note,
                  Authentication auth, RedirectAttributes redirect) {
        this.people.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Person p = new Person(id, t(fullName), t(email), t(phone), t(organization), clean(roles), minor, t(guardianName),
                t(guardianContact), dataConsentOn, consentByGuardian, photoConsent, t(note));
        List<String> errors = this.validate(p, id);
        if (!errors.isEmpty()) {
            redirect.addFlashAttribute("errors", errors);
            return "redirect:/ludia/" + id;
        }
        this.people.update(id, p);
        this.audit.record(CurrentUser.name(auth), "ZMENA", "osoba", id, p.fullName());
        redirect.addFlashAttribute("message", "Údaje sú uložené.");
        return "redirect:/ludia/" + id;
    }

    private List<String> validate(Person p, Long id) {
        List<String> errors = new ArrayList<>();
        if (p.fullName() == null) {
            errors.add("Meno je povinné.");
        }
        if (p.email() != null && !p.email().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            errors.add("E-mail nie je platný.");
        } else if (p.email() != null && this.people.emailTaken(p.email(), id)) {
            errors.add("Osoba s týmto e-mailom už existuje.");
        }
        if (p.minor() && (p.guardianName() == null || p.guardianContact() == null)) {
            errors.add("Pri maloletom vyplňte meno a kontakt zákonného zástupcu.");
        }
        if (p.consentByGuardian() && !p.minor()) {
            errors.add("Súhlas zákonného zástupcu sa týka len maloletých.");
        }
        if (p.dataConsentOn() != null && p.dataConsentOn().isAfter(LocalDate.now(this.clock))) {
            errors.add("Dátum súhlasu nemôže byť v budúcnosti.");
        }
        if (p.photoConsent() && p.dataConsentOn() == null) {
            errors.add("Súhlas s fotografiami bez súhlasu so spracovaním údajov nedáva zmysel - doplňte dátum súhlasu.");
        }
        return errors;
    }

    private static Person empty() {
        return new Person(null, "", "", "", "", List.of(), false, "", "", null, false, false, "");
    }

    private static List<String> clean(List<String> roles) {
        return roles == null ? List.of() : roles.stream().filter(PersonRole.codes()::contains).distinct().toList();
    }

    private static String t(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Potvrdenie o dobrovolnickej cinnosti za rok (alebo za vsetky roky). */
    @GetMapping("/{id}/potvrdenie.pdf")
    ResponseEntity<byte[]> confirmation(@PathVariable long id, @RequestParam(required = false) Integer rok,
                                        Authentication auth) {
        Person p = this.people.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        byte[] pdf;
        try {
            pdf = this.confirmations.pdf(id, rok, null);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        this.audit.record(CurrentUser.name(auth), "POTVRDENIE", "osoba", id, rok == null ? "všetky roky" : "rok " + rok);
        return VolunteerConfirmation.response(pdf, "potvrdenie-" + VolunteerConfirmation.fileSafe(p.fullName()) + (rok == null ? "" : "-" + rok));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
