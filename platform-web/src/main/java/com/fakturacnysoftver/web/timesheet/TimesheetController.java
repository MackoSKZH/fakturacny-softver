package com.fakturacnysoftver.web.timesheet;

import com.fakturacnysoftver.web.export.Csv;
import com.fakturacnysoftver.web.people.Person;
import com.fakturacnysoftver.web.people.PersonRepository;
import com.fakturacnysoftver.web.people.PersonRole;
import com.fakturacnysoftver.web.project.ProjectRepository;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.HttpStatus;
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

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * /dochadzka - sprava zmluv a vykazov (editori).
 * /moja-dochadzka - platený clovek vidi a zapisuje len svoje hodiny (prepojenie cez e-mail prihlasenia).
 */
@Controller
class TimesheetController {
    private final TimesheetService service;
    private final TimesheetRepository repo;
    private final PersonRepository people;
    private final ProjectRepository projects;
    private final Clock clock;

    TimesheetController(TimesheetService service, TimesheetRepository repo, PersonRepository people,
                        ProjectRepository projects, Clock clock) {
        this.service = service;
        this.repo = repo;
        this.people = people;
        this.projects = projects;
        this.clock = clock;
    }

    private YearMonth month(String raw) {
        try {
            return raw == null || raw.isBlank() ? YearMonth.now(this.clock) : YearMonth.parse(raw);
        } catch (DateTimeParseException e) {
            return YearMonth.now(this.clock);
        }
    }

    // ---------- sprava (editori) ----------

    @GetMapping("/dochadzka")
    String overview(@RequestParam(required = false) String mesiac, Model model) {
        YearMonth m = this.month(mesiac);
        List<TimesheetRepository.Contract> contracts = this.repo.contracts();
        model.addAttribute("month", m);
        model.addAttribute("rows", contracts.stream().map(c -> this.service.report(c.id(), m)).toList());
        model.addAttribute("kinds", ContractKind.values());
        model.addAttribute("paid", this.people.findAll().stream()
                .filter(p -> p.roles().contains(PersonRole.PLATENY.name())).toList());
        model.addAttribute("projects", this.projects.findAll());
        model.addAttribute("minWage", ContractKind.MIN_HOURLY_WAGE_2026);
        return "timesheet/overview";
    }

    @PostMapping("/dochadzka/zmluvy")
    String createContract(@RequestParam(required = false) Long personId, @RequestParam String kind,
                          @RequestParam String title, @RequestParam(required = false) String jobDescription,
                          @RequestParam String hourlyRate, @RequestParam(required = false) LocalDate validFrom,
                          @RequestParam(required = false) LocalDate validTo, @RequestParam(required = false) Long projectId,
                          @RequestParam(required = false) String note, Authentication auth, RedirectAttributes redirect) {
        try {
            long id = this.service.createContract(personId == null ? -1 : personId, kind, title, jobDescription,
                    hourlyRate, validFrom, validTo, projectId, note, CurrentUser.name(auth));
            redirect.addFlashAttribute("message", "Zmluva je uložená.");
            return "redirect:/dochadzka/" + id;
        } catch (TimesheetService.TimesheetException e) {
            redirect.addFlashAttribute("errors", e.errors());
            return "redirect:/dochadzka";
        }
    }

    @GetMapping("/dochadzka/{id}")
    String contract(@PathVariable long id, @RequestParam(required = false) String mesiac, Model model) {
        return this.detail(id, this.month(mesiac), model, false);
    }

    @PostMapping("/dochadzka/{id}/hodiny")
    String log(@PathVariable long id, @RequestParam(required = false) LocalDate workDate, @RequestParam String hours,
               @RequestParam(required = false) Long projectId, @RequestParam String description, Authentication auth,
               RedirectAttributes redirect) {
        return this.doLog(id, workDate, hours, projectId, description, auth, redirect, "/dochadzka/" + id);
    }

    @PostMapping("/dochadzka/{id}/hodiny/{logId}/zmazat")
    String deleteLog(@PathVariable long id, @PathVariable long logId, @RequestParam(required = false) LocalDate workDate,
                     RedirectAttributes redirect) {
        return this.doDelete(id, logId, workDate, redirect, "/dochadzka/" + id);
    }

    @PostMapping("/dochadzka/{id}/uzavriet")
    String close(@PathVariable long id, @RequestParam String mesiac, Authentication auth, RedirectAttributes redirect) {
        try {
            this.service.close(id, YearMonth.parse(mesiac), CurrentUser.name(auth));
            redirect.addFlashAttribute("message", "Mesiac " + mesiac + " je uzavretý - výkaz je pripravený pre účtovníka.");
        } catch (TimesheetService.TimesheetException e) {
            redirect.addFlashAttribute("errors", e.errors());
        }
        return "redirect:/dochadzka/" + id + "?mesiac=" + mesiac;
    }

    /** Mesacny podklad pre uctovnika - vsetky zmluvy. */
    @GetMapping("/dochadzka/export.csv")
    ResponseEntity<byte[]> exportMonth(@RequestParam(required = false) String mesiac) {
        YearMonth m = this.month(mesiac);
        Csv csv = new Csv("Mesiac", "Meno", "Pozícia", "Typ zmluvy", "Hodiny", "Hodinovka (€)", "Hrubá odmena (€)",
                "Rozpis podľa projektov", "Uzavreté");
        for (TimesheetRepository.Contract c : this.repo.contracts()) {
            TimesheetService.MonthReport r = this.service.report(c.id(), m);
            if (r.hours().signum() == 0 && !c.isActiveOn(m.atEndOfMonth()) && !c.isActiveOn(m.atDay(1))) {
                continue;
            }
            csv.row(m.toString(), c.personName(), c.title(), c.kindLabel(), r.hours(), c.hourlyRate(), r.reward(),
                    r.hoursByProject().entrySet().stream().map(e -> e.getKey() + ": " + Csv.cell(e.getValue())).toList(),
                    r.closed() ? "áno" : "NIE");
        }
        return csv.response("dochadzka-" + m + ".csv");
    }

