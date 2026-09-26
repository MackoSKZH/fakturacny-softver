package sk.firstglobal.hq.web.access;

import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.people.PersonRole;
import sk.firstglobal.hq.web.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;

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
    private final Clock clock;

    AdminController(AccessService service, AccessRepository repo, ActivityRepository activities, Clock clock) {
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
