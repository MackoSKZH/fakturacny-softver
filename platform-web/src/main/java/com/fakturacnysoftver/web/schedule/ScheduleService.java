package com.fakturacnysoftver.web.schedule;

import com.fakturacnysoftver.web.export.Table;
import com.fakturacnysoftver.web.ledger.LedgerService;
import com.fakturacnysoftver.web.people.PersonRepository;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
public class ScheduleService {
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScheduleRepository repo;
    private final PersonRepository people;

    public ScheduleService(ScheduleRepository repo, PersonRepository people) {
        this.repo = repo;
        this.people = people;
    }

    /** Filtre harmonogramu aktivity: pre koho (rola/tag), vlastnik bodu, konkretna osoba. */
    public record Filter(String audience, Long ownerId, Long personId) {
        public static final Filter NONE = new Filter(null, null, null);

        public Filter {
            audience = audience == null || audience.isBlank() ? null : audience.trim();
        }

        public boolean isEmpty() {
            return this.audience == null && this.ownerId == null && this.personId == null;
        }
    }

    public List<ScheduleEntry> activity(long projectId, Filter f) {
        List<ScheduleEntry> base = f.personId() != null ? this.repo.ofPerson(f.personId(), projectId)
                : this.repo.ofActivity(projectId);
        return base.stream()
                .filter(e -> f.audience() == null || e.isFor(f.audience()))
                .filter(e -> f.ownerId() == null || f.ownerId().equals(e.ownerId()))
                .toList();
    }

    public long add(long projectId, String startsAt, String endsAt, String title, String location, Long ownerId,
                    String tags, String note) {
        return this.repo.insert(projectId, this.validate(startsAt, endsAt, title, location, ownerId, tags, note));
    }

    public void update(long projectId, long id, String startsAt, String endsAt, String title, String location,
                       Long ownerId, String tags, String note) {
        if (this.repo.update(projectId, id, this.validate(startsAt, endsAt, title, location, ownerId, tags, note)) != 1) {
            throw new ScheduleException(List.of("Bod programu neexistuje."));
        }
    }

    private ScheduleRepository.AgendaRow validate(String startsAt, String endsAt, String title, String location,
                                                  Long ownerId, String tags, String note) {
        List<String> errors = new ArrayList<>();
        LocalDateTime start = parse(startsAt, "Začiatok", errors);
        LocalDateTime end = parse(endsAt, "Koniec", errors);
        if (start == null && (startsAt == null || startsAt.isBlank())) {
            errors.add("Začiatok je povinný.");
        }
        if (start != null && end != null && !end.isAfter(start)) {
            errors.add("Koniec musí byť po začiatku.");
        }
        String t = trim(title);
        if (t == null) {
            errors.add("Názov bodu programu je povinný.");
        } else if (t.length() > 200) {
            errors.add("Názov je dlhší než 200 znakov.");
        }
        if (ownerId != null && this.people.findById(ownerId).isEmpty()) {
            errors.add("Zodpovedná osoba neexistuje.");
        }
        List<String> tagList = LedgerService.normalizeTags(tags == null ? List.of() : List.of(tags.split(",")), errors);
        String n = trim(note);
        if (n != null && n.length() > 2000) {
            errors.add("Poznámka je dlhšia než 2000 znakov.");
        }
        if (!errors.isEmpty()) {
            throw new ScheduleException(errors);
        }
        return new ScheduleRepository.AgendaRow(start, end, t, trim(location), ownerId, tagList, n);
    }

    public int delete(long projectId, long id) {
        return this.repo.delete(projectId, id);
    }

    // ---------- exporty ----------

    public Table table(String title, List<ScheduleEntry> entries) {
        Table t = new Table(title, "Deň", "Od", "Do", "Aktivita", "Typ", "Bod programu", "Miesto", "Zodpovedá", "Pre",
                "Ľudia", "Poznámka");
        entries.forEach(e -> t.row(e.day(), e.allDay() ? "" : HM.format(e.startsAt()),
                e.endsAt() == null ? "" : HM.format(e.endsAt()), e.activityCode(), e.kind().label(), e.title(),
                e.location(), e.ownerName(), e.kind() == ScheduleEntry.Kind.TERMIN ? List.of() : e.tags(), e.people(),
                e.note()));
        return t;
    }

    /** Rozpis pre kazdeho cloveka aktivity - na hromadny e-mail (mail merge) alebo tlac. */
    public Table perPerson(String title, long projectId) {
        Table t = new Table(title, "Meno", "E-mail", "Roly", "Deň", "Od", "Do", "Typ", "Bod programu", "Miesto",
                "Poznámka");
        for (ScheduleRepository.Member m : this.repo.members(projectId)) {
            for (ScheduleEntry e : this.repo.ofPerson(m.personId(), projectId)) {
                t.row(m.fullName(), m.email(), m.roles(), e.day(), e.allDay() ? "" : HM.format(e.startsAt()),
                        e.endsAt() == null ? "" : HM.format(e.endsAt()), e.kind().label(), e.title(), e.location(),
                        e.note());
            }
        }
        return t;
    }

    // ---------- odber kalendara ----------

    public Optional<String> calendarToken(long personId) {
        return this.repo.calendarToken(personId);
    }

    /** Novy tajny odkaz; stary prestane fungovat (napr. ked unikne). */
    public String renewCalendarToken(long personId) {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        this.repo.setCalendarToken(personId, token);
        return token;
    }

    /** URL odberu pre sablony (https aj webcal pre jedno kliknutie na iPhone/Outlook). */
    public static java.util.Map<String, Object> feedUrls(String token) {
        String https = org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/kalendar/" + token + ".ics").toUriString();
        return java.util.Map.of("feedUrl", https, "webcalUrl", https.replaceFirst("^https?://", "webcal://"));
    }

    public void revokeCalendarToken(long personId) {
        this.repo.setCalendarToken(personId, null);
    }

    private static LocalDateTime parse(String raw, String label, List<String> errors) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            errors.add(label + " „" + raw.trim() + "“ nie je platný dátum a čas.");
            return null;
        }
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