    @GetMapping("/dochadzka/{id}/vykaz.csv")
    ResponseEntity<byte[]> exportContract(@PathVariable long id, @RequestParam(required = false) String mesiac) {
        return this.contractCsv(this.service.report(id, this.month(mesiac)));
    }

    // ---------- samoobsluha ----------

    @GetMapping("/moja-dochadzka")
    String mine(@RequestParam(required = false) Long zmluva, @RequestParam(required = false) String mesiac,
                Authentication auth, Model model) {
        Person me = this.people.findByEmail(CurrentUser.name(auth)).orElse(null);
        List<TimesheetRepository.Contract> mine = me == null ? List.of() : this.repo.contractsOf(me.id());
        model.addAttribute("me", me);
        model.addAttribute("contracts", mine);
        if (mine.isEmpty()) {
            return "timesheet/mine";
        }
        long id = zmluva != null && mine.stream().anyMatch(c -> c.id() == zmluva) ? zmluva : mine.get(0).id();
        return this.detail(id, this.month(mesiac), model, true);
    }

    @PostMapping("/moja-dochadzka/{id}/hodiny")
    String logMine(@PathVariable long id, @RequestParam(required = false) LocalDate workDate, @RequestParam String hours,
                   @RequestParam(required = false) Long projectId, @RequestParam String description, Authentication auth,
                   RedirectAttributes redirect) {
        this.requireOwn(id, auth);
        return this.doLog(id, workDate, hours, projectId, description, auth, redirect, "/moja-dochadzka?zmluva=" + id);
    }

    @PostMapping("/moja-dochadzka/{id}/hodiny/{logId}/zmazat")
    String deleteMine(@PathVariable long id, @PathVariable long logId, @RequestParam(required = false) LocalDate workDate,
                      Authentication auth, RedirectAttributes redirect) {
        this.requireOwn(id, auth);
        return this.doDelete(id, logId, workDate, redirect, "/moja-dochadzka?zmluva=" + id);
    }

    @GetMapping("/moja-dochadzka/{id}/vykaz.csv")
    ResponseEntity<byte[]> exportMine(@PathVariable long id, @RequestParam(required = false) String mesiac,
                                      Authentication auth) {
        this.requireOwn(id, auth);
        return this.contractCsv(this.service.report(id, this.month(mesiac)));
    }

    private void requireOwn(long contractId, Authentication auth) {
        Person me = this.people.findByEmail(CurrentUser.name(auth)).orElse(null);
        TimesheetRepository.Contract c = this.repo.contract(contractId).orElse(null);
        if (me == null || c == null || c.personId() != me.id()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    // ---------- spolocne ----------

    private String detail(long id, YearMonth m, Model model, boolean self) {
        TimesheetService.MonthReport r;
        try {
            r = this.service.report(id, m);
        } catch (TimesheetService.TimesheetException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("r", r);
        model.addAttribute("self", self);
        String page = self ? "/moja-dochadzka?zmluva=" + id + "&mesiac=" : "/dochadzka/" + id + "?mesiac=";
        String base = self ? "/moja-dochadzka/" + id : "/dochadzka/" + id;
        model.addAttribute("prevUrl", page + m.minusMonths(1));
        model.addAttribute("nextUrl", page + m.plusMonths(1));
        model.addAttribute("csvUrl", base + "/vykaz.csv?mesiac=" + m);
        model.addAttribute("logUrl", base + "/hodiny");
        model.addAttribute("deleteUrl", base + "/hodiny/");
        model.addAttribute("projects", this.projects.findAll());
        model.addAttribute("today", LocalDate.now(this.clock));
        return "timesheet/detail";
    }

    private String doLog(long id, LocalDate date, String hours, Long projectId, String description, Authentication auth,
                         RedirectAttributes redirect, String back) {
        String month = date == null ? "" : YearMonth.from(date).toString();
        try {
            this.service.logHours(id, date, hours, projectId, description, CurrentUser.name(auth));
            redirect.addFlashAttribute("message", "Hodiny sú zapísané.");
        } catch (TimesheetService.TimesheetException e) {
            redirect.addFlashAttribute("errors", e.errors());
        }
        return "redirect:" + back + (back.contains("?") ? "&" : "?") + "mesiac=" + month;
    }

    private String doDelete(long id, long logId, LocalDate date, RedirectAttributes redirect, String back) {
        try {
            this.service.deleteLog(id, logId, date);
        } catch (TimesheetService.TimesheetException e) {
            redirect.addFlashAttribute("errors", e.errors());
        }
        return "redirect:" + back + (back.contains("?") ? "&" : "?") + "mesiac=" + (date == null ? "" : YearMonth.from(date));
    }

    private ResponseEntity<byte[]> contractCsv(TimesheetService.MonthReport r) {
        Csv csv = new Csv("Dátum", "Hodiny", "Projekt", "Činnosť", "Zapísal");
        r.logs().forEach(l -> csv.row(l.workDate(), l.hours(), l.projectCode(), l.description(), l.createdBy()));
        csv.row("Spolu", r.hours(), "", "Hrubá odmena " + Csv.cell(r.reward()) + " € (" + Csv.cell(r.contract().hourlyRate())
                + " €/h)", r.closed() ? "uzavreté" : "neuzavreté");
        return csv.response("vykaz-" + r.contract().personName().replaceAll("[^A-Za-z0-9]", "_") + "-" + r.month() + ".csv");
    }
}
