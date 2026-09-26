package sk.firstglobal.hq.web.invoice;

import sk.firstglobal.hq.core.CreditNote;
import sk.firstglobal.hq.core.Invoice;
import sk.firstglobal.hq.core.InvoiceLine;
import sk.firstglobal.hq.core.InvoiceNumbering;
import sk.firstglobal.hq.core.InvoiceTotals;
import sk.firstglobal.hq.core.InvoiceValidator;
import sk.firstglobal.hq.core.ubl.UblInvoiceWriter;
import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.customer.Customer;
import sk.firstglobal.hq.web.customer.CustomerRepository;
import sk.firstglobal.hq.web.ledger.LedgerService;
import sk.firstglobal.hq.web.organization.Organization;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.pdf.InvoicePdfRenderer;
import sk.firstglobal.hq.web.project.Project;
import sk.firstglobal.hq.web.project.ProjectRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Vystavenie faktury v jednej transakcii: kontrola -> pridelenie cisla -> PDF + UBL -> ulozenie.
 * Ak cokolvek zlyha, transakcia sa vrati aj s cislom, takze v rade nevznikne medzera.
 */
@Service
public class InvoiceService {
    private final OrganizationRepository organizations;
    private final CustomerRepository customers;
    private final ProjectRepository projects;
    private final InvoiceRepository invoices;
    private final InvoiceNumberAllocator numbers;
    private final InvoicePdfRenderer pdf;
    private final AuditLog audit;
    private final LedgerService ledger;
    private final JsonMapper json;
    private final Clock clock;

