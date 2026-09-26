package com.fakturacnysoftver.web.activity;

import com.fakturacnysoftver.web.audit.AuditLog;
import com.fakturacnysoftver.web.people.PersonRepository;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/aktivity")
class ActivityController {
    private static final List<String> STATUSES = List.of("PRIPRAVA", "PREBIEHA", "UKONCENA", "ZRUSENA");

    private final ActivityRepository activities;
    private final StaffingRepository staffing;
    private final TaskRepository tasks;
    private final PersonRepository people;
    private final AuditLog audit;
    private final Clock clock;

    ActivityController(ActivityRepository activities, StaffingRepository staffing, TaskRepository tasks,
                       PersonRepository people, AuditLog audit, Clock clock) {
        this.activities = activities;
        this.staffing = staffing;
        this.tasks = tasks;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(this.clock);
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("activities", this.activities.findAll(this.today()));
        model.addAttribute("kinds", ActivityKind.values());
        model.addAttribute("errors", List.of());
        return "activities/list";
    }

    @PostMapping
    @Transactional
    String create(@RequestParam String code, @RequestParam String name, @RequestParam String kind,
                  @RequestParam(required = false) LocalDate startsOn, @RequestParam(required = false) LocalDate endsOn,
                  @RequestParam(required = false) String location, @RequestParam(defaultValue = "0") String budget,
                  @RequestParam(defaultValue = "false") boolean useTemplate, Authentication auth, Model model,
                  RedirectAttributes redirect) {
        List<String> errors = new ArrayList<>();
        String c = code.trim().toUpperCase();
        if (!c.matches("[A-Z0-9][A-Z0-9_-]{1,29}")) {
            errors.add("Kód: 2 až 30 znakov, písmená bez diakritiky, čísla, - a _.");
        } else if (this.activities.codeExists(c)) {
            errors.add("Aktivita s kódom " + c + " už existuje.");
        }
        ActivityRepository.Fields f = this.fields(name, kind, "PRIPRAVA", startsOn, endsOn, location, null, budget, errors);
        if (!errors.isEmpty()) {
            model.addAttribute("activities", this.activities.findAll(this.today()));
            model.addAttribute("kinds", ActivityKind.values());
            model.addAttribute("errors", errors);
            return "activities/list";
        }
        long id = this.activities.create(new ActivityRepository.Fields(c, f.name(), f.kind(), f.status(), f.startsOn(),
                f.endsOn(), f.location(), null, f.budget()));
        int created = useTemplate ? this.tasks.applyTemplate(id, f.kind(), f.startsOn()) : 0;
        this.audit.record(CurrentUser.name(auth), "VYTVORENIE", "aktivita", id, c + " " + f.name());
        redirect.addFlashAttribute("message", "Aktivita " + c + " je vytvorená"
                + (created > 0 ? " s " + created + " úlohami zo šablóny." : "."));
        return "redirect:/aktivity/" + id;
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Model model) {
        Activity a = this.activities.findById(id, this.today()).orElseThrow(NotFound::new);
        List<TaskRepository.Task> all = this.tasks.tasksOf(id);
        Map<String, List<TaskRepository.Task>> bySection = new LinkedHashMap<>();
        all.stream().filter(t -> !t.done()).forEach(t -> bySection.computeIfAbsent(t.section(), k -> new ArrayList<>()).add(t));
        model.addAttribute("a", a);
        model.addAttribute("roles", this.staffing.rolesOf(id));
        model.addAttribute("openTasks", bySection);
        model.addAttribute("doneTasks", all.stream().filter(TaskRepository.Task::done).toList());
        model.addAttribute("people", this.people.findAll());
        model.addAttribute("kinds", ActivityKind.values());
        model.addAttribute("statuses", STATUSES);
        model.addAttribute("today", this.today());
        model.addAttribute("templateSize", this.tasks.templateSize(a.kind()));
        return "activities/detail";
    }

