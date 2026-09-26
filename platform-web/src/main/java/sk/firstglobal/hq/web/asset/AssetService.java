package sk.firstglobal.hq.web.asset;

import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.people.PersonRepository;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class AssetService {
    private final AssetRepository repo;
    private final PersonRepository people;
    private final AuditLog audit;
    private final Clock clock;

    public AssetService(AssetRepository repo, PersonRepository people, AuditLog audit, Clock clock) {
        this.repo = repo;
        this.people = people;
        this.audit = audit;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(this.clock);
    }

    public record AssetInput(String inventoryNo, String name, String category, String serialNo, String purchasedOn,
                             String price, Long ledgerEntryId, Long dealId, String keepUntil, String location,
                             String note) {
    }

    @Transactional
    public long create(AssetInput in, String actor) {
        AssetRepository.Row row = this.validate(in, null);
        long id = this.repo.insert(row);
        this.audit.record(actor, "VYTVORENIE", "majetok", id, row.inventoryNo() + " " + row.name());
        return id;
    }

    public void update(long id, AssetInput in, String actor) {
        this.asset(id);
        AssetRepository.Row row = this.validate(in, id);
        this.repo.update(id, row);
        this.audit.record(actor, "ZMENA", "majetok", id, row.inventoryNo() + " " + row.name());
    }

    private AssetRepository.Row validate(AssetInput in, Long id) {
        List<String> errors = new ArrayList<>();
        String no = trim(in.inventoryNo());
        if (no == null) {
            no = this.repo.nextInventoryNo(this.today().getYear());
        } else if (this.repo.inventoryNoTaken(no, id)) {
            errors.add("Inventárne číslo " + no + " už existuje.");
        }
        String name = trim(in.name());
        if (name == null) {
            errors.add("Názov je povinný (napr. REV Control Hub, notebook Lenovo).");
        }
        if (!AssetCategory.exists(in.category())) {
            errors.add("Neznáma kategória.");
        }
        LocalDate purchased = date(in.purchasedOn(), "Dátum kúpy", errors);
        if (purchased != null && purchased.isAfter(this.today())) {
            errors.add("Dátum kúpy nemôže byť v budúcnosti.");
        }
        BigDecimal price = null;
        String p = in.price() == null ? "" : in.price().replaceAll("\\s", "").replace(',', '.');
        if (!p.isEmpty()) {
            try {
                price = new BigDecimal(p);
                if (price.signum() < 0 || price.scale() > 2) {
                    errors.add("Cena musí byť kladná, najviac 2 desatinné miesta.");
                }
            } catch (NumberFormatException e) {
                errors.add("Cena musí byť číslo.");
            }
        }
        LocalDate keep = date(in.keepUntil(), "Udržať do", errors);
        if (!errors.isEmpty()) {
            throw new AssetException(errors);
        }
        return new AssetRepository.Row(no, name, in.category(), trim(in.serialNo()), purchased, price,
                in.ledgerEntryId(), in.dealId(), keep, trim(in.location()), trim(in.note()));
    }

    @Transactional
    public void lend(long assetId, Long personId, Long projectId, String dueOn, String note, String actor) {
        Asset a = this.asset(assetId);
        List<String> errors = new ArrayList<>();
        if (a.isRetired()) {
            errors.add("Vyradený majetok sa nepožičiava.");
        }
        if (a.isLent()) {
            errors.add("Už je požičané (" + a.borrowerName() + "). Najprv zapíšte vrátenie.");
        }
        if (personId == null || this.people.findById(personId).isEmpty()) {
            errors.add("Vyberte, komu požičiavate.");
        }
        LocalDate due = date(dueOn, "Vrátiť do", errors);
        if (due != null && due.isBefore(this.today())) {
            errors.add("Termín vrátenia je v minulosti.");
        }
        if (!errors.isEmpty()) {
            throw new AssetException(errors);
        }
        try {
            this.repo.lend(assetId, personId, projectId, this.today(), due, trim(note), actor);
        } catch (DuplicateKeyException e) {
            throw new AssetException(List.of("Medzitým to požičal niekto iný."));
        }
        this.audit.record(actor, "VYPOZICKA", "majetok", assetId, "osoba " + personId);
    }

    public void giveBack(long assetId, String on, String actor) {
        Asset a = this.asset(assetId);
        List<String> errors = new ArrayList<>();
        LocalDate date = on == null || on.isBlank() ? this.today() : date(on, "Dátum vrátenia", errors);
        if (!a.isLent()) {
            errors.add("Nie je požičané.");
        } else if (date != null && (date.isAfter(this.today()) || date.isBefore(a.lentOn()))) {
            errors.add("Dátum vrátenia musí byť medzi požičaním a dneškom.");
        }
        if (!errors.isEmpty()) {
            throw new AssetException(errors);
        }
        this.repo.returnLoan(assetId, date);
        this.audit.record(actor, "VRATENIE", "majetok", assetId, a.borrowerName());
    }

    public void repair(long assetId, boolean inRepair, String actor) {
        Asset a = this.asset(assetId);
        if (a.isRetired()) {
            throw new AssetException(List.of("Vyradený majetok."));
        }
        this.repo.setStatus(assetId, inRepair ? "OPRAVA" : "AKTIVNY");
        this.audit.record(actor, "ZMENA", "majetok", assetId, inRepair ? "oprava" : "k dispozícii");
    }

    /** Vyradenie: nie pozicane, s dovodom, a majetok z grantu nie pred koncom udrzatelnosti. */
    public void retire(long assetId, String on, String reason, String actor) {
        Asset a = this.asset(assetId);
        List<String> errors = new ArrayList<>();
        LocalDate date = on == null || on.isBlank() ? this.today() : date(on, "Dátum vyradenia", errors);
        if (a.isRetired()) {
            errors.add("Už je vyradené.");
        }
        if (a.isLent()) {
            errors.add("Je požičané - najprv zapíšte vrátenie.");
        }
        if (trim(reason) == null) {
            errors.add("Uveďte dôvod vyradenia (pokazené, stratené, predané, darované...).");
        }
        if (date != null && date.isAfter(this.today())) {
            errors.add("Dátum vyradenia nemôže byť v budúcnosti.");
        }
        if (date != null && a.keepUntil() != null && date.isBefore(a.keepUntil())) {
            errors.add("Kúpené z grantu - podľa zmluvy ho treba udržať do " + Asset.fmt(a.keepUntil())
                    + ". Predčasné vyradenie konzultujte s grantorom.");
        }
        if (!errors.isEmpty()) {
            throw new AssetException(errors);
        }
        this.repo.retire(assetId, date, trim(reason));
        this.audit.record(actor, "VYRADENIE", "majetok", assetId, trim(reason));
    }

    public Asset asset(long id) {
        return this.repo.findById(id).orElseThrow(() -> new AssetException(List.of("Majetok neexistuje.")));
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
}
