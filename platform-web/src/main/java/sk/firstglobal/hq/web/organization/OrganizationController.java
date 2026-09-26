package sk.firstglobal.hq.web.organization;

import sk.firstglobal.hq.core.Identifiers;
import sk.firstglobal.hq.core.InvoiceNumbering;
import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.security.CurrentUser;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/nastavenia")
class OrganizationController {
    private final OrganizationRepository organizations;
    private final AuditLog audit;

    OrganizationController(OrganizationRepository organizations, AuditLog audit) {
        this.organizations = organizations;
        this.audit = audit;
    }

    @GetMapping
    String edit(Model model) {
        return this.page(model, this.organizations.find().orElse(Organization.empty()), List.of());
    }

    @PostMapping
    String save(@ModelAttribute Organization form, BindingResult binding, Authentication auth, Model model,
                RedirectAttributes redirect) {
        Organization o = normalize(form);
        List<String> errors = validate(o);
        if (binding.hasErrors()) {
            errors.add("Skontrolujte číselné polia (splatnosť v dňoch).");
        }
        if (!errors.isEmpty()) {
            return this.page(model, o, errors);
        }
        this.organizations.save(o);
        this.audit.record(CurrentUser.name(auth), "ZMENA", "organizacia", 1, o.name() + ", IČO " + o.ico()
                + ", IČ DPH " + (o.isVatPayer() ? o.icDph() : "-") + ", IBAN " + o.iban());
        redirect.addFlashAttribute("message", "Nastavenia boli uložené. Na už vystavené faktúry nemajú vplyv.");
        return "redirect:/nastavenia";
    }

    static Organization normalize(Organization f) {
        return new Organization(t(f.name()), t(f.street()), t(f.city()), t(f.postalCode()),
                t(f.country()).isEmpty() ? "SK" : t(f.country()).toUpperCase(), s(f.ico()), s(f.dic()),
                s(f.icDph()), t(f.email()), t(f.phone()), s(f.iban()), s(f.bic()), t(f.registrationNote()),
                t(f.invoicePattern()).isEmpty() ? "{YYYY}{NNNN}" : t(f.invoicePattern()), f.dueDays());
    }

    static List<String> validate(Organization o) {
        List<String> errors = new ArrayList<>();
        if (o.name().isEmpty()) {
            errors.add("Názov organizácie je povinný.");
        }
        if (o.city().isEmpty()) {
            errors.add("Obec je povinná.");
        }
        if (!o.ico().isEmpty() && !Identifiers.isIco(o.ico())) {
            errors.add("IČO musí mať 8 číslic.");
        }
        if (!o.dic().isEmpty() && !Identifiers.isDic(o.dic())) {
            errors.add("DIČ musí mať 10 číslic.");
        }
        if (!o.icDph().isEmpty() && !Identifiers.isSlovakIcDph(o.icDph())) {
            errors.add("IČ DPH musí byť v tvare SK1234567890 (vyplňte len ak ste platiteľ DPH).");
        }
        if (!o.iban().isEmpty() && !Identifiers.isIban(o.iban())) {
            errors.add("IBAN nie je platný.");
        }
        if (o.dueDays() < 0 || o.dueDays() > 365) {
            errors.add("Splatnosť musí byť 0 až 365 dní.");
        }
        try {
            InvoiceNumbering.format(o.invoicePattern(), 2027, 1);
        } catch (IllegalArgumentException | IllegalStateException e) {
            errors.add("Vzor čísla faktúry musí obsahovať {NNNN}, napr. {YYYY}{NNNN}.");
        }
        return errors;
    }

    private String page(Model model, Organization org, List<String> errors) {
        model.addAttribute("org", org);
        model.addAttribute("errors", errors);
        model.addAttribute("audit", this.audit.latest(20));
        return "settings";
    }

    private static String t(String v) {
        return v == null ? "" : v.trim();
    }

    private static String s(String v) {
        return v == null ? "" : v.replaceAll("\\s+", "").toUpperCase();
    }
}
