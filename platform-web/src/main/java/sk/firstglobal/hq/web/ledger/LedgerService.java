package sk.firstglobal.hq.web.ledger;

import sk.firstglobal.hq.web.project.ProjectRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Pravidla pre polozky penazneho dennika. Kazda zmena ide cez tuto triedu, aby sa zapisal autor
 * do historie a aby sa nedali obist kontroly (suma > 0, datum nie v buducnosti, polozky z faktur).
 */
@Service
public class LedgerService {
    public static final int MAX_TAGS = 12;
    public static final int MAX_TAG_LENGTH = 40;
    /** STRICT - inak by Java "31.2.2027" potichu zmenila na 28. 2. */
    private static final DateTimeFormatter SK_DATE = DateTimeFormatter.ofPattern("d.M.uuuu")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);

    private final LedgerRepository ledger;
    private final ProjectRepository projects;
    private final Clock clock;

    public LedgerService(LedgerRepository ledger, ProjectRepository projects, Clock clock) {
        this.ledger = ledger;
        this.projects = projects;
        this.clock = clock;
    }

    @Transactional
    public LedgerEntry create(LedgerInput in, String actor) {
        LedgerRepository.Row row = this.validate(in, null);
        this.ledger.setActor(actor);
        return this.ledger.findById(this.ledger.insert(row)).orElseThrow();
    }

    @Transactional
    public LedgerEntry update(long id, LedgerInput in, String actor) {
        LedgerEntry current = this.ledger.findById(id)
                .orElseThrow(() -> LedgerException.invalid("Položka už neexistuje."));
        LedgerRepository.Row row = this.validate(in, current);
        int version = in.version() == null ? -1 : in.version();
        if (version == current.version() && sameAs(row, current)) {
            return current; // bez zmeny - nezapisovat, historia ostane cista
        }
        this.ledger.setActor(actor);
        if (!this.ledger.update(id, version, row)) {
            throw new LedgerException(List.of("Položku medzitým zmenil niekto iný. Zobrazuje sa aktuálna verzia."), true);
        }
        return this.ledger.findById(id).orElseThrow();
    }

    @Transactional
    public void delete(long id, int version, String actor) {
        LedgerEntry current = this.ledger.findById(id)
                .orElseThrow(() -> LedgerException.invalid("Položka už neexistuje."));
        if (current.isLockedByInvoice()) {
            throw LedgerException.invalid("Položka vznikla z " + current.lockReason() + " - zrušte úhradu na faktúre.");
        }
        this.ledger.setActor(actor);
        if (!this.ledger.delete(id, version)) {
            throw new LedgerException(List.of("Položku medzitým zmenil niekto iný."), true);
        }
    }

    /** Hromadna zmena: priradit projekt, pridat/odobrat tag. Vsetko alebo nic. */
    @Transactional
    public List<LedgerEntry> bulk(List<Long> ids, String action, String value, String actor) {
        if (ids == null || ids.isEmpty()) {
            throw LedgerException.invalid("Nie sú vybrané žiadne položky.");
        }
        List<LedgerEntry> out = new ArrayList<>();
        for (Long id : ids) {
            LedgerEntry e = this.ledger.findById(id)
                    .orElseThrow(() -> LedgerException.invalid("Položka " + id + " už neexistuje."));
            List<String> tags = new ArrayList<>(e.tags());
            Long projectId = e.projectId();
            switch (action) {
                case "project" -> projectId = value == null || value.isBlank() ? null : Long.valueOf(value);
                case "addTag" -> tags.add(value);
                case "removeTag" -> tags.removeIf(t -> t.equalsIgnoreCase(value == null ? "" : value.trim()));
                default -> throw LedgerException.invalid("Neznáma hromadná akcia.");
            }
            LedgerInput in = new LedgerInput(e.entryDate().toString(), e.description(), e.direction(),
                    e.amount().toPlainString(), projectId, tags, e.category(), e.counterparty(), e.documentRef(),
                    e.paymentMethod(), e.note(), e.version());
            out.add(this.update(id, in, actor));
        }
        return out;
    }

    /** Import z bankoveho vypisu / Excelu. Najprv sa skontroluju vsetky riadky, zapise sa az ked su vsetky OK. */
    @Transactional
    public List<LedgerEntry> importRows(List<LedgerInput> rows, String actor) {
        if (rows == null || rows.isEmpty()) {
            throw LedgerException.invalid("Import neobsahuje žiadne riadky.");
        }
        if (rows.size() > 2000) {
            throw LedgerException.invalid("Naraz najviac 2000 riadkov.");
        }
        List<String> errors = new ArrayList<>();
        List<LedgerRepository.Row> valid = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            try {
                valid.add(this.validate(rows.get(i), null));
            } catch (LedgerException e) {
                int line = i + 1;
                e.errors().forEach(m -> errors.add("Riadok " + line + ": " + m));
            }
        }
        if (!errors.isEmpty()) {
            throw new LedgerException(errors.size() > 30 ? errors.subList(0, 30) : errors, false);
        }
        this.ledger.setActor(actor);
        List<LedgerEntry> out = new ArrayList<>();
        for (LedgerRepository.Row r : valid) {
            out.add(this.ledger.findById(this.ledger.insert(r)).orElseThrow());
        }
        return out;
    }

    /** Uhrada faktury = prijem (pri dobropise vydavok) v denniku, aby sa nezadavala dvakrat. */
    @Transactional
    public void recordInvoicePayment(long invoiceId, String number, boolean creditNote, BigDecimal amount,
                                     LocalDate paidOn, Long projectId, String counterparty, String actor) {
        this.ledger.setActor(actor);
        this.ledger.deleteByInvoice(invoiceId);
        if (paidOn == null) {
            return;
        }
        this.ledger.insert(new LedgerRepository.Row(paidOn,
                (creditNote ? "Vrátenie dobropisu " : "Úhrada faktúry ") + number,
                creditNote ? LedgerEntry.EXPENSE : LedgerEntry.INCOME, amount, projectId, new String[0],
                creditNote ? "Vrátenie" : "Úhrada faktúry", counterparty, number, LedgerEntry.BANK, null, invoiceId, null));
    }

    /** Uhrada dosslej faktury = vydavok (pri dobropise od dodavatela prijem). Zrusenie uhrady polozku zmaze. */
    public long recordReceivedInvoicePayment(long receivedInvoiceId, String number, boolean creditNote, BigDecimal amount,
                                             LocalDate paidOn, Long projectId, String supplier, String category,
                                             String paymentRef, String actor) {
        this.ledger.setActor(actor);
        this.ledger.deleteByReceivedInvoice(receivedInvoiceId);
        if (paidOn == null) {
            return 0;
        }
        return this.ledger.insert(new LedgerRepository.Row(paidOn,
                (creditNote ? "Dobropis od " : "Faktúra ") + supplier + " " + number,
                creditNote ? LedgerEntry.INCOME : LedgerEntry.EXPENSE, amount, projectId, new String[0],
                category, supplier, paymentRef == null ? number : paymentRef, LedgerEntry.BANK, null, null,
                receivedInvoiceId));
    }

    private LedgerRepository.Row validate(LedgerInput in, LedgerEntry current) {
        List<String> errors = new ArrayList<>();
        LocalDate date = parseDate(in.entryDate(), errors);
        if (date != null && date.isAfter(LocalDate.now(this.clock))) {
            errors.add("Dátum nemôže byť v budúcnosti.");
        }
        String description = trim(in.description());
        if (description == null) {
            errors.add("Popis je povinný.");
        } else if (description.length() > 500) {
            errors.add("Popis môže mať najviac 500 znakov.");
        }
        String direction = in.direction() == null ? "" : in.direction().trim().toUpperCase(Locale.ROOT);
        if (!LedgerEntry.INCOME.equals(direction) && !LedgerEntry.EXPENSE.equals(direction)) {
            errors.add("Typ musí byť príjem alebo výdavok.");
        }
        BigDecimal amount = parseAmount(in.amount(), errors);
        String method = in.paymentMethod() == null || in.paymentMethod().isBlank()
                ? LedgerEntry.BANK : in.paymentMethod().trim().toUpperCase(Locale.ROOT);
        if (!LedgerEntry.BANK.equals(method) && !LedgerEntry.CASH.equals(method)) {
            errors.add("Úhrada musí byť banka alebo pokladňa.");
        }
        if (in.projectId() != null && this.projects.findById(in.projectId()).isEmpty()) {
            errors.add("Projekt neexistuje.");
        }
        List<String> tags = normalizeTags(in.tags(), errors);

        if (current != null && current.isLockedByInvoice() && errors.isEmpty()) {
            boolean locked = !Objects.equals(date, current.entryDate()) || !direction.equals(current.direction())
                    || amount.compareTo(current.amount()) != 0;
            if (locked) {
                errors.add("Dátum, typ a sumu tejto položky určuje " + current.lockReason() + " - zmeňte ju na faktúre.");
            }
        }
        if (!errors.isEmpty()) {
            throw new LedgerException(errors, false);
        }
        return new LedgerRepository.Row(date, description, direction, amount, in.projectId(),
                tags.toArray(String[]::new), limit(trim(in.category()), 80), limit(trim(in.counterparty()), 200),
                limit(trim(in.documentRef()), 100), method, limit(trim(in.note()), 2000),
                current == null ? null : current.invoiceId(), current == null ? null : current.receivedInvoiceId());
    }

    private static boolean sameAs(LedgerRepository.Row r, LedgerEntry e) {
        return r.entryDate().equals(e.entryDate()) && r.description().equals(e.description())
                && r.direction().equals(e.direction()) && r.amount().compareTo(e.amount()) == 0
                && Objects.equals(r.projectId(), e.projectId()) && List.of(r.tags()).equals(e.tags())
                && Objects.equals(r.category(), e.category()) && Objects.equals(r.counterparty(), e.counterparty())
                && Objects.equals(r.documentRef(), e.documentRef()) && r.paymentMethod().equals(e.paymentMethod())
                && Objects.equals(r.note(), e.note());
    }

    static LocalDate parseDate(String raw, List<String> errors) {
        String s = raw == null ? "" : raw.trim().replace(" ", "");
        if (s.isEmpty()) {
            errors.add("Dátum je povinný.");
            return null;
        }
        try {
            return LocalDate.parse(s, s.contains(".") ? SK_DATE : ISO_DATE);
        } catch (DateTimeParseException e) {
            errors.add("Dátum „" + raw.trim() + "“ nie je platný (napr. 15.1.2027).");
            return null;
        }
    }

    static BigDecimal parseAmount(String raw, List<String> errors) {
        String s = raw == null ? "" : raw.replaceAll("[\\s\\u00A0€]", "").replace(',', '.');
        if (s.isEmpty()) {
            errors.add("Suma je povinná.");
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(s);
            if (v.signum() <= 0) {
                errors.add("Suma musí byť kladná - smer určuje typ (príjem/výdavok).");
                return null;
            }
            if (v.stripTrailingZeros().scale() > 2) {
                errors.add("Suma môže mať najviac 2 desatinné miesta.");
                return null;
            }
            if (v.compareTo(new BigDecimal("9999999999")) > 0) {
                errors.add("Suma je príliš veľká.");
                return null;
            }
            return v.setScale(2, RoundingMode.UNNECESSARY);
        } catch (NumberFormatException | ArithmeticException e) {
            errors.add("Suma „" + raw.trim() + "“ nie je číslo.");
            return null;
        }
    }

    /** Tagy bez medzier na krajoch, bez duplicit (bez ohladu na velkost pismen), s limitom. */
    public static List<String> normalizeTags(List<String> raw, List<String> errors) {
        Map<String, String> unique = new LinkedHashMap<>();
        if (raw != null) {
            for (String t : raw) {
                String v = trim(t);
                if (v == null) {
                    continue;
                }
                if (v.length() > MAX_TAG_LENGTH) {
                    errors.add("Tag „" + v + "“ je dlhší než " + MAX_TAG_LENGTH + " znakov.");
                    continue;
                }
                unique.putIfAbsent(v.toLowerCase(Locale.ROOT), v);
            }
        }
        if (unique.size() > MAX_TAGS) {
            errors.add("Najviac " + MAX_TAGS + " tagov na položku.");
        }
        return new ArrayList<>(unique.values());
    }

    private static String trim(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String limit(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
