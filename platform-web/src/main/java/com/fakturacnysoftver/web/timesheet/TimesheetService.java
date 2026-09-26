package com.fakturacnysoftver.web.timesheet;

import com.fakturacnysoftver.web.audit.AuditLog;
import com.fakturacnysoftver.web.people.Person;
import com.fakturacnysoftver.web.people.PersonRepository;
import com.fakturacnysoftver.web.people.PersonRole;
import com.fakturacnysoftver.web.project.ProjectRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dochadzka: zmluvy, vykaz hodin, mesacna uzavierka. Kontroluje zakonne limity pri kazdom zapise.
 * Vysledok je hruba odmena (hodiny x hodinovka) - odvody, dan a vyplatu pocita uctovnik.
 */
@Service
public class TimesheetService {
    private final TimesheetRepository repo;
    private final PersonRepository people;
    private final ProjectRepository projects;
    private final AuditLog audit;
    private final Clock clock;

    public TimesheetService(TimesheetRepository repo, PersonRepository people, ProjectRepository projects,
                            AuditLog audit, Clock clock) {
        this.repo = repo;
        this.people = people;
        this.projects = projects;
        this.audit = audit;
        this.clock = clock;
    }

    public static class TimesheetException extends RuntimeException {
        private final List<String> errors;

        public TimesheetException(List<String> errors) {
            super(String.join(" ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return this.errors;
        }
    }

    @Transactional
    public long createContract(long personId, String kindCode, String title, String jobDescription, String rateRaw,
                               LocalDate from, LocalDate to, Long projectId, String note, String actor) {
        List<String> errors = new ArrayList<>();
        Person person = this.people.findById(personId).orElse(null);
        if (person == null) {
            errors.add("Vyberte osobu.");
        } else if (!person.roles().contains(PersonRole.PLATENY.name())) {
            errors.add("Osoba nemá rolu „Platený spolupracovník“ - pridajte ju v karte osoby.");
        }
        ContractKind kind = ContractKind.parse(kindCode);
        if (kind == null) {
            errors.add("Vyberte typ zmluvy.");
        }
        if (title == null || title.isBlank()) {
            errors.add("Uveďte názov pozície (napr. Koordinátor národného kola).");
        }
        BigDecimal rate = parseDecimal(rateRaw, "Hodinovka", errors);
        if (rate != null && rate.signum() <= 0) {
            errors.add("Hodinovka musí byť kladná.");
        }
        if (rate != null && kind != null && kind.isEmployment() && rate.compareTo(ContractKind.MIN_HOURLY_WAGE_2026) < 0) {
            errors.add("Hodinovka je pod minimálnou hodinovou mzdou 2026 (" + ContractKind.MIN_HOURLY_WAGE_2026
                    + " €) - to zákon neumožňuje.");
        }
        if (from == null || to == null) {
            errors.add("Uveďte platnosť zmluvy od - do.");
        } else if (to.isBefore(from)) {
            errors.add("Koniec zmluvy je pred jej začiatkom.");
        } else if (kind != null && kind.isAgreement() && to.isAfter(from.plusMonths(12).minusDays(1))) {
            errors.add("Dohodu možno uzatvoriť najviac na 12 mesiacov.");
        }
        if (projectId != null && this.projects.findById(projectId).isEmpty()) {
            errors.add("Projekt neexistuje.");
        }
        if (!errors.isEmpty()) {
            throw new TimesheetException(errors);
        }
        long id = this.repo.insertContract(personId, kind.name(), title.trim(), blank(jobDescription),
                rate.setScale(2, RoundingMode.HALF_UP), from, to, projectId, blank(note));
        this.audit.record(actor, "ZMLUVA", "dochadzka", id, person.fullName() + ", " + kind.label() + ", "
                + rate + " €/h, " + from + " - " + to);
        return id;
    }

    /** Zapis hodin - editorom alebo samotnym clovekom (selfService = len vlastne zmluvy, overuje controller). */
    @Transactional
    public long logHours(long contractId, LocalDate date, String hoursRaw, Long projectId, String description,
                         String actor) {
        TimesheetRepository.Contract c = this.repo.contract(contractId)
                .orElseThrow(() -> new TimesheetException(List.of("Zmluva neexistuje.")));
        List<String> errors = new ArrayList<>();
        BigDecimal hours = parseDecimal(hoursRaw, "Počet hodín", errors);
        if (date == null) {
            errors.add("Uveďte dátum.");
        }
        if (description == null || description.isBlank()) {
            errors.add("Popíšte, čo ste robili.");
        }
        if (hours != null && (hours.signum() <= 0 || hours.stripTrailingZeros().scale() > 2)) {
            errors.add("Počet hodín musí byť kladný, najviac 2 desatinné miesta (napr. 1,5).");
        }
        if (projectId != null && this.projects.findById(projectId).isEmpty()) {
            errors.add("Projekt neexistuje.");
        }
        if (!errors.isEmpty()) {
            throw new TimesheetException(errors);
        }
        LocalDate today = LocalDate.now(this.clock);
        if (date.isAfter(today)) {
            errors.add("Dátum nemôže byť v budúcnosti.");
        }
        if (!c.isActiveOn(date)) {
            errors.add("Dátum je mimo platnosti zmluvy (" + c.validFrom() + " - " + c.validTo() + ").");
        }
        if (this.repo.closure(contractId, date.withDayOfMonth(1)).isPresent()) {
            errors.add("Mesiac " + date.getMonthValue() + "/" + date.getYear() + " je uzavretý.");
        }
        BigDecimal day = this.repo.personHoursOn(c.personId(), date).add(hours);
        if (day.compareTo(ContractKind.DAY_LIMIT) > 0) {
            errors.add("V jeden deň najviac 12 hodín (spolu by bolo " + plain(day) + " h).");
        }
        ContractKind kind = ContractKind.parse(c.kind());
        if (kind == ContractKind.DOVP) {
            BigDecimal year = this.repo.personDovpHours(c.personId(), date.withDayOfYear(1),
                    date.with(TemporalAdjusters.lastDayOfYear())).add(hours);
            if (year.compareTo(ContractKind.DOVP_YEAR_LIMIT) > 0) {
                errors.add("Dohoda o vykonaní práce: najviac 350 hodín za rok, spolu by bolo " + plain(year) + " h.");
            }
        }
        if (kind == ContractKind.DOPC) {
            LocalDate monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            BigDecimal week = this.repo.contractHours(contractId, monday, monday.plusDays(6)).add(hours);
            if (week.compareTo(ContractKind.DOPC_WEEK_LIMIT) > 0) {
                errors.add("Dohoda o pracovnej činnosti: najviac 10 hodín týždenne, v tomto týždni by bolo "
                        + plain(week) + " h.");
            }
        }
        if (kind == ContractKind.DOBPS) {
            // Priemer 20 h tyzdenne za celu dobu dohody - maximum, ktore sa da odpracovat, je 20 h x pocet tyzdnov.
            long weeks = Math.max(1, (ChronoUnit.DAYS.between(c.validFrom(), c.validTo()) + 7) / 7);
            BigDecimal allowed = ContractKind.DOBPS_WEEK_AVERAGE_LIMIT.multiply(BigDecimal.valueOf(weeks));
            BigDecimal total = this.repo.contractHours(contractId, c.validFrom(), c.validTo()).add(hours);
            if (total.compareTo(allowed) > 0) {
                errors.add("Brigáda študenta: v priemere najviac 20 h týždenne, za celú dohodu najviac "
                        + plain(allowed) + " h.");
            }
        }
        if (!errors.isEmpty()) {
            throw new TimesheetException(errors);
        }
        return this.repo.insertLog(contractId, date, hours, projectId != null ? projectId : c.projectId(),
                description.trim(), actor);
    }

    @Transactional
    public void deleteLog(long contractId, long logId, LocalDate logDate) {
        if (logDate != null && this.repo.closure(contractId, logDate.withDayOfMonth(1)).isPresent()) {
            throw new TimesheetException(List.of("Mesiac je uzavretý - záznam sa už nedá zmazať."));
        }
        this.repo.deleteLog(contractId, logId);
    }

    public record MonthReport(TimesheetRepository.Contract contract, YearMonth month, List<TimesheetRepository.Log> logs,
                              BigDecimal hours, BigDecimal reward, Map<String, BigDecimal> hoursByProject,
                              TimesheetRepository.Closure closure) {
        public boolean closed() {
            return this.closure != null;
        }
    }

    public MonthReport report(long contractId, YearMonth month) {
        TimesheetRepository.Contract c = this.repo.contract(contractId)
                .orElseThrow(() -> new TimesheetException(List.of("Zmluva neexistuje.")));
        List<TimesheetRepository.Log> logs = this.repo.logs(contractId, month.atDay(1), month.atEndOfMonth());
        BigDecimal hours = logs.stream().map(TimesheetRepository.Log::hours).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> byProject = new LinkedHashMap<>();
        logs.forEach(l -> byProject.merge(l.projectCode() == null ? "bez projektu" : l.projectCode(), l.hours(), BigDecimal::add));
        return new MonthReport(c, month, logs, hours, reward(hours, c.hourlyRate()), byProject,
                this.repo.closure(contractId, month.atDay(1)).orElse(null));
    }

    /** Uzavretie mesiaca - od tej chvile DB trigger nepusti zmenu hodin v tomto mesiaci. */
    @Transactional
    public MonthReport close(long contractId, YearMonth month, String actor) {
        MonthReport r = this.report(contractId, month);
        if (r.closed()) {
            throw new TimesheetException(List.of("Mesiac už je uzavretý."));
        }
        if (month.atDay(1).isAfter(LocalDate.now(this.clock))) {
            throw new TimesheetException(List.of("Budúci mesiac sa nedá uzavrieť."));
        }
        this.repo.close(contractId, month.atDay(1), r.hours(), r.reward(), actor);
        this.audit.record(actor, "UZAVIERKA", "dochadzka", contractId, month + ": " + plain(r.hours()) + " h, "
                + r.reward() + " €");
        return this.report(contractId, month);
    }

    static BigDecimal reward(BigDecimal hours, BigDecimal rate) {
        return hours.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal parseDecimal(String raw, String label, List<String> errors) {
        try {
            return new BigDecimal(raw == null ? "" : raw.replaceAll("[\\s€]", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            errors.add(label + " musí byť číslo.");
            return null;
        }
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
