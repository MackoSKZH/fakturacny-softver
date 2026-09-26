package com.fakturacnysoftver.web.partner;

import com.fakturacnysoftver.web.attachment.AttachmentRepository;
import com.fakturacnysoftver.web.project.ProjectRepository;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

/**
 * /financovanie - pipeline dohod, krytie rozpoctu aktivit a granty. Citaju vsetci clenovia
 * (bez kontaktov na partnerov), zapisuju editori.
 */
@Controller
class FundingController {
    private final PartnerService service;
    private final PartnerRepository repo;
    private final ProjectRepository projects;
    private final AttachmentRepository attachments;

    FundingController(PartnerService service, PartnerRepository repo, ProjectRepository projects,
                      AttachmentRepository attachments) {
        this.attachments = attachments;
        this.service = service;
        this.repo = repo;
        this.projects = projects;
    }

    @GetMapping("/financovanie")
    String overview(@RequestParam(required = false) Long aktivita, @RequestParam(required = false) String typ,
                    Model model) {
        Predicate<Deal> filter = d -> (aktivita == null || aktivita.equals(d.projectId()))
                && (typ == null || typ.isBlank() || d.kind().equals(typ));
        List<Deal> deals = this.repo.deals(this.service.today()).stream().filter(filter).toList();
        model.addAttribute("deals", deals);
        model.addAttribute("negotiating", sum(deals, s -> s.isOpen()));
        model.addAttribute("secured", sum(deals, DealStage::isSecured));
        model.addAttribute("received", deals.stream().map(Deal::received).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("warningCount", deals.stream().mapToLong(d -> d.warnings(this.service.today()).size()).sum());
        model.addAttribute("funding", this.repo.fundingByActivity().stream()
                .filter(f -> aktivita == null || aktivita == f.id()).toList());
        model.addAttribute("today", this.service.today());
        model.addAttribute("aktivita", aktivita);
        model.addAttribute("typ", typ);
        model.addAttribute("dealKinds", DealKind.values());
        model.addAttribute("stages", DealStage.values());
        model.addAttribute("projects", this.projects.findAll());
        model.addAttribute("partners", this.repo.findAll());
        return "funding/overview";
    }

    private static BigDecimal sum(List<Deal> deals, Predicate<DealStage> stage) {
        return deals.stream().filter(d -> d.kind() != null && !DealKind.INVESTICIA.name().equals(d.kind()))
                .filter(d -> stage.test(DealStage.parse(d.stage()))).map(Deal::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @PostMapping("/financovanie")
    String create(PartnerService.DealInput in, @RequestParam(required = false) String back, Authentication auth,
                  RedirectAttributes redirect) {
        try {
            long id = this.service.createDeal(in, CurrentUser.name(auth));
            redirect.addFlashAttribute("message", "Dohoda je zapísaná.");
            return "redirect:/financovanie/" + id;
        } catch (PartnerException e) {
            redirect.addFlashAttribute("errors", e.errors());
            return "redirect:" + (back != null && back.startsWith("/partneri/") ? back : "/financovanie");
        }
    }

    @GetMapping("/financovanie/{id}")
    String detail(@PathVariable long id, Model model, Authentication auth) {
        this.repo.deal(id, this.service.today()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAllAttributes(this.service.dealModel(id));
        model.addAttribute("attachments", this.attachments.visible(AttachmentRepository.Owner.DEAL, id,
                CurrentUser.isEditor(auth)));
        model.addAttribute("uploadUrl", "/financovanie/" + id + "/prilohy");
        model.addAttribute("today", this.service.today());
        model.addAttribute("dealKinds", DealKind.values());
        model.addAttribute("stages", DealStage.values());
        model.addAttribute("projects", this.projects.findAll());
        return "funding/detail";
    }

    @PostMapping("/financovanie/{id}")
    String update(@PathVariable long id, PartnerService.DealInput in, Authentication auth, RedirectAttributes redirect) {
        this.service.updateDeal(id, in, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Dohoda je uložená.");
        return "redirect:/financovanie/" + id;
    }

    @PostMapping("/financovanie/{id}/zmazat")
    String delete(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        Deal d = this.service.deal(id);
        this.service.deleteDeal(id, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Dohoda je zmazaná.");
        return "redirect:/partneri/" + d.partnerId();
    }

    @PostMapping("/financovanie/{id}/protiplnenia")
    String addDeliverable(@PathVariable long id, @RequestParam(required = false) String title,
                          @RequestParam(required = false) String dueOn, RedirectAttributes redirect) {
        this.service.addDeliverable(id, title, dueOn);
        return "redirect:/financovanie/" + id + "#protiplnenia";
    }

    @PostMapping("/financovanie/{id}/protiplnenia/{itemId}/hotovo")
    String toggleDeliverable(@PathVariable long id, @PathVariable long itemId, Authentication auth) {
        this.repo.toggleDeliverable(id, itemId, CurrentUser.name(auth));
        return "redirect:/financovanie/" + id + "#protiplnenia";
    }

    @PostMapping("/financovanie/{id}/protiplnenia/{itemId}/zmazat")
    String deleteDeliverable(@PathVariable long id, @PathVariable long itemId) {
        this.repo.deleteDeliverable(id, itemId);
        return "redirect:/financovanie/" + id + "#protiplnenia";
    }

    @PostMapping("/financovanie/{id}/polozky")
    String link(@PathVariable long id, @RequestParam(required = false) List<Long> entryIds, Authentication auth,
                RedirectAttributes redirect) {
        if (entryIds == null || entryIds.isEmpty()) {
            throw new PartnerException(List.of("Vyberte aspoň jednu položku."));
        }
        entryIds.forEach(e -> this.service.link(id, e, CurrentUser.name(auth)));
        redirect.addFlashAttribute("message", "Priradené položky: " + entryIds.size() + ".");
        return "redirect:/financovanie/" + id + "#polozky";
    }

    @PostMapping("/financovanie/{id}/polozky/{entryId}/odpojit")
    String unlink(@PathVariable long id, @PathVariable long entryId, Authentication auth) {
        this.service.unlink(id, entryId, CurrentUser.name(auth));
        return "redirect:/financovanie/" + id + "#polozky";
    }

    @GetMapping("/financovanie/{id}/vyuctovanie.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> settlement(@PathVariable long id, @PathVariable String format) {
        return this.service.settlement(id).response(format, "vyuctovanie-dohoda-" + id);
    }

    /** Chyba validacie: spat na stranku dohody s chybami (alebo 404, ak dohoda neexistuje). */
    @ExceptionHandler(PartnerException.class)
    String handle(PartnerException e, jakarta.servlet.http.HttpServletRequest request,
                  jakarta.servlet.http.HttpServletResponse response, RedirectAttributes redirect) throws java.io.IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/financovanie/(\\d+)").matcher(path);
        if (!m.find() || this.repo.deal(Long.parseLong(m.group(1)), this.service.today()).isEmpty()) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return null;
        }
        redirect.addFlashAttribute("errors", e.errors());
        return "redirect:/financovanie/" + m.group(1);
    }
}
