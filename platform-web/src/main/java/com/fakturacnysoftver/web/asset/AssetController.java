package com.fakturacnysoftver.web.asset;

import com.fakturacnysoftver.web.people.PersonRepository;
import com.fakturacnysoftver.web.project.ProjectRepository;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Locale;

/** /majetok - hardver a vybavenie: kde je, kto ho ma, z coho sme ho kupili. Citaju vsetci, zapisuju editori. */
@Controller
class AssetController {
    private final AssetService service;
    private final AssetRepository repo;
    private final PersonRepository people;
    private final ProjectRepository projects;

    AssetController(AssetService service, AssetRepository repo, PersonRepository people, ProjectRepository projects) {
        this.service = service;
        this.repo = repo;
        this.people = people;
        this.projects = projects;
    }

    @GetMapping("/majetok")
    String list(@RequestParam(required = false) String kategoria, @RequestParam(required = false) String stav,
                @RequestParam(required = false) String q, Model model) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        String st = stav == null || stav.isBlank() ? "aktivne" : stav;
        List<Asset> all = this.repo.findAll().stream()
                .filter(a -> kategoria == null || kategoria.isBlank() || a.category().equals(kategoria))
                .filter(a -> switch (st) {
                    case "pozicane" -> a.isLent();
                    case "vyradene" -> a.isRetired();
                    case "aktivne" -> !a.isRetired();
                    default -> true;
                })
                .filter(a -> query.isEmpty() || (a.inventoryNo() + " " + a.name() + " " + nz(a.serialNo()) + " "
                        + nz(a.location()) + " " + nz(a.borrowerName())).toLowerCase(Locale.ROOT).contains(query))
                .toList();
        model.addAttribute("assets", all);
        model.addAttribute("categories", AssetCategory.values());
        model.addAttribute("kategoria", kategoria);
        model.addAttribute("stav", st);
        model.addAttribute("q", q);
        model.addAttribute("today", this.service.today());
        model.addAttribute("nextNo", this.repo.nextInventoryNo(this.service.today().getYear()));
        this.options(model);
        return "assets/list";
    }

    @PostMapping("/majetok")
    String create(AssetService.AssetInput in, Authentication auth, RedirectAttributes redirect) {
        try {
            long id = this.service.create(in, CurrentUser.name(auth));
            redirect.addFlashAttribute("message", "Zapísané.");
            return "redirect:/majetok/" + id;
        } catch (AssetException e) {
            redirect.addFlashAttribute("errors", e.errors());
            return "redirect:/majetok";
        }
    }

    @GetMapping("/majetok/{id}")
    String detail(@PathVariable long id, Model model) {
        Asset a = this.repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("a", a);
        model.addAttribute("warnings", a.warnings(this.service.today()));
        model.addAttribute("loans", this.repo.loans(id));
        model.addAttribute("today", this.service.today());
        model.addAttribute("categories", AssetCategory.values());
        model.addAttribute("people", this.people.findAll());
        model.addAttribute("projects", this.projects.findAll());
        this.options(model);
        return "assets/detail";
    }

    @PostMapping("/majetok/{id}")
    String update(@PathVariable long id, AssetService.AssetInput in, Authentication auth, RedirectAttributes redirect) {
        this.service.update(id, in, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Uložené.");
        return "redirect:/majetok/" + id;
    }

    @PostMapping("/majetok/{id}/pozicat")
    String lend(@PathVariable long id, @RequestParam(required = false) Long personId,
                @RequestParam(required = false) Long projectId, @RequestParam(required = false) String dueOn,
                @RequestParam(required = false) String note, Authentication auth, RedirectAttributes redirect) {
        this.service.lend(id, personId, projectId, dueOn, note, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Požičanie je zapísané.");
        return "redirect:/majetok/" + id;
    }

    @PostMapping("/majetok/{id}/vratit")
    String giveBack(@PathVariable long id, @RequestParam(required = false) String returnedOn, Authentication auth,
                    RedirectAttributes redirect) {
        this.service.giveBack(id, returnedOn, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Vrátenie je zapísané.");
        return "redirect:/majetok/" + id;
    }

    @PostMapping("/majetok/{id}/oprava")
    String repair(@PathVariable long id, @RequestParam(defaultValue = "false") boolean inRepair, Authentication auth) {
        this.service.repair(id, inRepair, CurrentUser.name(auth));
        return "redirect:/majetok/" + id;
    }

    @PostMapping("/majetok/{id}/vyradit")
    String retire(@PathVariable long id, @RequestParam(required = false) String retiredOn,
                  @RequestParam(required = false) String reason, Authentication auth, RedirectAttributes redirect) {
        this.service.retire(id, retiredOn, reason, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Vyradené.");
        return "redirect:/majetok/" + id;
    }

    private void options(Model model) {
        model.addAttribute("expenses", this.repo.expenseOptions());
        model.addAttribute("grants", this.repo.grantOptions());
    }

    @ExceptionHandler(AssetException.class)
    String handle(AssetException e, jakarta.servlet.http.HttpServletRequest request,
                  jakarta.servlet.http.HttpServletResponse response, RedirectAttributes redirect) throws java.io.IOException {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/majetok/(\\d+)")
                .matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if (!m.find() || this.repo.findById(Long.parseLong(m.group(1))).isEmpty()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return null;
        }
        redirect.addFlashAttribute("errors", e.errors());
        return "redirect:/majetok/" + m.group(1);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
