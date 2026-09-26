package sk.firstglobal.hq.web.bank;

import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.donation.DonationRepository;
import sk.firstglobal.hq.web.invoice.InvoiceService;
import sk.firstglobal.hq.web.ledger.LedgerEntry;
import sk.firstglobal.hq.web.ledger.LedgerInput;
import sk.firstglobal.hq.web.ledger.LedgerService;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.partner.PartnerService;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parovanie pohybov z vypisu: uhrada faktury (VS + presna suma), dar na aktivitu (VS aktivity), inak nova polozka.
 * Nic sa nezapise bez potvrdenia v nahlade a cely vypis sa zapise naraz alebo vobec (transakcia).
 */
@Service
public class BankImportService {
    public enum Action { FAKTURA, DOSLA, DOHODA, DAR, POLOZKA, PRESKOCIT, DUPLIKAT }

    /** Navrh pre jeden riadok vypisu. Pri DOHODA je invoiceId id dohody a invoiceNumber jej nazov. */
    public record Proposal(int index, Camt053Parser.Line line, Action action, Long invoiceId, String invoiceNumber,
                           Long projectId, String reason) implements Serializable {
    }

    public record Preview(Camt053Parser.Statement statement, List<Proposal> proposals, List<String> warnings)
            implements Serializable {
    }

    /** Rozhodnutie pouzivatela v nahlade (akcia a aktivita). */
    public record Decision(int index, Action action, Long projectId) {
    }

    public record Result(int invoices, int deals, int donations, int entries, int skipped) {
    }

    private record OpenDeal(long id, String vs, String label, Long projectId, String kind, BigDecimal amount,
                            BigDecimal received) {
    }

    private record UnpaidInvoice(long id, String number, BigDecimal totalPayable, String vs, Long projectId) {
    }

    private record UnpaidReceived(long id, String number, String supplierName, BigDecimal totalPayable, String vs,
                                  String iban) {
    }

    private final JdbcClient jdbc;
    private final sk.firstglobal.hq.web.received.ReceivedInvoiceService received;
    private final PartnerService partners;
    private final InvoiceService invoices;
    private final LedgerService ledger;
    private final OrganizationRepository organizations;
    private final AuditLog audit;
    private final Clock clock;

    public BankImportService(JdbcClient jdbc, InvoiceService invoices, LedgerService ledger,
                             OrganizationRepository organizations, AuditLog audit, Clock clock,
                             sk.firstglobal.hq.web.received.ReceivedInvoiceService received, PartnerService partners) {
        this.jdbc = jdbc;
        this.partners = partners;
        this.received = received;
        this.invoices = invoices;
        this.ledger = ledger;
        this.organizations = organizations;
        this.audit = audit;
        this.clock = clock;
    }

    public Preview preview(Camt053Parser.Statement st) {
        List<String> warnings = new ArrayList<>();
        String ourIban = this.organizations.find().map(o -> o.iban() == null ? "" : o.iban().replace(" ", "")).orElse("");
        if (st.iban() != null && !ourIban.isEmpty() && !st.iban().equalsIgnoreCase(ourIban)) {
            warnings.add("Výpis je z účtu " + st.iban() + ", ale v Nastaveniach je IBAN " + ourIban + ". Skontrolujte, či je to správny účet.");
        }
        if (st.skippedPending() > 0) {
            warnings.add(st.skippedPending() + " nezaúčtovaných pohybov (čakajúcich) sme vynechali - naimportujte ich z ďalšieho výpisu.");
        }
        if (st.lines().isEmpty()) {
            warnings.add("Výpis neobsahuje žiadne zaúčtované pohyby.");
        }
        List<UnpaidInvoice> unpaid = this.jdbc.sql("""
                        SELECT id, number, total_payable, ltrim(COALESCE(document->>'variableSymbol', ''), '0') AS vs, project_id
                        FROM invoice WHERE paid_on IS NULL AND doc_type = 'FAKTURA'""")
                .query(UnpaidInvoice.class).list();
        List<UnpaidReceived> payables = this.jdbc.sql("""
                        SELECT id, number, supplier_name, total_payable, ltrim(COALESCE(payment_ref, ''), '0') AS vs,
                               supplier_iban AS iban
                        FROM received_invoice WHERE paid_on IS NULL AND doc_type = 'FAKTURA'""")
                .query(UnpaidReceived.class).list();
        List<OpenDeal> deals = this.jdbc.sql("""
                        SELECT d.id, COALESCE(d.payment_vs, (700000 + d.id)::text) AS vs, pa.name || ' - ' || d.title AS label,
                               d.project_id, d.kind, d.amount,
                               COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.deal_id = d.id
                                         AND l.direction = 'PRIJEM'), 0) AS received
                        FROM deal d JOIN partner pa ON pa.id = d.partner_id
                        WHERE d.stage <> 'ODMIETNUTE' AND d.kind <> 'VECNE'""")
                .query(OpenDeal.class).list();
        List<Proposal> out = new ArrayList<>();
        java.util.Set<Long> usedReceived = new java.util.HashSet<>();
        java.util.Set<Long> used = new java.util.HashSet<>();
        java.util.Set<String> refs = new java.util.HashSet<>();
        for (int i = 0; i < st.lines().size(); i++) {
            Camt053Parser.Line l = st.lines().get(i);
            Proposal p = !refs.add(l.ref()) ? new Proposal(i, l, Action.DUPLIKAT, null, null, null, "rovnaký pohyb je vo výpise dvakrát")
                    : this.propose(i, l, unpaid, deals);
            if (p.action() == Action.POLOZKA && !l.credit()) {
                p = this.proposePayable(p, l, payables, usedReceived);
            }
            if (p.action() == Action.FAKTURA && !used.add(p.invoiceId())) {
                p = new Proposal(i, l, Action.POLOZKA, null, null, p.projectId(), "faktúru " + p.invoiceNumber()
                        + " už uhrádza iný riadok výpisu - druhá platba? Skontrolujte.");
            }
            out.add(p);
        }
        return new Preview(st, out, warnings);
    }

