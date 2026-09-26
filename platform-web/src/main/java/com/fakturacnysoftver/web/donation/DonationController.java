package com.fakturacnysoftver.web.donation;

import com.fakturacnysoftver.core.PayBySquare;
import com.fakturacnysoftver.web.audit.AuditLog;
import com.fakturacnysoftver.web.organization.Organization;
import com.fakturacnysoftver.web.organization.OrganizationRepository;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * /podpora/{kod} - verejna stranka bez prihlasenia: QR platba (PAY by square) na ucet zdruzenia s VS aktivity.
 * Ukazuje len nazov aktivity, text, ktory napisal editor, a udaje na platbu - nic z internych dat.
 */
@Controller
class DonationController {
    static final List<Integer> PRESETS = List.of(10, 20, 50, 100);

    private final DonationRepository repo;
    private final OrganizationRepository organizations;
    private final AuditLog audit;

    DonationController(DonationRepository repo, OrganizationRepository organizations, AuditLog audit) {
        this.repo = repo;
        this.organizations = organizations;
        this.audit = audit;
    }

    @GetMapping("/podpora/{code:[A-Za-z0-9_-]{2,30}}")
    String page(@PathVariable String code, @RequestParam(required = false) Integer suma, Model model) {
        DonationRepository.Settings s = this.repo.publicByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        Organization org = this.organizations.find().orElse(null);
        model.addAttribute("s", s);
        model.addAttribute("org", org);
        model.addAttribute("presets", PRESETS);
        if (org == null || org.iban() == null || org.iban().isBlank()) {
            model.addAttribute("qr", null);
            return "donation/public";
        }
        BigDecimal amount = suma != null && suma >= 1 && suma <= 10000 ? BigDecimal.valueOf(suma) : null;
        String message = "Dar " + s.code();
        String address2 = String.join(" ", nz(org.postalCode()), nz(org.city())).trim();
        PayBySquare.Payment payment = new PayBySquare.Payment(amount, "EUR", null, s.variableSymbol(), null, null,
                message, org.iban(), blank(org.bic()), org.name(), blank(org.street()), blank(address2));
        model.addAttribute("qr", QrSvg.svg(PayBySquare.encode(payment), "QR platba " + message));
        model.addAttribute("amount", amount);
        model.addAttribute("message", message);
        model.addAttribute("iban", formatIban(org.iban()));
        return "donation/public";
    }

    @PostMapping("/aktivity/{id}/podpora")
    String settings(@PathVariable long id, @RequestParam(defaultValue = "false") boolean enabled,
                    @RequestParam(required = false) String text, @RequestParam(required = false) String goal,
                    Authentication auth, RedirectAttributes redirect) {
        this.repo.of(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<String> errors = new ArrayList<>();
        BigDecimal g = null;
        String raw = goal == null ? "" : goal.replaceAll("\\s", "").replace(',', '.');
        if (!raw.isEmpty()) {
            try {
                g = new BigDecimal(raw);
                if (g.signum() <= 0 || g.scale() > 2) {
                    errors.add("Cieľ zbierky musí byť kladná suma.");
                }
            } catch (NumberFormatException e) {
                errors.add("Cieľ zbierky musí byť číslo.");
            }
        }
        String t = text == null || text.isBlank() ? null : text.trim();
        if (t != null && t.length() > 1500) {
            errors.add("Text pre verejnosť má najviac 1500 znakov.");
        }
        if (enabled && this.organizations.find().map(o -> o.iban() == null || o.iban().isBlank()).orElse(true)) {
            errors.add("Najprv vyplňte IBAN združenia v Nastaveniach.");
        }
        if (!errors.isEmpty()) {
            redirect.addFlashAttribute("errors", errors);
            return "redirect:/aktivity/" + id + "#podpora";
        }
        this.repo.update(id, enabled, t, g);
        this.audit.record(CurrentUser.name(auth), "ZMENA", "podpora", id, enabled ? "verejná" : "vypnutá");
        redirect.addFlashAttribute("message", enabled ? "Verejná stránka podpory je zapnutá." : "Verejná stránka podpory je vypnutá.");
        return "redirect:/aktivity/" + id + "#podpora";
    }

    static String formatIban(String iban) {
        return iban.replace(" ", "").replaceAll("(.{4})(?!$)", "$1 ");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