    public InvoiceService(OrganizationRepository organizations, CustomerRepository customers,
                          ProjectRepository projects, InvoiceRepository invoices, InvoiceNumberAllocator numbers,
                          InvoicePdfRenderer pdf, AuditLog audit, JsonMapper json, Clock clock, LedgerService ledger) {
        this.ledger = ledger;
        this.organizations = organizations;
        this.customers = customers;
        this.projects = projects;
        this.invoices = invoices;
        this.numbers = numbers;
        this.pdf = pdf;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public long issue(InvoiceDraft draft, String actor) {
        List<String> errors = new ArrayList<>();
        Organization org = this.organizations.find().orElse(null);
        if (org == null || org.name() == null || org.name().isBlank()) {
            throw new InvoiceValidationException(List.of("Najprv vyplňte údaje organizácie v Nastaveniach."));
        }
        Customer customer = this.customers.findById(draft.customerId()).orElse(null);
        if (customer == null) {
            throw new InvoiceValidationException(List.of("Vyberte odberateľa."));
        }
        Project project = draft.projectId() == null ? null : this.projects.findById(draft.projectId()).orElse(null);
        if (draft.projectId() != null && project == null) {
            errors.add("Projekt neexistuje.");
        }

        LocalDate today = LocalDate.now(this.clock);
        LocalDate issueDate = draft.issueDate() == null ? today : draft.issueDate();
        if (issueDate.isAfter(today)) {
            errors.add("Dátum vyhotovenia nemôže byť v budúcnosti.");
        }
        LocalDate dueDate = draft.dueDate() == null ? issueDate.plusDays(org.dueDays()) : draft.dueDate();
        LocalDate deliveryDate = draft.deliveryDate() == null ? issueDate : draft.deliveryDate();

        Invoice provisional = this.build(org, customer, project, draft, "NEPRIDELENE", issueDate, deliveryDate,
                dueDate, blankToNull(draft.variableSymbol()));
        errors.addAll(InvoiceValidator.validate(provisional));
        if (!errors.isEmpty()) {
            throw new InvoiceValidationException(errors);
        }

        String number = this.numbers.next(org.invoicePattern(), issueDate.getYear());
        // Poradove cisla musia ist chronologicky. Kontrola az po prideleni cisla - vtedy drzime zamok radu,
        // takze subezne vystavenie nemoze medzitym vlozit fakturu s novsim datumom.
        this.invoices.latestIssueDate(issueDate.getYear())
                .filter(issueDate::isBefore)
                .ifPresent(last -> {
                    throw new InvoiceValidationException(List.of(
                            "Dátum vyhotovenia je starší než posledná faktúra (" + last + ")."));
                });
        String vs = blankToNull(draft.variableSymbol()) != null
                ? draft.variableSymbol().trim()
                : InvoiceNumbering.variableSymbolOf(number);
        Invoice invoice = this.build(org, customer, project, draft, number, issueDate, deliveryDate, dueDate, vs);
        List<String> finalErrors = InvoiceValidator.validate(invoice);
        if (!finalErrors.isEmpty()) {
            throw new InvoiceValidationException(finalErrors);
        }

        // E-fakturu (UBL) ukladame len ak ju je mozne dorucit cez Peppol - inak by to bol nevalidny dokument.
        String ubl = InvoiceValidator.validateForPeppol(invoice).isEmpty() ? UblInvoiceWriter.write(invoice) : null;
        byte[] pdfBytes = this.pdf.render(invoice, null, org.registrationNote(),
                project == null ? null : project.name(), actor);
        InvoiceTotals totals = invoice.totals();

        long id = this.invoices.insert(new InvoiceRepository.NewInvoice(number, issueDate, dueDate, customer.id(),
                project == null ? null : project.id(), customer.name(), totals.lineExtensionAmount(),
                totals.vatAmount(), totals.payableAmount(), invoice.currency(), this.json.writeValueAsString(invoice),
                ubl, pdfBytes, actor, InvoiceSummary.INVOICE, null, null));
        this.audit.record(actor, "VYSTAVENIE", "faktura", id,
                number + " " + customer.name() + " " + totals.payableAmount() + " " + invoice.currency());
        return id;
    }

    /**
     * Dobropis k vystavenej fakture. Strany, mena a platobne udaje sa preberaju z povodnej faktury
     * (nie z aktualnych nastaveni), aby oprava sedela s opravovanym dokladom. Sucet dobropisov
     * nesmie prekrocit sumu faktury - kontrola bezi pod zamkom povodnej faktury.
     */
    @Transactional
    public long issueCreditNote(long invoiceId, List<InvoiceLine> lines, String reason, LocalDate requestedDate,
                                String actor) {
        InvoiceSummary original = this.invoices.findSummary(invoiceId)
                .orElseThrow(() -> new InvoiceValidationException(List.of("Faktúra neexistuje.")));
        if (original.isCreditNote()) {
            throw new InvoiceValidationException(List.of("Dobropis sa vystavuje k faktúre, nie k dobropisu."));
        }
        Organization org = this.organizations.find()
                .orElseThrow(() -> new InvoiceValidationException(List.of("Chýbajú údaje organizácie.")));
        Invoice orig = this.load(invoiceId);
        LocalDate today = LocalDate.now(this.clock);
        LocalDate issueDate = requestedDate == null ? today : requestedDate;
        List<String> errors = new ArrayList<>();
        if (issueDate.isAfter(today)) {
            errors.add("Dátum vyhotovenia nemôže byť v budúcnosti.");
        }
        CreditNote draft = this.creditNote(orig, "NEPRIDELENE", issueDate, lines, reason, org.dueDays());
        errors.addAll(draft.validate());
        if (!errors.isEmpty()) {
            throw new InvoiceValidationException(errors);
        }

        this.invoices.lockForCredit(invoiceId);
        BigDecimal remaining = original.totalPayable().subtract(this.invoices.creditedTotal(invoiceId));
        BigDecimal amount = draft.totals().payableAmount();
        if (amount.signum() <= 0) {
            throw new InvoiceValidationException(List.of("Suma dobropisu musí byť kladná."));
        }
        if (amount.compareTo(remaining) > 0) {
            throw new InvoiceValidationException(List.of("Dobropis " + amount + " € prevyšuje zostatok faktúry "
                    + remaining + " € (po započítaní skorších dobropisov)."));
        }

        String number = this.numbers.next("D" + org.invoicePattern(), issueDate.getYear());
        this.invoices.latestIssueDate(issueDate.getYear())
                .filter(issueDate::isBefore)
                .ifPresent(last -> {
                    throw new InvoiceValidationException(List.of(
                            "Dátum vyhotovenia je starší než posledný doklad (" + last + ")."));
                });
        CreditNote cn = this.creditNote(orig, number, issueDate, lines, reason, org.dueDays());
        String ubl = cn.validateForPeppol().isEmpty() ? UblInvoiceWriter.write(cn) : null;
        Project project = null;
        if (orig.projectCode() != null) {
            project = this.projects.findAll().stream().filter(p -> p.code().equals(orig.projectCode())).findFirst()
                    .orElse(null);
        }
        byte[] pdfBytes = this.pdf.render(cn.body(), cn, org.registrationNote(),
                project == null ? null : project.name(), actor);
        InvoiceTotals totals = cn.totals();
        long customerId = this.invoices.customerIdOf(invoiceId);
        long id = this.invoices.insert(new InvoiceRepository.NewInvoice(number, issueDate, cn.body().dueDate(),
                customerId, project == null ? null : project.id(), orig.buyer().name(),
                totals.lineExtensionAmount(), totals.vatAmount(), totals.payableAmount(), orig.currency(),
                this.json.writeValueAsString(cn.body()), ubl, pdfBytes, actor, InvoiceSummary.CREDIT_NOTE, invoiceId,
                reason.trim()));
        this.audit.record(actor, "DOBROPIS", "faktura", id, number + " k " + orig.number() + " "
                + totals.payableAmount() + " " + orig.currency() + ": " + reason.trim());
        return id;
    }

    private CreditNote creditNote(Invoice orig, String number, LocalDate issueDate, List<InvoiceLine> lines,
                                  String reason, int dueDays) {
        Invoice body = new Invoice(number, issueDate, issueDate, issueDate.plusDays(dueDays), orig.currency(),
                orig.seller(), orig.buyer(), lines, orig.variableSymbol(), orig.payeeIban(), orig.payeeBic(),
                orig.buyerReference(), orig.projectCode(), null);
        return new CreditNote(body, orig.number(), orig.issueDate(), reason == null ? null : reason.trim());
    }

    public Invoice load(long id) {
        return this.invoices.findDocument(id)
                .map(doc -> this.json.readValue(doc, Invoice.class))
                .orElseThrow();
    }

    @Transactional
    public void markPaid(long id, LocalDate paidOn, String actor) {
        if (paidOn != null && paidOn.isAfter(LocalDate.now(this.clock))) {
            throw new InvoiceValidationException(List.of("Dátum úhrady nemôže byť v budúcnosti."));
        }
        if (this.invoices.markPaid(id, paidOn) == 1) {
            this.audit.record(actor, "UHRADA", "faktura", id, paidOn == null ? "zrušená úhrada" : paidOn.toString());
            InvoiceSummary s = this.invoices.findSummary(id).orElseThrow();
            this.ledger.recordInvoicePayment(id, s.number(), s.isCreditNote(), s.totalPayable(), paidOn,
                    this.invoices.projectIdOf(id), s.buyerName(), actor);
        }
    }

    private Invoice build(Organization org, Customer customer, Project project, InvoiceDraft d, String number,
                          LocalDate issueDate, LocalDate deliveryDate, LocalDate dueDate, String vs) {
        return new Invoice(number, issueDate, deliveryDate, dueDate, "EUR", org.toParty(), customer.toParty(),
                d.lines(), vs, blankToNull(org.iban()), blankToNull(org.bic()), blankToNull(d.buyerReference()),
                project == null ? null : project.code(), blankToNull(d.note()));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
