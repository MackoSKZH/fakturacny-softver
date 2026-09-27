package sk.firstglobal.hq.web.received;

import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.ledger.LedgerService;
import sk.firstglobal.hq.web.organization.OrganizationRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Kniha dosslych faktur: e-faktury UBL aj rucne zapisane, uhrady ako vydavky v polozkach. */
@Service
public class ReceivedInvoiceService {
    private final ReceivedInvoiceRepository repo;
    private final LedgerService ledger;
    private final OrganizationRepository organizations;
    private final AuditLog audit;
    private final Clock clock;

    public ReceivedInvoiceService(ReceivedInvoiceRepository repo, LedgerService ledger,
                                  OrganizationRepository organizations, AuditLog audit, Clock clock) {
        this.repo = repo;
        this.ledger = ledger;
        this.organizations = organizations;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public long importUbl(byte[] xml, Long projectId, String actor) {
        String sha = sha256(xml);
        if (this.repo.shaExists(sha)) {
            throw new ReceivedInvoiceException("Túto e-faktúru už máte nahratú.");
        }
        UblReader.Parsed p = UblReader.read(xml);
        String ourIco = this.organizations.find().map(o -> o.ico() == null ? "" : o.ico().replace(" ", "")).orElse("");
        if (p.buyerIco() != null && !ourIco.isEmpty() && !p.buyerIco().equals(ourIco)) {
            throw new ReceivedInvoiceException("E-faktúra je pre odberateľa s IČO " + p.buyerIco() + ", nie pre vás (" + ourIco
                    + "). Vráťte ju dodávateľovi.");
        }
        if (!"EUR".equals(p.currency())) {
            throw new ReceivedInvoiceException("E-faktúra je v mene " + p.currency() + ". Zapíšte ju ručne v eurách podľa "
                    + "skutočne zaplatenej sumy a XML priložte ako prílohu.");
        }
        long id = this.insert(new ReceivedInvoiceRepository.Row(p.creditNote() ? "DOBROPIS" : "FAKTURA", p.number(),
                p.supplierName(), p.supplierIco(), p.supplierDic(), p.supplierIban(), p.issueDate(), p.dueDate(),
                p.currency(), p.totalNet(), p.totalVat(), p.totalPayable(), p.paymentRef(), projectId, null, null, "UBL",
                new String(xml, StandardCharsets.UTF_8), sha), actor);
        this.audit.record(actor, "PRIJATIE", "dosla_faktura", id, p.supplierName() + " " + p.number() + " e-faktúra");
        return id;
    }

    public record ManualInput(String docType, String number, String supplierName, String supplierIco, String supplierIban,
                              String issueDate, String dueDate, String totalPayable, String paymentRef, Long projectId,
                              String category, String note) {
    }

    public long createManual(ManualInput in, String actor) {
        List<String> errors = new ArrayList<>();
        String number = trim(in.number());
        String supplier = trim(in.supplierName());
        if (number == null) {
            errors.add("Číslo faktúry dodávateľa je povinné.");
        }
        if (supplier == null) {
            errors.add("Dodávateľ je povinný.");
        }
        String ico = trim(in.supplierIco());
        if (ico != null && !ico.replace(" ", "").matches("\\d{6,8}")) {
            errors.add("IČO má 6 až 8 číslic.");
        }
        LocalDate issue = date(in.issueDate(), "Dátum vyhotovenia", errors);
        LocalDate due = date(in.dueDate(), "Splatnosť", errors);
        if (issue == null && errors.stream().noneMatch(e -> e.startsWith("Dátum vyhotovenia"))) {
            errors.add("Dátum vyhotovenia je povinný.");
        }
        if (issue != null && issue.isAfter(LocalDate.now(this.clock))) {
            errors.add("Dátum vyhotovenia nemôže byť v budúcnosti.");
        }
        if (issue != null && due != null && due.isBefore(issue)) {
            errors.add("Splatnosť je pred dátumom vyhotovenia.");
        }
        BigDecimal amount = null;
        try {
            String a = in.totalPayable() == null ? "" : in.totalPayable().replaceAll("\\s", "").replace(',', '.');
            amount = new BigDecimal(a);
            if (amount.signum() < 0 || amount.scale() > 2) {
                errors.add("Suma musí byť kladná, najviac 2 desatinné miesta.");
            }
        } catch (NumberFormatException e) {
            errors.add("Suma na úhradu musí byť číslo.");
        }
        if (!errors.isEmpty()) {
            throw new ValidationErrors(errors);
        }
        String type = "DOBROPIS".equals(in.docType()) ? "DOBROPIS" : "FAKTURA";
        long id = this.insert(new ReceivedInvoiceRepository.Row(type, number, supplier, ico == null ? null : ico.replace(" ", ""),
                null, trim(in.supplierIban()) == null ? null : in.supplierIban().replace(" ", "").toUpperCase(),
                issue, due, "EUR", null, null, amount, trim(in.paymentRef()), in.projectId(), trim(in.category()),
                trim(in.note()), "RUCNE", null, null), actor);
        this.audit.record(actor, "PRIJATIE", "dosla_faktura", id, supplier + " " + number);
        return id;
    }

    private long insert(ReceivedInvoiceRepository.Row row, String actor) {
        try {
            return this.repo.insert(row, actor);
        } catch (DuplicateKeyException e) {
            throw new ReceivedInvoiceException("Faktúru " + row.number() + " od dodávateľa " + row.supplierName()
                    + " už v knihe máte.");
        }
    }

    public void updateDetails(long id, Long projectId, String category, String note, String actor) {
        this.find(id);
        this.repo.updateDetails(id, projectId, trim(category), trim(note));
        this.audit.record(actor, "ZMENA", "dosla_faktura", id, "aktivita " + projectId);
    }

    /** Uhrada (alebo jej zrusenie s null) - zapise/zmaze vydavok v polozkach so zamknutou sumou. */
    @Transactional
    public long markPaid(long id, LocalDate paidOn, String actor) {
        ReceivedInvoiceRepository.ReceivedInvoice r = this.find(id);
        if (paidOn != null && paidOn.isAfter(LocalDate.now(this.clock))) {
            throw new ReceivedInvoiceException("Dátum úhrady nemôže byť v budúcnosti.");
        }
        if (paidOn != null && paidOn.isBefore(r.issueDate().minusDays(60))) {
            throw new ReceivedInvoiceException("Úhrada je viac než 60 dní pred vystavením faktúry - skontrolujte dátum (záloha?).");
        }
        this.repo.setPaid(id, paidOn);
        long entry = this.ledger.recordReceivedInvoicePayment(id, r.number(), r.isCreditNote(), r.totalPayable(), paidOn,
                r.projectId(), r.supplierName(), r.category(), r.paymentRef(), actor);
        this.audit.record(actor, "UHRADA", "dosla_faktura", id, paidOn == null ? "zrušená úhrada" : paidOn.toString());
        return entry;
    }

    public void delete(long id, String actor) {
        ReceivedInvoiceRepository.ReceivedInvoice r = this.find(id);
        if (this.repo.delete(id) == 0) {
            throw new ReceivedInvoiceException("Uhradenú faktúru nemožno zmazať - najprv zrušte úhradu.");
        }
        this.audit.record(actor, "ZMAZANIE", "dosla_faktura", id, r.supplierName() + " " + r.number());
    }

    public ReceivedInvoiceRepository.ReceivedInvoice find(long id) {
        return this.repo.find(id).orElseThrow(() -> new ReceivedInvoiceException("Faktúra neexistuje."));
    }

    /** Viac chyb naraz z formulara. */
    public static class ValidationErrors extends RuntimeException {
        private final List<String> errors;

        ValidationErrors(List<String> errors) {
            super(String.join(" ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return this.errors;
        }
    }

    private static LocalDate date(String raw, String label, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            errors.add(label + ": „" + raw.trim() + "“ nie je platný dátum.");
            return null;
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