    @PostMapping("/{id}")
    String update(@PathVariable long id, @RequestParam String name, @RequestParam String kind, @RequestParam String status,
                  @RequestParam(required = false) LocalDate startsOn, @RequestParam(required = false) LocalDate endsOn,
                  @RequestParam(required = false) String location, @RequestParam(required = false) String description,
                  @RequestParam(defaultValue = "0") String budget, Authentication auth, RedirectAttributes redirect) {
        this.activities.findById(id, this.today()).orElseThrow(NotFound::new);
        List<String> errors = new ArrayList<>();
        ActivityRepository.Fields f = this.fields(name, kind, status, startsOn, endsOn, location, description, budget, errors);
        if (!errors.isEmpty()) {
            redirect.addFlashAttribute("errors", errors);
            return "redirect:/aktivity/" + id;
        }
        this.activities.update(id, f);
        this.audit.record(CurrentUser.name(auth), "ZMENA", "aktivita", id, f.name() + " " + f.status());
        redirect.addFlashAttribute("message", "Aktivita je uložená.");
        return "redirect:/aktivity/" + id;
    }

    @PostMapping("/{id}/sablona")
    String applyTemplate(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        Activity a = this.activities.findById(id, this.today()).orElseThrow(NotFound::new);
        int created = this.tasks.applyTemplate(id, a.kind(), a.startsOn());
        this.audit.record(CurrentUser.name(auth), "SABLONA", "aktivita", id, created + " úloh");
        redirect.addFlashAttribute("message", created == 0 ? "Všetky úlohy zo šablóny už existujú."
                : "Pridaných " + created + " úloh zo šablóny" + (a.startsOn() == null ? " (bez termínov - doplňte dátum začiatku)." : "."));
        return "redirect:/aktivity/" + id + "#ulohy";
    }

    // ---------- roly a obsadenie ----------

