package sk.firstglobal.hq.web.schedule;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

/** Jeden riadok harmonogramu: bod programu, smena v role alebo termin ulohy. */
public record ScheduleEntry(
        Kind kind,
        long sourceId,
        long projectId,
        String activityCode,
        String activityName,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        String title,
        String location,
        Long ownerId,
        String ownerName,
        List<String> tags,
        String note,
        String people) {

    /** Tag, ktory znamena "pre cely tim aktivity". */
    public static final String EVERYONE = "všetci";

    public enum Kind {
        PROGRAM("Program"), SMENA("Smena"), TERMIN("Termín úlohy");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public String uid() {
        return this.kind.name().toLowerCase(Locale.ROOT) + "-" + this.sourceId + "@fgs-hq";
    }

    public boolean allDay() {
        return this.kind == Kind.TERMIN;
    }

    public LocalDate day() {
        return this.startsAt.toLocalDate();
    }

    public boolean forEveryone() {
        return this.tags.stream().anyMatch(ScheduleEntry::isEveryone);
    }

    /** Patri bod skupine (rola alebo tag)? Body pre vsetkych patria kazdemu. */
    public boolean isFor(String audience) {
        return this.forEveryone() || this.tags.stream().anyMatch(t -> t.equalsIgnoreCase(audience.trim()));
    }

    static boolean isEveryone(String tag) {
        String t = tag.trim().toLowerCase(Locale.ROOT);
        return t.equals(EVERYONE) || t.equals("vsetci");
    }
}
