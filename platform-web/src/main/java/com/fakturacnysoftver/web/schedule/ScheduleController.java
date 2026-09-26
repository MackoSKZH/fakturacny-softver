package com.fakturacnysoftver.web.schedule;

import com.fakturacnysoftver.web.activity.Activity;
import com.fakturacnysoftver.web.activity.ActivityRepository;
import com.fakturacnysoftver.web.asset.AssetRepository;
import com.fakturacnysoftver.web.audit.AuditLog;
import com.fakturacnysoftver.web.people.Person;
import com.fakturacnysoftver.web.people.PersonRepository;
import com.fakturacnysoftver.web.people.VolunteerConfirmation;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Harmonogram aktivity (program + smeny), osobny program a odber kalendara.
 * /aktivity/{id}/harmonogram - vsetci clenovia citaju, editori upravuju.
 * /moj-program - kazdy prihlaseny vidi svoj program (prepojenie cez e-mail).
 * /kalendar/{token}.ics - odber pre kalendarove aplikacie bez prihlasenia, chraneny tajnym tokenom.
 */
@Controller
class ScheduleController {
    private final ScheduleService service;
    private final ScheduleRepository repo;
    private final ActivityRepository activities;
    private final PersonRepository people;
    private final AuditLog audit;
    private final AssetRepository assets;
    private final VolunteerConfirmation confirmations;
    private final Clock clock;

    ScheduleController(ScheduleService service, ScheduleRepository repo, ActivityRepository activities,
                       PersonRepository people, AuditLog audit, AssetRepository assets,
                       VolunteerConfirmation confirmations, Clock clock) {
        this.assets = assets;
        this.confirmations = confirmations;
        this.service = service;
        this.repo = repo;
        this.activities = activities;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
    }

    // ---------- harmonogram aktivity ----------

    @GetMapping("/aktivity/{id}/harmonogram")
    String activity(@PathVariable long id, @RequestParam(required = false) String pre,
                    @RequestParam(required = false) Long owner, @RequestParam(required = false) Long osoba,
                    Model model) {
        Activity a = this.activity(id);
        ScheduleService.Filter f = new ScheduleService.Filter(pre, owner, osoba);
        List<ScheduleEntry> entries = this.service.activity(id, f);
        Map<LocalDate, List<ScheduleEntry>> byDay = new LinkedHashMap<>();
        entries.forEach(e -> byDay.computeIfAbsent(e.day(), k -> new java.util.ArrayList<>()).add(e));
        String query = query(f);
        model.addAttribute("a", a);
        model.addAttribute("days", byDay);
        model.addAttribute("count", entries.size());
        model.addAttribute("filter", f);
        model.addAttribute("audiences", this.repo.audiences(id));
        model.addAttribute("members", this.repo.members(id));
        model.addAttribute("people", this.people.findAll());
        model.addAttribute("everyone", ScheduleEntry.EVERYONE);
        model.addAttribute("exportBase", "/aktivity/" + id + "/harmonogram");
        model.addAttribute("exportQuery", query);
        model.addAttribute("defaultDay", a.startsOn() == null ? LocalDate.now(this.clock) : a.startsOn());
        return "schedule/activity";
    }

    @GetMapping("/aktivity/{id}/harmonogram.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> activityExport(@PathVariable long id, @PathVariable String format,
                                          @RequestParam(required = false) String pre,
                                          @RequestParam(required = false) Long owner,
                                          @RequestParam(required = false) Long osoba) {
        Activity a = this.activity(id);
        ScheduleService.Filter f = new ScheduleService.Filter(pre, owner, osoba);
        return this.service.table("Harmonogram - " + a.name(), this.service.activity(id, f))
                .subtitle(this.describe(a, f))
                .response(format, fileName("harmonogram", a, f));
    }

    @GetMapping("/aktivity/{id}/harmonogram.ics")
    ResponseEntity<byte[]> activityIcs(@PathVariable long id, @RequestParam(required = false) String pre,
                                       @RequestParam(required = false) Long owner,
                                       @RequestParam(required = false) Long osoba) {
        Activity a = this.activity(id);
        ScheduleService.Filter f = new ScheduleService.Filter(pre, owner, osoba);
        String name = a.name() + (f.audience() == null ? "" : " - " + f.audience());
        return ics(name, this.service.activity(id, f), fileName("harmonogram", a, f) + ".ics", true);
    }

    /** Rozpis pre kazdeho cloveka zvlast, s e-mailom - len editori (SecurityConfig). */
    @GetMapping("/aktivity/{id}/rozpis.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> perPerson(@PathVariable long id, @PathVariable String format) {
        Activity a = this.activity(id);
        return this.service.perPerson("Rozpis pre ľudí - " + a.name(), id).response(format, "rozpis-" + safe(a.code()));
    }

