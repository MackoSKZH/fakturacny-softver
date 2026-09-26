package com.fakturacnysoftver.web.people;

import com.fakturacnysoftver.web.audit.AuditLog;
import com.fakturacnysoftver.web.schedule.ScheduleService;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.HttpStatus;
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
    private final AuditLog audit;
    private final Clock clock;

    PeopleController(PersonRepository people, ScheduleService schedule, AuditLog audit, Clock clock) {
        this.people = people;
        this.schedule = schedule;
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
        model.addAttribute("participation", this.people.participation(id));
        model.addAttribute("hours", this.people.volunteerHours(id));
        this.schedule.calendarToken(id).ifPresent(t -> model.addAllAttributes(ScheduleService.feedUrls(t)));
        return "people/detail";
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

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
