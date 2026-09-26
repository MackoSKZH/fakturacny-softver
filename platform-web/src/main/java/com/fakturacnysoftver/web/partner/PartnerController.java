package com.fakturacnysoftver.web.partner;

import com.fakturacnysoftver.web.attachment.AttachmentRepository;
import com.fakturacnysoftver.web.customer.CustomerRepository;
import com.fakturacnysoftver.web.people.PersonRepository;
import com.fakturacnysoftver.web.project.ProjectRepository;
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

import java.util.List;
import java.util.Locale;

/** /partneri - karta kazdeho partnera: kontakty, dohody, protiplnenia, faktury a historia komunikacie (editori). */
@Controller
class PartnerController {
    private final PartnerService service;
    private final PartnerRepository repo;
    private final PersonRepository people;
    private final CustomerRepository customers;
    private final ProjectRepository projects;
    private final AttachmentRepository attachments;

    PartnerController(PartnerService service, PartnerRepository repo, PersonRepository people,
                      CustomerRepository customers, ProjectRepository projects, AttachmentRepository attachments) {
        this.attachments = attachments;
        this.service = service;
        this.repo = repo;
        this.people = people;
        this.customers = customers;
        this.projects = projects;
    }

    @GetMapping("/partneri")
    String list(@RequestParam(required = false) String typ, @RequestParam(required = false) String q, Model model) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Partner> all = this.repo.findAll().stream()
                .filter(p -> typ == null || typ.isBlank() || p.kind().equals(typ))
                .filter(p -> query.isEmpty() || (p.name() + " " + nz(p.contactName()) + " " + String.join(" ", p.tags()))
                        .toLowerCase(Locale.ROOT).contains(query))
                .toList();
        model.addAttribute("partners", all);
        model.addAttribute("kinds", PartnerKind.values());
        model.addAttribute("typ", typ);
        model.addAttribute("q", q);
        model.addAttribute("today", this.service.today());
        this.formOptions(model);
        return "partners/list";
    }

    @PostMapping("/partneri")
    String create(PartnerService.PartnerInput in, Authentication auth, RedirectAttributes redirect) {
        try {
            long id = this.service.createPartner(in, CurrentUser.name(auth));
            redirect.addFlashAttribute("message", "Partner je pridaný. Zapíšte prvý kontakt a dohodu.");
            return "redirect:/partneri/" + id;
        } catch (PartnerException e) {
            redirect.addFlashAttribute("errors", e.errors());
            return "redirect:/partneri";
        }
    }

    @GetMapping("/partneri/{id}")
    String detail(@PathVariable long id, Model model) {
        Partner p = this.repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("p", p);
        model.addAttribute("deals", this.repo.dealsOf(id, this.service.today()));
        model.addAttribute("notes", this.repo.notes(id));
        model.addAttribute("invoices", p.customerId() == null ? List.of() : this.repo.invoices(p.customerId()));
        model.addAttribute("today", this.service.today());
        model.addAttribute("dealKinds", DealKind.values());
        model.addAttribute("stages", DealStage.values());
        model.addAttribute("projects", this.projects.findAll());
        model.addAttribute("kinds", PartnerKind.values());
        model.addAttribute("attachments", this.attachments.of(AttachmentRepository.Owner.PARTNER, id));
        model.addAttribute("uploadUrl", "/partneri/" + id + "/prilohy");
        this.formOptions(model);
        return "partners/detail";
    }

    @PostMapping("/partneri/{id}")
    String update(@PathVariable long id, PartnerService.PartnerInput in, Authentication auth, RedirectAttributes redirect) {
        try {
            this.service.updatePartner(id, in, CurrentUser.name(auth));
            redirect.addFlashAttribute("message", "Partner je uložený.");
        } catch (PartnerException e) {
            redirect.addFlashAttribute("errors", e.errors());
        }
        return "redirect:/partneri/" + id;
    }

    @PostMapping("/partneri/{id}/komunikacia")
    String addNote(@PathVariable long id, @RequestParam(required = false) String happenedOn,
                   @RequestParam(required = false) String text, Authentication auth, RedirectAttributes redirect) {
        this.repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        try {
            this.service.addNote(id, happenedOn, text, CurrentUser.name(auth));
        } catch (PartnerException e) {
            redirect.addFlashAttribute("errors", e.errors());
        }
        return "redirect:/partneri/" + id + "#komunikacia";
    }

    @PostMapping("/partneri/{id}/komunikacia/{noteId}/zmazat")
    String deleteNote(@PathVariable long id, @PathVariable long noteId) {
        this.repo.deleteNote(id, noteId);
        return "redirect:/partneri/" + id + "#komunikacia";
    }

    private void formOptions(Model model) {
        model.addAttribute("people", this.people.findAll());
        model.addAttribute("customers", this.customers.findAll());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