    @PostMapping("/aktivity/{id}/program")
    String add(@PathVariable long id, @RequestParam(required = false) String startsAt,
               @RequestParam(required = false) String endsAt, @RequestParam(required = false) String title,
               @RequestParam(required = false) String location, @RequestParam(required = false) Long ownerId,
               @RequestParam(required = false) String tags, @RequestParam(required = false) String note,
               Authentication auth, RedirectAttributes redirect) {
        this.activity(id);
        try {
            long itemId = this.service.add(id, startsAt, endsAt, title, location, ownerId, tags, note);
            this.audit.record(CurrentUser.name(auth), "VYTVORENIE", "program", itemId, title);
            redirect.addFlashAttribute("message", "Bod programu je pridaný.");
        } catch (ScheduleException e) {
            redirect.addFlashAttribute("errors", e.errors());
        }
        return "redirect:/aktivity/" + id + "/harmonogram";
    }

    @PostMapping("/aktivity/{id}/program/{itemId}")
    String update(@PathVariable long id, @PathVariable long itemId, @RequestParam(required = false) String startsAt,
                  @RequestParam(required = false) String endsAt, @RequestParam(required = false) String title,
                  @RequestParam(required = false) String location, @RequestParam(required = false) Long ownerId,
                  @RequestParam(required = false) String tags, @RequestParam(required = false) String note,
                  Authentication auth, RedirectAttributes redirect) {
        this.activity(id);
        try {
            this.service.update(id, itemId, startsAt, endsAt, title, location, ownerId, tags, note);
            this.audit.record(CurrentUser.name(auth), "ZMENA", "program", itemId, title);
            redirect.addFlashAttribute("message", "Bod programu je uložený.");
        } catch (ScheduleException e) {
            redirect.addFlashAttribute("errors", e.errors());
        }
        return "redirect:/aktivity/" + id + "/harmonogram";
    }

    @PostMapping("/aktivity/{id}/program/{itemId}/zmazat")
    String delete(@PathVariable long id, @PathVariable long itemId, Authentication auth, RedirectAttributes redirect) {
        if (this.service.delete(id, itemId) == 1) {
            this.audit.record(CurrentUser.name(auth), "ZMAZANIE", "program", itemId, null);
            redirect.addFlashAttribute("message", "Bod programu je zmazaný.");
        }
        return "redirect:/aktivity/" + id + "/harmonogram";
    }

    // ---------- program osoby (editori) ----------

    @GetMapping("/ludia/{id}/program.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> personExport(@PathVariable long id, @PathVariable String format) {
        Person p = this.person(id);
        return this.service.table("Program - " + p.fullName(), this.repo.ofPerson(id, null))
                .response(format, "program-" + safe(p.fullName()));
    }

    @GetMapping("/ludia/{id}/program.ics")
    ResponseEntity<byte[]> personIcs(@PathVariable long id) {
        Person p = this.person(id);
        return ics("FGS - " + p.fullName(), this.repo.ofPerson(id, null), "program-" + safe(p.fullName()) + ".ics", true);
    }

    @PostMapping("/ludia/{id}/kalendar")
    String renewFor(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        this.person(id);
        this.service.renewCalendarToken(id);
        this.audit.record(CurrentUser.name(auth), "KALENDAR", "osoba", id, "nový odkaz");
        redirect.addFlashAttribute("message", "Nový odkaz na kalendár je vytvorený. Starý prestal fungovať.");
        return "redirect:/ludia/" + id + "#kalendar";
    }

    @PostMapping("/ludia/{id}/kalendar/zrusit")
    String revokeFor(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        this.person(id);
        this.service.revokeCalendarToken(id);
        this.audit.record(CurrentUser.name(auth), "KALENDAR", "osoba", id, "zrušený odkaz");
        redirect.addFlashAttribute("message", "Odkaz na kalendár je zrušený.");
        return "redirect:/ludia/" + id + "#kalendar";
    }

    // ---------- moj program (kazdy prihlaseny) ----------

    @GetMapping("/moj-program")
    String mine(Authentication auth, Model model) {
        Person me = this.people.findByEmail(CurrentUser.name(auth)).orElse(null);
        model.addAttribute("me", me);
        if (me != null) {
            List<ScheduleEntry> entries = this.repo.ofPerson(me.id(), null);
            LocalDate today = LocalDate.now(this.clock);
            Map<LocalDate, List<ScheduleEntry>> byDay = new LinkedHashMap<>();
            entries.stream().filter(e -> !e.day().isBefore(today))
                    .forEach(e -> byDay.computeIfAbsent(e.day(), k -> new java.util.ArrayList<>()).add(e));
            model.addAttribute("days", byDay);
            model.addAttribute("pastCount", entries.stream().filter(e -> e.day().isBefore(today)).count());
            this.service.calendarToken(me.id()).ifPresent(t -> model.addAllAttributes(ScheduleService.feedUrls(t)));
            model.addAttribute("lent", this.assets.lentTo(me.id()));
            model.addAttribute("confirmationYears", this.confirmations.years(me.id()));
            model.addAttribute("today", today);
        }
        return "schedule/mine";
    }

