package com.fakturacnysoftver.web.project;

import com.fakturacnysoftver.web.audit.AuditLog;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/projekty")
class ProjectController {
    private final ProjectRepository projects;
    private final AuditLog audit;

    ProjectController(ProjectRepository projects, AuditLog audit) {
        this.projects = projects;
        this.audit = audit;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("projects", this.projects.findAll());
        model.addAttribute("errors", List.of());
        return "projects/list";
    }

    @PostMapping
    String create(@RequestParam String code, @RequestParam String name, @RequestParam(defaultValue = "0") String budget,
                  Authentication auth, Model model, RedirectAttributes redirect) {
        List<String> errors = new ArrayList<>();
        String c = code.trim().toUpperCase();
        if (!c.matches("[A-Z0-9][A-Z0-9_-]{1,29}")) {
            errors.add("Kód projektu: 2 až 30 znakov, písmená bez diakritiky, čísla, - a _.");
        } else if (this.projects.codeExists(c)) {
            errors.add("Projekt s kódom " + c + " už existuje.");
        }
        if (name.isBlank()) {
            errors.add("Názov projektu je povinný.");
        }
        BigDecimal amount = null;
        try {
            amount = new BigDecimal(budget.replaceAll("\\s", "").replace(',', '.'));
            if (amount.signum() < 0) {
                errors.add("Rozpočet nemôže byť záporný.");
            }
        } catch (NumberFormatException e) {
            errors.add("Rozpočet musí byť číslo.");
        }
        if (!errors.isEmpty()) {
            model.addAttribute("projects", this.projects.findAll());
            model.addAttribute("errors", errors);
            return "projects/list";
        }
        long id = this.projects.insert(c, name.trim(), amount);
        this.audit.record(CurrentUser.name(auth), "VYTVORENIE", "projekt", id, c + " rozpočet " + amount);
        redirect.addFlashAttribute("message", "Projekt " + c + " bol vytvorený.");
        return "redirect:/projekty";
    }
}
