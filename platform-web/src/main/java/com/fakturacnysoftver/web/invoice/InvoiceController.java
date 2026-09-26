package com.fakturacnysoftver.web.invoice;

import com.fakturacnysoftver.core.Invoice;
import com.fakturacnysoftver.web.customer.CustomerRepository;
import com.fakturacnysoftver.web.organization.Organization;
import com.fakturacnysoftver.web.organization.OrganizationRepository;
import com.fakturacnysoftver.web.project.ProjectRepository;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/faktury")
class InvoiceController {
    private final InvoiceService service;
    private final InvoiceRepository invoices;
    private final CustomerRepository customers;
    private final ProjectRepository projects;
    private final OrganizationRepository organizations;

    InvoiceController(InvoiceService service, InvoiceRepository invoices, CustomerRepository customers,
                      ProjectRepository projects, OrganizationRepository organizations) {
        this.service = service;
        this.invoices = invoices;
        this.customers = customers;
        this.projects = projects;
        this.organizations = organizations;
    }

    @GetMapping
    String list(Model model) {
        model.addAttribute("invoices", this.invoices.findAll());
        return "invoices/list";
    }

    @GetMapping("/nova")
    String newInvoice(Model model) {
        InvoiceForm form = new InvoiceForm();
        form.getLines().add(new InvoiceForm.LineForm());
        return this.form(model, form, List.of());
    }

    @PostMapping
    String issue(@ModelAttribute("form") InvoiceForm form, Authentication auth, Model model,
                 RedirectAttributes redirect) {
        List<String> errors = new ArrayList<>();
        boolean vatPayer = this.organizations.find().map(Organization::isVatPayer).orElse(false);
        InvoiceDraft draft = form.toDraft(vatPayer, errors);
        if (errors.isEmpty()) {
            try {
                long id = this.service.issue(draft, CurrentUser.name(auth));
                redirect.addFlashAttribute("message", "Faktúra bola vystavená.");
                return "redirect:/faktury/" + id;
            } catch (InvoiceValidationException e) {
                errors.addAll(e.errors());
            }
        }
        if (form.getLines().isEmpty()) {
            form.getLines().add(new InvoiceForm.LineForm());
        }
        return this.form(model, form, errors);
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Model model) {
        InvoiceSummary summary = this.invoices.findSummary(id).orElseThrow(NotFound::new);
        Invoice invoice = this.service.load(id);
        model.addAttribute("s", summary);
        model.addAttribute("inv", invoice);
        model.addAttribute("totals", invoice.totals());
        model.addAttribute("creditNotes", this.invoices.findCreditNotes(id));
        return "invoices/detail";
    }

    @GetMapping("/{id}/dobropis")
    String newCreditNote(@PathVariable long id, Model model) {
        InvoiceSummary original = this.invoices.findSummary(id).orElseThrow(NotFound::new);
        if (original.isCreditNote()) {
            throw new NotFound();
        }
        return this.creditForm(model, original, InvoiceForm.fromLines(this.service.load(id).lines()), List.of());
    }

    @PostMapping("/{id}/dobropis")
    String issueCreditNote(@PathVariable long id, @ModelAttribute("form") InvoiceForm form, Authentication auth,
                           Model model, RedirectAttributes redirect) {
        InvoiceSummary original = this.invoices.findSummary(id).orElseThrow(NotFound::new);
        Invoice orig = this.service.load(id);
        List<String> errors = new ArrayList<>();
        var lines = form.parseLines(orig.seller().isVatRegistered(), errors);
        if (form.getReason() == null || form.getReason().isBlank()) {
            errors.add("Uveďte dôvod opravy.");
        }
        if (errors.isEmpty()) {
            try {
                long creditId = this.service.issueCreditNote(id, lines, form.getReason(), form.getIssueDate(),
                        CurrentUser.name(auth));
                redirect.addFlashAttribute("message", "Dobropis bol vystavený.");
                return "redirect:/faktury/" + creditId;
            } catch (InvoiceValidationException e) {
                errors.addAll(e.errors());
            }
        }
        if (form.getLines().isEmpty()) {
            form.getLines().add(new InvoiceForm.LineForm());
        }
        return this.creditForm(model, original, form, errors);
    }

    private String creditForm(Model model, InvoiceSummary original, InvoiceForm form, List<String> errors) {
        model.addAttribute("original", original);
        model.addAttribute("remaining", original.totalPayable().subtract(this.invoices.creditedTotal(original.id())));
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("vatPayer", this.service.load(original.id()).seller().isVatRegistered());
        return "invoices/credit-form";
    }

    @GetMapping("/{id}/pdf")
    ResponseEntity<byte[]> pdf(@PathVariable long id) {
        InvoiceSummary summary = this.invoices.findSummary(id).orElseThrow(NotFound::new);
        byte[] pdf = this.invoices.findPdf(id).orElseThrow(NotFound::new);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename("faktura-" + safe(summary.number()) + ".pdf").build().toString())
                .body(pdf);
    }

    @GetMapping("/{id}/xml")
    ResponseEntity<byte[]> ubl(@PathVariable long id) {
        InvoiceSummary summary = this.invoices.findSummary(id).orElseThrow(NotFound::new);
        String xml = this.invoices.findUbl(id).orElseThrow(NotFound::new);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("efaktura-" + safe(summary.number()) + ".xml").build().toString())
                .body(xml.getBytes(StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/uhrada")
    String markPaid(@PathVariable long id, @RequestParam(required = false) LocalDate paidOn, Authentication auth,
                    RedirectAttributes redirect) {
        this.invoices.findSummary(id).orElseThrow(NotFound::new);
        try {
            this.service.markPaid(id, paidOn, CurrentUser.name(auth));
        } catch (InvoiceValidationException e) {
            redirect.addFlashAttribute("errors", e.errors());
            return "redirect:/faktury/" + id;
        }
        redirect.addFlashAttribute("message", paidOn == null ? "Úhrada bola zrušená." : "Úhrada bola zaznamenaná.");
        return "redirect:/faktury/" + id;
    }

    private String form(Model model, InvoiceForm form, List<String> errors) {
        Organization org = this.organizations.find().orElse(null);
        model.addAttribute("form", form);
        model.addAttribute("errors", errors);
        model.addAttribute("customers", this.customers.findAll());
        model.addAttribute("projects", this.projects.findActive());
        model.addAttribute("orgReady", org != null && !org.name().isBlank());
        model.addAttribute("vatPayer", org != null && org.isVatPayer());
        return "invoices/form";
    }

    private static String safe(String number) {
        return number.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    static class NotFound extends ResponseStatusException {
        NotFound() {
            super(HttpStatus.NOT_FOUND);
        }
    }
}
