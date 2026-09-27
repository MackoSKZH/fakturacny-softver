package sk.firstglobal.hq.web.access;

import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.export.Table;
import sk.firstglobal.hq.web.people.PersonRole;
import sk.firstglobal.hq.web.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** /sprava - pouzivatelia, roly a pozvanky. Len Admin (SecurityConfig). */
@Controller
class AdminController {
    private final AccessService service;
    private final AccessRepository repo;
    private final ActivityRepository activities;
    private final AuditLog audit;
    private final Clock clock;

    AdminController(AccessService service, AccessRepository repo, ActivityRepository activities, AuditLog audit,
                    Clock clock) {
        this.audit = audit;
        this.service = service;
        this.repo = repo;
        this.activities = activities;
        this.clock = clock;
    }

    @GetMapping("/sprava")
    String index() {
        return "redirect:/sprava/pouzivatelia";
    }

    // ---------- pouzivatelia ----------

    @GetMapping("/sprava/pouzivatelia")
    String users(Model model) {
        model.addAttribute("users", this.repo.users());
        model.addAttribute("roles", this.repo.roles());
        model.addAttribute("activities", this.activities.findAll(LocalDate.now(this.clock)));
        model.addAttribute("tab", "pouzivatelia");
        return "admin/users";
    }

    @PostMapping("/sprava/pouzivatelia")
    String createUser(@RequestParam(required = false) String email, @RequestParam(required = false) String displayName,
                      @RequestParam(required = false) List<Long> roleIds, Authentication auth, RedirectAttributes redirect) {
        this.service.createUser(email, displayName, roleIds, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Používateľ je pridaný. Prihlási sa svojím Google účtom " + email.trim() + ".");
        return "redirect:/sprava/pouzivatelia";
    }

    @PostMapping("/sprava/pouzivatelia/{id}/roly")
    String setRoles(@PathVariable long id, @RequestParam(required = false) List<Long> roleIds, HttpServletRequest request,
                    RedirectAttributes redirect) {
        this.service.setRoles(id, roleIds, AccessFilter.of(request));
        redirect.addFlashAttribute("message", "Roly sú uložené - platia od najbližšieho kliknutia.");
        return "redirect:/sprava/pouzivatelia#u" + id;
    }

    @PostMapping("/sprava/pouzivatelia/{id}/aktivny")
    String setActive(@PathVariable long id, @RequestParam boolean active, HttpServletRequest request,
                     RedirectAttributes redirect) {
        this.service.setActive(id, active, AccessFilter.of(request));
        redirect.addFlashAttribute("message", active ? "Účet je znova aktívny." : "Účet je deaktivovaný - odhlási sa hneď.");
        return "redirect:/sprava/pouzivatelia#u" + id;
    }

    @PostMapping("/sprava/pouzivatelia/{id}/vlastnictvo")
    String addOwnership(@PathVariable long id, @RequestParam(required = false) Long projectId, Authentication auth,
                        RedirectAttributes redirect) {
        if (projectId == null) {
            throw new AccessException(List.of("Vyberte aktivitu."));
        }
        this.service.addOwner(id, projectId, CurrentUser.name(auth));
        return "redirect:/sprava/pouzivatelia#u" + id;
    }

    @PostMapping("/sprava/pouzivatelia/{id}/vlastnictvo/{projectId}/odobrat")
    String removeOwnership(@PathVariable long id, @PathVariable long projectId, Authentication auth) {
        this.service.removeOwner(id, projectId, CurrentUser.name(auth));
        return "redirect:/sprava/pouzivatelia#u" + id;
    }

    // ---------- roly ----------

    @GetMapping("/sprava/role")
    String roles(Model model) {
        model.addAttribute("roles", this.repo.roles());
        model.addAttribute("permissions", Permission.values());
        model.addAttribute("personRoles", PersonRole.values());
        model.addAttribute("tab", "role");
        return "admin/roles";
    }

    @PostMapping("/sprava/role")
    String createRole(@RequestParam(required = false) String name, @RequestParam(required = false) String description,
                      @RequestParam(required = false) List<String> permissions,
                      @RequestParam(required = false) String personRole, Authentication auth, RedirectAttributes redirect) {
        this.service.createRole(name, description, permissions, personRole, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Rola je vytvorená.");
        return "redirect:/sprava/role";
    }

    @PostMapping("/sprava/role/{id}")
    String updateRole(@PathVariable long id, @RequestParam(required = false) String name,
                      @RequestParam(required = false) String description,
                      @RequestParam(required = false) List<String> permissions,
                      @RequestParam(required = false) String personRole, Authentication auth, RedirectAttributes redirect) {
        this.service.updateRole(id, name, description, permissions, personRole, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Rola je uložená - zmena platí pre všetkých s touto rolou hneď.");
        return "redirect:/sprava/role#r" + id;
    }

    @PostMapping("/sprava/role/{id}/zmazat")
    String deleteRole(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        this.service.deleteRole(id, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Rola je zmazaná.");
        return "redirect:/sprava/role";
    }

    // ---------- audit ----------

    private static final int AUDIT_PAGE = 100;
    private static final int AUDIT_EXPORT_MAX = 20000;

    /** Kto, kedy a co zmenil. Zaznamy sa nedaju upravit ani zmazat (chrani to databaza). */
    @GetMapping("/sprava/audit")
    String audit(@RequestParam(required = false) String actor, @RequestParam(required = false) String entity,
                 @RequestParam(required = false) String action,
                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                 @RequestParam(required = false) String q, @RequestParam(required = false) Long before, Model model) {
        AuditLog.Filter f = new AuditLog.Filter(actor, entity, action, from, to, q);
        List<AuditLog.Row> rows = this.audit.search(f, before, AUDIT_PAGE + 1);
        model.addAttribute("rows", rows.size() > AUDIT_PAGE ? rows.subList(0, AUDIT_PAGE) : rows);
        model.addAttribute("nextBefore", rows.size() > AUDIT_PAGE ? rows.get(AUDIT_PAGE - 1).id() : null);
        model.addAttribute("f", f);
        model.addAttribute("query", f.query());
        model.addAttribute("paged", before != null);
        model.addAttribute("entities", this.audit.entities());
        model.addAttribute("actions", this.audit.actions());
        model.addAttribute("tab", "audit");
        return "admin/audit";
    }

    @GetMapping("/sprava/audit.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> auditExport(@PathVariable String format, @RequestParam(required = false) String actor,
                                       @RequestParam(required = false) String entity,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                       @RequestParam(required = false) String q, Authentication auth) {
        AuditLog.Filter f = new AuditLog.Filter(actor, entity, action, from, to, q);
        List<AuditLog.Row> rows = this.audit.search(f, null, AUDIT_EXPORT_MAX + 1);
        Table t = new Table("Audit log", "Čas", "Kto", "Akcia", "Záznam", "Id", "Podrobnosti");
        t.subtitle(f.isEmpty() ? "všetky záznamy" : "filter: " + java.net.URLDecoder.decode(f.query(),
                java.nio.charset.StandardCharsets.UTF_8).replace("&", ", "));
        rows.stream().limit(AUDIT_EXPORT_MAX).forEach(r -> t.row(r.at(), r.actor(), r.action(), r.entity(), r.entityId(),
                r.detail()));
        if (rows.size() > AUDIT_EXPORT_MAX) {
            t.note("Zobrazených je len " + AUDIT_EXPORT_MAX + " najnovších záznamov - zúžte filter (napr. obdobie).");
        }
        // aj export auditu je udalost - kto si stiahol prehlad cinnosti ostatnych
        this.audit.record(CurrentUser.name(auth), "EXPORT", "audit", null, format + " " + f.query());
        return t.response(format, "audit-" + LocalDate.now(this.clock));
    }

    // ---------- pozvanky ----------

    @GetMapping("/sprava/pozvanky")
    String invites(Model model) {
        List<AccessRepository.Role> roles = this.repo.roles();
        model.addAttribute("invites", this.repo.invites());
        model.addAttribute("roles", roles);
        model.addAttribute("roleNamesById", roles.stream().collect(java.util.stream.Collectors.toMap(
                AccessRepository.Role::id, AccessRepository.Role::name)));
        model.addAttribute("activities", this.activities.findAll(LocalDate.now(this.clock)));
        model.addAttribute("now", OffsetDateTime.now(this.clock));
        model.addAttribute("tab", "pozvanky");
        return "admin/invites";
    }

    @PostMapping("/sprava/pozvanky")
    String createInvite(@RequestParam(required = false) String email, @RequestParam(required = false) List<Long> roleIds,
                        @RequestParam(required = false) Long projectId, @RequestParam(required = false) String note,
                        @RequestParam(required = false) Integer days, @RequestParam(required = false) Integer maxUses,
                        Authentication auth, RedirectAttributes redirect) {
        AccessService.CreatedInvite i = this.service.createInvite(email, roleIds, projectId, note, days, maxUses,
                CurrentUser.name(auth));
        redirect.addFlashAttribute("inviteLink", ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/pozvanka/" + i.token()).toUriString());
        redirect.addFlashAttribute("message", "Pozvánka je vytvorená. Odkaz skopírujte teraz - neskôr sa už zobraziť nedá.");
        return "redirect:/sprava/pozvanky";
    }

    @PostMapping("/sprava/pozvanky/{id}/zrusit")
    String revoke(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        this.service.revokeInvite(id, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Pozvánka je zrušená.");
        return "redirect:/sprava/pozvanky";
    }

    /** Chyba validacie - spat na stranku, z ktorej formular prisiel. */
    @ExceptionHandler(AccessException.class)
    String handle(AccessException e, HttpServletRequest request, RedirectAttributes redirect) {
        redirect.addFlashAttribute("errors", e.errors());
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return "redirect:" + (path.startsWith("/sprava/role") ? "/sprava/role"
                : path.startsWith("/sprava/pozvanky") ? "/sprava/pozvanky" : "/sprava/pouzivatelia");
    }
}
