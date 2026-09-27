package sk.firstglobal.hq.web.customer;

import sk.firstglobal.hq.core.Identifiers;
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
@RequestMapping("/odberatelia")
class CustomerController {
    private final CustomerRepository customers;
    private final AuditLog audit;

    CustomerController(CustomerRepository customers, AuditLog audit) {
        this.customers = customers;
        this.audit = audit;
    }

    @GetMapping
    String list(Model model) {
        return this.page(model, new Customer(null, "", "", "", "", "SK", "", "", "", "", ""), List.of());
    }

    @PostMapping
    String create(@ModelAttribute Customer c, BindingResult binding, Authentication auth, Model model,
                  RedirectAttributes redirect) {
        Customer customer = new Customer(null, trim(c.name()), trim(c.street()), trim(c.city()), trim(c.postalCode()),
                c.country() == null || c.country().isBlank() ? "SK" : c.country().trim().toUpperCase(),
                strip(c.ico()), strip(c.dic()), strip(c.icDph()), trim(c.email()), trim(c.phone()));
        List<String> errors = validate(customer);
        if (!errors.isEmpty()) {
            return this.page(model, customer, errors);
        }
        long id = this.customers.insert(customer);
        this.audit.record(CurrentUser.name(auth), "VYTVORENIE", "odberatel", id, customer.name());
        redirect.addFlashAttribute("message", "Odberateľ " + customer.name() + " bol pridaný.");
        return "redirect:/odberatelia";
    }

    static List<String> validate(Customer c) {
        List<String> errors = new ArrayList<>();
        if (c.name() == null || c.name().isBlank()) {
            errors.add("Názov je povinný.");
        }
        if (!c.ico().isEmpty() && !Identifiers.isIco(c.ico())) {
            errors.add("IČO musí mať 8 číslic.");
        }
        if (!c.dic().isEmpty() && "SK".equals(c.country()) && !Identifiers.isDic(c.dic())) {
            errors.add("DIČ musí mať 10 číslic.");
        }
        if (!c.icDph().isEmpty() && "SK".equals(c.country()) && !Identifiers.isSlovakIcDph(c.icDph())) {
            errors.add("IČ DPH musí byť v tvare SK1234567890.");
        }
        return errors;
    }

    private String page(Model model, Customer draft, List<String> errors) {
        model.addAttribute("customers", this.customers.findAll());
        model.addAttribute("c", draft);
        model.addAttribute("errors", errors);
        return "customers/list";
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String strip(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toUpperCase();
    }
}
