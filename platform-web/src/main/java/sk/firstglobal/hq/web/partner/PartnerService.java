package sk.firstglobal.hq.web.partner;

import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.customer.CustomerRepository;
import sk.firstglobal.hq.web.export.Table;
import sk.firstglobal.hq.web.ledger.LedgerService;
import sk.firstglobal.hq.web.people.PersonRepository;
import sk.firstglobal.hq.web.project.ProjectRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class PartnerService {
    private final PartnerRepository repo;
    private final PersonRepository people;
    private final CustomerRepository customers;
    private final ProjectRepository projects;
    private final AuditLog audit;
    private final Clock clock;

    public PartnerService(PartnerRepository repo, PersonRepository people, CustomerRepository customers,
                          ProjectRepository projects, AuditLog audit, Clock clock) {
        this.repo = repo;
        this.people = people;
        this.customers = customers;
        this.projects = projects;
        this.audit = audit;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(this.clock);
    }

    // ---------- partneri ----------

    public record PartnerInput(String name, String kind, String ico, String web, String contactName, String contactEmail,
                               String contactPhone, Long customerId, Long ownerPersonId, String tags, String note) {
    }

    public long createPartner(PartnerInput in, String actor) {
        PartnerRepository.PartnerRow row = this.validate(in, null);
        long id = this.repo.insert(row);
        this.audit.record(actor, "VYTVORENIE", "partner", id, row.name());
        return id;
    }

    public void updatePartner(long id, PartnerInput in, String actor) {
        this.repo.findById(id).orElseThrow(() -> new PartnerException(List.of("Partner neexistuje.")));
        PartnerRepository.PartnerRow row = this.validate(in, id);
        this.repo.update(id, row);
        this.audit.record(actor, "ZMENA", "partner", id, row.name());
    }

    private PartnerRepository.PartnerRow validate(PartnerInput in, Long id) {
        List<String> errors = new ArrayList<>();
        String name = trim(in.name());
        if (name == null) {
            errors.add("Názov partnera je povinný.");
        } else if (this.repo.nameTaken(name, id)) {
            errors.add("Partner „" + name + "“ už existuje.");
        }
        if (!PartnerKind.exists(in.kind())) {
            errors.add("Neznámy typ partnera.");
        }
        String ico = trim(in.ico());
        if (ico != null && !ico.replace(" ", "").matches("\\d{6,8}")) {
            errors.add("IČO má 6 až 8 číslic.");
        }
        String email = trim(in.contactEmail());
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            errors.add("E-mail kontaktu nie je platný.");
        }
        if (in.customerId() != null && this.customers.findById(in.customerId()).isEmpty()) {
            errors.add("Odberateľ neexistuje.");
        }
        if (in.ownerPersonId() != null && this.people.findById(in.ownerPersonId()).isEmpty()) {
            errors.add("Osoba zodpovedná za vzťah neexistuje.");
        }
        List<String> tags = LedgerService.normalizeTags(in.tags() == null ? List.of() : List.of(in.tags().split(",")),
                errors);
        if (!errors.isEmpty()) {
            throw new PartnerException(errors);
        }
        return new PartnerRepository.PartnerRow(name, in.kind(), ico == null ? null : ico.replace(" ", ""),
                trim(in.web()), trim(in.contactName()), email, trim(in.contactPhone()), in.customerId(),
                in.ownerPersonId(), tags, trim(in.note()));
    }

    public void addNote(long partnerId, String on, String text, String actor) {
        List<String> errors = new ArrayList<>();
        LocalDate date = on == null || on.isBlank() ? this.today() : date(on, "Dátum", errors);
        if (date != null && date.isAfter(this.today())) {
            errors.add("Záznam o komunikácii nemôže byť z budúcnosti - plán patrí do „ďalší krok“ pri dohode.");
        }
        if (trim(text) == null) {
            errors.add("Napíšte, čo sa udialo.");
        }
        if (!errors.isEmpty()) {
            throw new PartnerException(errors);
        }
        this.repo.addNote(partnerId, date, trim(text), actor);
    }

    // ---------- dohody ----------

    public record DealInput(Long partnerId, Long projectId, String title, String kind, String stage, String amount,
                            String expectedOn, String nextStep, String nextStepOn, String program, String appliedOn,
                            String periodFrom, String periodTo, String reportDueOn, String reportedOn, String note) {
    }

    public long createDeal(DealInput in, String actor) {
        PartnerRepository.DealRow row = this.validate(in);
        long id = this.repo.insertDeal(row);
        this.audit.record(actor, "VYTVORENIE", "dohoda", id, row.title() + " " + row.amount());
        return id;
    }

    public void updateDeal(long id, DealInput in, String actor) {
        Deal current = this.deal(id);
        PartnerRepository.DealRow row = this.validate(new DealInput(current.partnerId(), in.projectId(), in.title(),
                in.kind(), in.stage(), in.amount(), in.expectedOn(), in.nextStep(), in.nextStepOn(), in.program(),
                in.appliedOn(), in.periodFrom(), in.periodTo(), in.reportDueOn(), in.reportedOn(), in.note()));
        if (!row.kind().equals(current.kind()) && this.repo.linkedCount(id) > 0
                && (DealKind.GRANT.name().equals(current.kind()) || DealKind.GRANT.name().equals(row.kind()))) {
            throw new PartnerException(List.of("Dohoda má prepojené položky - typ z/na grant sa nedá zmeniť, "
                    + "najprv položky odpojte."));
        }
        this.repo.updateDeal(id, row);
        this.audit.record(actor, "ZMENA", "dohoda", id, row.title() + " " + row.stage() + " " + row.amount());
    }

    @Transactional
    public void deleteDeal(long id, String actor) {
        Deal d = this.deal(id);
        if (this.repo.linkedCount(id) > 0) {
            throw new PartnerException(List.of("Dohoda má prepojené položky - najprv ich odpojte. "
                    + "Ak dohoda neklapla, nastavte stav Odmietnuté (história ostane)."));
        }
        this.repo.deleteDeal(id);
        this.audit.record(actor, "ZMAZANIE", "dohoda", id, d.title());
    }

    public Deal deal(long id) {
        return this.repo.deal(id, this.today()).orElseThrow(() -> new PartnerException(List.of("Dohoda neexistuje.")));
    }

    private PartnerRepository.DealRow validate(DealInput in) {
        List<String> errors = new ArrayList<>();
        if (in.partnerId() == null || this.repo.findById(in.partnerId()).isEmpty()) {
            errors.add("Vyberte partnera.");
        }
        if (in.projectId() != null && this.projects.findAll().stream().noneMatch(p -> in.projectId().equals(p.id()))) {
            errors.add("Aktivita neexistuje.");
        }
        String title = trim(in.title());
        if (title == null) {
            errors.add("Názov dohody je povinný (napr. Hlavný partner NK 2027).");
        }
        if (!DealKind.exists(in.kind())) {
            errors.add("Neznámy typ financovania.");
        }
        DealStage stage = DealStage.parse(in.stage() == null ? "OSLOVENY" : in.stage());
        if (stage == null) {
            errors.add("Neznámy stav.");
        }
        BigDecimal amount = BigDecimal.ZERO;
        try {
            String a = in.amount() == null ? "" : in.amount().replaceAll("\\s", "").replace(',', '.');
            amount = a.isEmpty() ? BigDecimal.ZERO : new BigDecimal(a);
            if (amount.signum() < 0 || amount.scale() > 2) {
                errors.add("Suma musí byť kladná, najviac 2 desatinné miesta.");
            }
        } catch (NumberFormatException e) {
            errors.add("Suma musí byť číslo.");
        }
        if (stage != null && stage.isSecured() && amount.signum() == 0) {
            errors.add("Dohodnutá alebo zaplatená dohoda musí mať sumu.");
        }
        LocalDate expected = date(in.expectedOn(), "Očakávaná platba", errors);
        LocalDate nextOn = date(in.nextStepOn(), "Termín ďalšieho kroku", errors);
        LocalDate applied = date(in.appliedOn(), "Dátum podania", errors);
        LocalDate from = date(in.periodFrom(), "Začiatok obdobia", errors);
        LocalDate to = date(in.periodTo(), "Koniec obdobia", errors);
        LocalDate reportDue = date(in.reportDueOn(), "Termín vyúčtovania", errors);
        LocalDate reported = date(in.reportedOn(), "Dátum odovzdania vyúčtovania", errors);
        if (from != null && to != null && to.isBefore(from)) {
            errors.add("Oprávnené obdobie končí pred začiatkom.");
        }
        if (reported != null && reported.isAfter(this.today())) {
            errors.add("Vyúčtovanie nemôže byť odovzdané v budúcnosti.");
        }
        boolean grant = DealKind.GRANT.name().equals(in.kind());
        if (grant && stage != null && stage.isSecured() && (from == null || to == null)) {
            errors.add("Schválený grant potrebuje oprávnené obdobie (od - do) - bez neho nevieme skontrolovať čerpanie.");
        }
        if (!errors.isEmpty()) {
            throw new PartnerException(errors);
        }
        return new PartnerRepository.DealRow(in.partnerId(), in.projectId(), title, in.kind(), stage.name(), amount,
                expected, trim(in.nextStep()), nextOn, grant ? trim(in.program()) : null, grant ? applied : null,
                grant ? from : null, grant ? to : null, grant ? reportDue : null, grant ? reported : null,
                trim(in.note()));
    }

    // ---------- protiplnenia a polozky ----------

    public void addDeliverable(long dealId, String title, String dueOn) {
        this.deal(dealId);
        List<String> errors = new ArrayList<>();
        LocalDate due = date(dueOn, "Termín", errors);
        if (trim(title) == null) {
            errors.add("Napíšte protiplnenie (napr. logo na robote a dresoch).");
        }
        if (!errors.isEmpty()) {
            throw new PartnerException(errors);
        }
        this.repo.addDeliverable(dealId, trim(title), due);
    }

    @Transactional
    public void link(long dealId, long entryId, String actor) {
        Deal d = this.deal(dealId);
        PartnerRepository.LinkedEntry e = this.repo.entry(entryId)
                .orElseThrow(() -> new PartnerException(List.of("Položka neexistuje.")));
        if (e.dealId() != null) {
            throw new PartnerException(List.of("Položka už patrí k inej dohode - najprv ju tam odpojte."));
        }
        if (!e.isIncome() && !d.isGrant()) {
            throw new PartnerException(List.of("K sponzorovi či darcovi patria len príjmy. Výdavky sa priraďujú ku grantu."));
        }
        if (this.repo.link(entryId, dealId, actor) == 1) {
            this.audit.record(actor, "PREPOJENIE", "dohoda", dealId, "položka " + entryId);
        }
    }

    @Transactional
    public void unlink(long dealId, long entryId, String actor) {
        if (this.repo.unlink(entryId, dealId, actor) == 1) {
            this.audit.record(actor, "ODPOJENIE", "dohoda", dealId, "položka " + entryId);
        }
    }

    /**
     * Vyuctovanie grantu (alebo prehlad platieb od partnera) - podklad pre grantora a uctovnika.
     * Interne upozornenia sem nepatria: dokument odchadza von, stav obdobia ukazuje stlpec "V obdobi".
     */
    public Table settlement(long dealId) {
        Deal d = this.deal(dealId);
        List<PartnerRepository.LinkedEntry> entries = this.repo.linkedEntries(dealId);
        Table t = new Table((d.isGrant() ? "Vyúčtovanie grantu - " : "Platby - ") + d.title(), "Dátum", "Doklad",
                "Popis", "Dodávateľ / platiteľ", "Aktivita", "Typ", "Suma (€)", "V období");
        List<String> sub = new ArrayList<>(List.of(d.partnerName()));
        if (d.program() != null) {
            sub.add(d.program());
        }
        if (d.periodFrom() != null) {
            sub.add("oprávnené obdobie " + Deal.fmt(d.periodFrom()) + " - " + Deal.fmt(d.periodTo()));
        }
        t.subtitle(String.join(" · ", sub));
        for (PartnerRepository.LinkedEntry e : entries) {
            boolean in = d.periodFrom() == null || (!e.entryDate().isBefore(d.periodFrom()) && !e.entryDate().isAfter(d.periodTo()));
            t.row(e.entryDate(), e.documentRef(), e.description(), e.counterparty(), e.projectCode(),
                    e.isIncome() ? "Príjem" : "Výdavok", e.amount(), e.isIncome() ? "" : in ? "áno" : "NIE");
        }
        t.note("Schválená suma " + Table.text(d.amount()) + " € · prijaté " + Table.text(d.received()) + " € · čerpané "
                + Table.text(d.spent()) + " €" + (d.isGrant() ? " · zostatok " + Table.text(d.remainingToSpend()) + " €" : ""));
        if (d.isGrant()) {
            t.note("Vypracoval: ______________________   Dátum: __________   Podpis štatutára: ______________________");
        }
        return t;
    }

    public Map<String, Object> dealModel(long dealId) {
        Deal d = this.deal(dealId);
        return Map.of("d", d, "warnings", d.warnings(this.today()),
                "deliverables", this.repo.deliverables(dealId),
                "entries", this.repo.linkedEntries(dealId),
                "candidates", this.repo.candidates(d.projectId(), !d.isGrant()));
    }

    static LocalDate date(String raw, String label, List<String> errors) {
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

    static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
