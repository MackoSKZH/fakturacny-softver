package sk.firstglobal.hq.web.bank;

import sk.firstglobal.hq.web.activity.ActivityRepository;
import sk.firstglobal.hq.web.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** /polozky/banka - nahratie vypisu, nahlad s navrhmi parovania a potvrdenie (len zapis financii). */
@Controller
class BankImportController {
    static final String SESSION = "fgs.bankImport";
    private static final int MAX_BYTES = 5 * 1024 * 1024;

    private final BankImportService service;
    private final ActivityRepository activities;
    private final Clock clock;

    BankImportController(BankImportService service, ActivityRepository activities, Clock clock) {
        this.service = service;
        this.activities = activities;
        this.clock = clock;
    }

    @GetMapping("/polozky/banka")
    String upload() {
        return "bank/upload";
    }

    @PostMapping("/polozky/banka")
    String parse(@RequestParam(required = false) MultipartFile file, HttpSession session, RedirectAttributes redirect)
            throws IOException {
        try {
            if (file == null || file.isEmpty()) {
                throw new BankImportException("Vyberte súbor s výpisom.");
            }
            if (file.getSize() > MAX_BYTES) {
                throw new BankImportException("Výpis má viac než 5 MB - stiahnite kratšie obdobie (napr. mesiac).");
            }
            session.setAttribute(SESSION, this.service.preview(Camt053Parser.parse(file.getBytes())));
            return "redirect:/polozky/banka/nahlad";
        } catch (BankImportException e) {
            redirect.addFlashAttribute("errors", List.of(e.getMessage()));
            return "redirect:/polozky/banka";
        }
    }

    @GetMapping("/polozky/banka/nahlad")
    String preview(HttpSession session, Model model) {
        if (!(session.getAttribute(SESSION) instanceof BankImportService.Preview p)) {
            return "redirect:/polozky/banka";
        }
        model.addAttribute("p", p);
        model.addAttribute("activities", this.activities.findAll(LocalDate.now(this.clock)));
        return "bank/preview";
    }

    @PostMapping("/polozky/banka/potvrdit")
    String confirm(HttpServletRequest request, HttpSession session, Authentication auth, RedirectAttributes redirect) {
        if (!(session.getAttribute(SESSION) instanceof BankImportService.Preview p)) {
            redirect.addFlashAttribute("errors", List.of("Náhľad vypršal - nahrajte výpis znova."));
            return "redirect:/polozky/banka";
        }
        List<BankImportService.Decision> decisions = new ArrayList<>();
        for (BankImportService.Proposal pr : p.proposals()) {
            String a = request.getParameter("action_" + pr.index());
            String proj = request.getParameter("project_" + pr.index());
            BankImportService.Action action;
            try {
                action = a == null ? pr.action() : BankImportService.Action.valueOf(a);
            } catch (IllegalArgumentException e) {
                action = BankImportService.Action.PRESKOCIT;
            }
            if ((action == BankImportService.Action.FAKTURA || action == BankImportService.Action.DOSLA
                    || action == BankImportService.Action.DOHODA) && action != pr.action()) {
                action = BankImportService.Action.POLOZKA;
            }
            Long project = proj == null || proj.isBlank() || !proj.matches("\\d{1,18}") ? null : Long.valueOf(proj);
            decisions.add(new BankImportService.Decision(pr.index(), action, project));
        }
        try {
            BankImportService.Result r = this.service.apply(p, decisions, CurrentUser.name(auth));
            session.removeAttribute(SESSION);
            redirect.addFlashAttribute("message", "Výpis je naimportovaný: uhradené faktúry (vydané aj došlé) " + r.invoices()
                    + (r.deals() > 0 ? ", platby k dohodám " + r.deals() : "") + ", dary "
                    + r.donations() + ", nové položky " + r.entries() + ", preskočené " + r.skipped() + ".");
            return "redirect:/polozky";
        } catch (BankImportException e) {
            redirect.addFlashAttribute("errors", List.of(e.getMessage()));
            return "redirect:/polozky/banka/nahlad";
        }
    }
}