    private Proposal propose(int i, Camt053Parser.Line l, List<UnpaidInvoice> unpaid, List<OpenDeal> deals) {
        if (this.bankRefExists(l.ref())) {
            return new Proposal(i, l, Action.DUPLIKAT, null, null, null, "už je v položkách");
        }
        if (!"EUR".equalsIgnoreCase(l.currency())) {
            return new Proposal(i, l, Action.POLOZKA, null, null, null, "platba v " + l.currency() + " - skontrolujte sumu v eurách");
        }
        if (l.credit() && l.vs() != null) {
            Optional<UnpaidInvoice> inv = unpaid.stream().filter(u -> l.vs().equals(u.vs())).findFirst();
            if (inv.isPresent()) {
                UnpaidInvoice u = inv.get();
                if (u.totalPayable().compareTo(l.amount()) == 0) {
                    return new Proposal(i, l, Action.FAKTURA, u.id(), u.number(), u.projectId(), "VS a suma sedia s faktúrou " + u.number());
                }
                return new Proposal(i, l, Action.POLOZKA, null, null, u.projectId(), "VS faktúry " + u.number()
                        + ", ale suma nesedí (" + u.totalPayable().toPlainString() + " €) - čiastočná úhrada? Faktúru neoznačíme ako uhradenú.");
            }
            Optional<OpenDeal> deal = deals.stream().filter(d -> l.vs().equals(d.vs())).findFirst();
            if (deal.isPresent()) {
                OpenDeal d = deal.get();
                BigDecimal after = d.received().add(l.amount());
                String note = d.amount().signum() > 0 && after.compareTo(d.amount()) > 0
                        ? " - spolu " + after.toPlainString() + " € je viac ako dohodnutých " + d.amount().toPlainString() + " €, skontrolujte"
                        : d.amount().signum() > 0 ? " (prijaté spolu " + after.toPlainString() + " z " + d.amount().toPlainString() + " €)" : "";
                String kindNote = "REKLAMA".equals(d.kind()) ? " - reklama sa má platiť na faktúru, vystavte ju" : "";
                return new Proposal(i, l, Action.DOHODA, d.id(), d.label(), d.projectId(), "VS dohody " + d.label() + note + kindNote);
            }
            long vs = Long.parseLong(l.vs());
            long projectId = vs - DonationRepository.VS_BASE;
            if (projectId > 0 && projectId < DonationRepository.VS_BASE && this.projectExists(projectId)) {
                return new Proposal(i, l, Action.DAR, null, null, projectId, "VS zbierky aktivity");
            }
        }
        return new Proposal(i, l, Action.POLOZKA, null, null, null, l.vs() == null ? "bez VS" : "VS " + l.vs() + " nepoznáme");
    }

    /** Odchadzajuca platba: dossla faktura podla VS a sumy, inak podla IBAN dodavatela a sumy. */
    private Proposal proposePayable(Proposal p, Camt053Parser.Line l, List<UnpaidReceived> payables, java.util.Set<Long> used) {
        Optional<UnpaidReceived> byVs = l.vs() == null ? Optional.empty() : payables.stream()
                .filter(r -> !used.contains(r.id()) && l.vs().equals(r.vs()) && r.totalPayable().compareTo(l.amount()) == 0)
                .findFirst();
        Optional<UnpaidReceived> match = byVs.isPresent() ? byVs : payables.stream()
                .filter(r -> !used.contains(r.id()) && r.iban() != null && r.iban().equalsIgnoreCase(l.counterpartyIban())
                        && r.totalPayable().compareTo(l.amount()) == 0)
                .findFirst();
        if (match.isEmpty()) {
            return p;
        }
        UnpaidReceived r = match.get();
        used.add(r.id());
        return new Proposal(p.index(), l, Action.DOSLA, r.id(), r.number(), null, "došlá faktúra " + r.supplierName()
                + " " + r.number() + (byVs.isPresent() ? " (VS a suma)" : " (IBAN a suma)"));
    }