    @PostMapping("/{id}/roly")
    String addRole(@PathVariable long id, @RequestParam String name, @RequestParam(defaultValue = "1") int needed,
                   @RequestParam(required = false) LocalDateTime startsAt, @RequestParam(required = false) LocalDateTime endsAt,
                   @RequestParam(required = false) String description, RedirectAttributes redirect) {
        this.activities.findById(id, this.today()).orElseThrow(NotFound::new);
        List<String> errors = new ArrayList<>();
        if (name.isBlank()) {
            errors.add("Názov roly je povinný.");
        }
        if (needed < 1 || needed > 500) {
            errors.add("Počet ľudí musí byť 1 až 500.");
        }
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            errors.add("Koniec smeny musí byť po jej začiatku.");
        }
        if (errors.isEmpty()) {
            this.staffing.addRole(id, name.trim(), startsAt, endsAt, needed, blank(description));
            redirect.addFlashAttribute("message", "Rola " + name.trim() + " je pridaná.");
        } else {
            redirect.addFlashAttribute("errors", errors);
        }
        return "redirect:/aktivity/" + id + "#tim";
    }

    @PostMapping("/{id}/roly/{roleId}/zmazat")
    String deleteRole(@PathVariable long id, @PathVariable long roleId, RedirectAttributes redirect) {
        if (this.staffing.deleteRole(id, roleId) == 1) {
            redirect.addFlashAttribute("message", "Rola je zmazaná.");
        }
        return "redirect:/aktivity/" + id + "#tim";
    }

    @PostMapping("/{id}/roly/{roleId}/priradit")
    String assign(@PathVariable long id, @PathVariable long roleId, @RequestParam(required = false) Long personId,
                  RedirectAttributes redirect) {
        Long project = this.staffing.roleProject(roleId).orElseThrow(NotFound::new);
        if (project != id || personId == null || this.people.findById(personId).isEmpty()) {
            redirect.addFlashAttribute("errors", List.of("Vyberte osobu."));
        } else if (!this.staffing.assign(roleId, personId)) {
            redirect.addFlashAttribute("errors", List.of("Osoba už v tejto role je."));
        }
        return "redirect:/aktivity/" + id + "#tim";
    }

    @PostMapping("/{id}/obsadenie/{assignmentId}")
    String updateAssignment(@PathVariable long id, @PathVariable long assignmentId, @RequestParam String status,
                            @RequestParam(required = false) String hours, RedirectAttributes redirect) {
        List<String> allowed = List.of("POZVANY", "POTVRDENY", "ODMIETOL", "ZUCASTNIL_SA");
        BigDecimal h = null;
        try {
            h = hours == null || hours.isBlank() ? null : new BigDecimal(hours.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            h = BigDecimal.valueOf(-1);
        }
        if (!allowed.contains(status) || (h != null && (h.signum() < 0 || h.compareTo(BigDecimal.valueOf(200)) > 0))) {
            redirect.addFlashAttribute("errors", List.of("Neplatný stav alebo počet hodín (0 až 200)."));
        } else {
            this.staffing.update(id, assignmentId, status, h);
        }
        return "redirect:/aktivity/" + id + "#tim";
    }

    @PostMapping("/{id}/obsadenie/{assignmentId}/odobrat")
    String removeAssignment(@PathVariable long id, @PathVariable long assignmentId) {
        this.staffing.remove(id, assignmentId);
        return "redirect:/aktivity/" + id + "#tim";
    }

    // ---------- ulohy ----------

    @PostMapping("/{id}/ulohy")
    String addTask(@PathVariable long id, @RequestParam String title, @RequestParam(required = false) String section,
                   @RequestParam(required = false) LocalDate dueOn, @RequestParam(required = false) Long assigneeId,
                   RedirectAttributes redirect) {
        this.activities.findById(id, this.today()).orElseThrow(NotFound::new);
        if (title.isBlank()) {
            redirect.addFlashAttribute("errors", List.of("Názov úlohy je povinný."));
        } else {
            this.tasks.add(id, section == null || section.isBlank() ? "Úlohy" : section.trim(), title.trim(), dueOn,
                    assigneeId);
        }
        return "redirect:/aktivity/" + id + "#ulohy";
    }

    @PostMapping("/{id}/ulohy/{taskId}/hotovo")
    String toggleTask(@PathVariable long id, @PathVariable long taskId, Authentication auth) {
        this.tasks.toggle(id, taskId, CurrentUser.name(auth));
        return "redirect:/aktivity/" + id + "#ulohy";
    }

    @PostMapping("/{id}/ulohy/{taskId}/zmazat")
    String deleteTask(@PathVariable long id, @PathVariable long taskId) {
        this.tasks.delete(id, taskId);
        return "redirect:/aktivity/" + id + "#ulohy";
    }

    private ActivityRepository.Fields fields(String name, String kind, String status, LocalDate startsOn,
                                             LocalDate endsOn, String location, String description, String budget,
                                             List<String> errors) {
        if (name == null || name.isBlank()) {
            errors.add("Názov je povinný.");
        }
        if (Arrays.stream(ActivityKind.values()).noneMatch(k -> k.name().equals(kind))) {
            errors.add("Neznámy typ aktivity.");
        }
        if (!STATUSES.contains(status)) {
            errors.add("Neznámy stav.");
        }
        if (startsOn != null && endsOn != null && endsOn.isBefore(startsOn)) {
            errors.add("Koniec aktivity je pred jej začiatkom.");
        }
        BigDecimal b = null;
        try {
            b = new BigDecimal(budget.replaceAll("\\s", "").replace(',', '.'));
            if (b.signum() < 0) {
                errors.add("Rozpočet nemôže byť záporný.");
            }
        } catch (NumberFormatException e) {
            errors.add("Rozpočet musí byť číslo.");
        }
        return new ActivityRepository.Fields(null, name == null ? null : name.trim(), kind, status, startsOn, endsOn,
                blank(location), blank(description), b);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static class NotFound extends ResponseStatusException {
        NotFound() {
            super(HttpStatus.NOT_FOUND);
        }
    }
}