    @GetMapping("/moj-program/program.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> mineExport(@PathVariable String format, Authentication auth) {
        Person me = this.me(auth);
        return this.service.table("Môj program - " + me.fullName(), this.repo.ofPerson(me.id(), null))
                .response(format, "moj-program");
    }

    @GetMapping("/moj-program/program.ics")
    ResponseEntity<byte[]> mineIcs(Authentication auth) {
        Person me = this.me(auth);
        return ics("FGS - " + me.fullName(), this.repo.ofPerson(me.id(), null), "moj-program.ics", true);
    }

    /** Dobrovolnik si potvrdenie stiahne sam - podpis a peciatku doplni statutar. */
    @GetMapping("/moj-program/potvrdenie.pdf")
    ResponseEntity<byte[]> myConfirmation(@RequestParam(required = false) Integer rok, Authentication auth) {
        Person me = this.me(auth);
        try {
            return VolunteerConfirmation.response(this.confirmations.pdf(me.id(), rok, null),
                    "potvrdenie-dobrovolnictvo" + (rok == null ? "" : "-" + rok));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/moj-program/kalendar")
    String renewMine(Authentication auth, RedirectAttributes redirect) {
        Person me = this.me(auth);
        this.service.renewCalendarToken(me.id());
        this.audit.record(CurrentUser.name(auth), "KALENDAR", "osoba", me.id(), "nový odkaz");
        redirect.addFlashAttribute("message", "Odkaz je pripravený. Ak ste mali starší, prestal fungovať.");
        return "redirect:/moj-program";
    }

    @PostMapping("/moj-program/kalendar/zrusit")
    String revokeMine(Authentication auth, RedirectAttributes redirect) {
        Person me = this.me(auth);
        this.service.revokeCalendarToken(me.id());
        this.audit.record(CurrentUser.name(auth), "KALENDAR", "osoba", me.id(), "zrušený odkaz");
        redirect.addFlashAttribute("message", "Odkaz na kalendár je zrušený.");
        return "redirect:/moj-program";
    }

    // ---------- odber bez prihlasenia ----------

    @GetMapping("/kalendar/{token:[A-Za-z0-9_-]{43}}.ics")
    ResponseEntity<byte[]> feed(@PathVariable String token) {
        ScheduleRepository.FeedOwner owner = this.repo.byCalendarToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ics("FGS - " + owner.fullName(), this.repo.ofPerson(owner.id(), null), "fgs.ics", false);
    }

    private ResponseEntity<byte[]> ics(String name, List<ScheduleEntry> entries, String filename, boolean download) {
        byte[] body = Ics.calendar(name, entries, this.clock.instant()).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, (download ? "attachment" : "inline") + "; filename=\"" + filename + "\"")
                .header("X-Robots-Tag", "noindex")
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate())
                .contentType(new MediaType("text", "calendar", StandardCharsets.UTF_8))
                .body(body);
    }

    private Activity activity(long id) {
        return this.activities.findById(id, LocalDate.now(this.clock))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Person person(long id) {
        return this.people.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Person me(Authentication auth) {
        return this.people.findByEmail(CurrentUser.name(auth))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Účet nie je prepojený s osobou v adresári."));
    }

    /** Popis filtra do podnadpisu exportu. */
    private String describe(Activity a, ScheduleService.Filter f) {
        List<String> parts = new java.util.ArrayList<>();
        if (a.location() != null) {
            parts.add(a.location());
        }
        if (f.audience() != null) {
            parts.add("pre: " + f.audience());
        }
        if (f.personId() != null) {
            this.people.findById(f.personId()).ifPresent(p -> parts.add("osoba: " + p.fullName()));
        }
        if (f.ownerId() != null) {
            this.people.findById(f.ownerId()).ifPresent(p -> parts.add("zodpovedá: " + p.fullName()));
        }
        return String.join(" · ", parts);
    }

    private static String query(ScheduleService.Filter f) {
        if (f.isEmpty()) {
            return "";
        }
        return UriComponentsBuilder.newInstance()
                .queryParamIfPresent("pre", java.util.Optional.ofNullable(f.audience()))
                .queryParamIfPresent("owner", java.util.Optional.ofNullable(f.ownerId()))
                .queryParamIfPresent("osoba", java.util.Optional.ofNullable(f.personId()))
                .encode().build().toUriString();
    }

    private static String fileName(String prefix, Activity a, ScheduleService.Filter f) {
        return prefix + "-" + safe(a.code()) + (f.audience() == null ? "" : "-" + safe(f.audience()))
                + (f.personId() == null ? "" : "-osoba" + f.personId())
                + (f.ownerId() == null ? "" : "-vlastnik" + f.ownerId());
    }

    /** Nazov suboru len z ASCII - diakritika v Content-Disposition robi problemy starsim prehliadacom. */
    static String safe(String s) {
        String ascii = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return ascii.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
    }
}