    @Transactional
    public Result apply(Preview preview, List<Decision> decisions, String actor) {
        int inv = 0;
        int dea = 0;
        int don = 0;
        int ent = 0;
        int skip = 0;
        LocalDate today = LocalDate.now(this.clock);
        for (Decision d : decisions) {
            Proposal p = preview.proposals().get(d.index());
            Camt053Parser.Line l = p.line();
            if (d.action() == Action.PRESKOCIT || d.action() == Action.DUPLIKAT || this.bankRefExists(l.ref())) {
                skip++;
                continue;
            }
            if (l.date() == null || l.date().isAfter(today)) {
                throw new BankImportException("Riadok " + (d.index() + 1) + ": dátum pohybu chýba alebo je v budúcnosti.");
            }
            switch (d.action()) {
                case FAKTURA -> {
                    if (p.invoiceId() == null) {
                        throw new BankImportException("Riadok " + (d.index() + 1) + ": k pohybu nie je navrhnutá faktúra.");
                    }
                    this.invoices.markPaid(p.invoiceId(), l.date(), actor);
                    this.jdbc.sql("UPDATE ledger_entry SET bank_ref = :r WHERE invoice_id = :i")
                            .param("r", l.ref()).param("i", p.invoiceId()).update();
                    inv++;
                }
                case DOSLA -> {
                    if (p.action() != Action.DOSLA || p.invoiceId() == null) {
                        throw new BankImportException("Riadok " + (d.index() + 1) + ": k pohybu nie je navrhnutá došlá faktúra.");
                    }
                    this.received.markPaid(p.invoiceId(), l.date(), actor);
                    this.jdbc.sql("UPDATE ledger_entry SET bank_ref = :r WHERE received_invoice_id = :i")
                            .param("r", l.ref()).param("i", p.invoiceId()).update();
                    inv++;
                }
                case DOHODA -> {
                    if (p.action() != Action.DOHODA || p.invoiceId() == null) {
                        throw new BankImportException("Riadok " + (d.index() + 1) + ": k pohybu nie je navrhnutá dohoda.");
                    }
                    String kind = this.jdbc.sql("SELECT kind FROM deal WHERE id = :id").param("id", p.invoiceId())
                            .query(String.class).optional()
                            .orElseThrow(() -> new BankImportException("Riadok " + (d.index() + 1) + ": dohoda medzitým zmizla."));
                    String category = switch (kind) {
                        case "GRANT" -> "Grant";
                        case "REKLAMA" -> "Reklama";
                        case "DAR" -> "Dar";
                        default -> null;
                    };
                    long entry = this.create(l, d.projectId() != null ? d.projectId() : p.projectId(),
                            "Platba - " + p.invoiceNumber(), category, actor);
                    this.partners.recordPayment(p.invoiceId(), entry, actor);
                    dea++;
                }
                case DAR -> {
                    this.create(l, d.projectId() != null ? d.projectId() : p.projectId(), "Dar - " + nz(l.counterparty(), "darca"),
                            "Dar", actor);
                    don++;
                }
                default -> {
                    this.create(l, d.projectId(), description(l), null, actor);
                    ent++;
                }
            }
        }
        this.audit.record(actor, "IMPORT", "vypis", preview.statement().iban(),
                "faktúry " + inv + ", dohody " + dea + ", dary " + don + ", položky " + ent + ", preskočené " + skip);
        return new Result(inv, dea, don, ent, skip);
    }

    private long create(Camt053Parser.Line l, Long projectId, String description, String category, String actor) {
        LedgerEntry e = this.ledger.create(new LedgerInput(l.date().toString(), description, l.credit() ? "PRIJEM" : "VYDAVOK",
                l.amount().toPlainString(), projectId, List.of(), category, l.counterparty(), l.vs(), "BANKA",
                l.message(), null), actor);
        this.jdbc.sql("UPDATE ledger_entry SET bank_ref = :r WHERE id = :id").param("r", l.ref()).param("id", e.id()).update();
        return e.id();
    }

    private static String description(Camt053Parser.Line l) {
        String d = l.message() != null ? l.message() : (l.credit() ? "Platba od " : "Platba pre ") + nz(l.counterparty(), "neznámy");
        return d.length() > 200 ? d.substring(0, 200) : d;
    }

    private boolean bankRefExists(String ref) {
        return this.jdbc.sql("SELECT count(*) FROM ledger_entry WHERE bank_ref = :r").param("r", ref)
                .query(Long.class).single() > 0;
    }

    private boolean projectExists(long id) {
        return this.jdbc.sql("SELECT count(*) FROM project WHERE id = :id").param("id", id).query(Long.class).single() > 0;
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
