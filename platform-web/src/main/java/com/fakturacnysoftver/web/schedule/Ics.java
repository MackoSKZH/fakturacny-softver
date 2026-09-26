package com.fakturacnysoftver.web.schedule;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * iCalendar (RFC 5545) pre Google Kalendar, Outlook a iPhone. Casy su prevedene z miestneho casu
 * (Europe/Bratislava, vratane letneho casu) na UTC, takze netreba posielat VTIMEZONE.
 */
public final class Ics {
    static final ZoneId ZONE = ZoneId.of("Europe/Bratislava");
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private Ics() {
    }

    public static String calendar(String name, List<ScheduleEntry> entries, Instant now) {
        List<String> lines = new ArrayList<>(List.of("BEGIN:VCALENDAR", "VERSION:2.0",
                "PRODID:-//FIRST Global Slovakia//HQ//SK", "CALSCALE:GREGORIAN", "METHOD:PUBLISH",
                "X-WR-CALNAME:" + text(name), "REFRESH-INTERVAL;VALUE=DURATION:PT1H", "X-PUBLISHED-TTL:PT1H"));
        for (ScheduleEntry e : entries) {
            lines.add("BEGIN:VEVENT");
            lines.add("UID:" + e.uid());
            lines.add("DTSTAMP:" + UTC.format(now));
            if (e.allDay()) {
                lines.add("DTSTART;VALUE=DATE:" + DATE.format(e.day()));
                lines.add("DTEND;VALUE=DATE:" + DATE.format(e.day().plusDays(1)));
                lines.add("TRANSP:TRANSPARENT");
            } else {
                lines.add("DTSTART:" + utc(e.startsAt()));
                if (e.endsAt() != null) {
                    lines.add("DTEND:" + utc(e.endsAt()));
                }
            }
            lines.add("SUMMARY:" + text(summary(e)));
            if (e.location() != null) {
                lines.add("LOCATION:" + text(e.location()));
            }
            lines.add("DESCRIPTION:" + text(description(e)));
            lines.add("CATEGORIES:" + text(e.kind().label()));
            lines.add("END:VEVENT");
        }
        lines.add("END:VCALENDAR");
        StringBuilder sb = new StringBuilder();
        lines.forEach(l -> sb.append(fold(l)).append("\r\n"));
        return sb.toString();
    }

    static String summary(ScheduleEntry e) {
        return switch (e.kind()) {
            case TERMIN -> "Termín: " + e.title() + " [" + e.activityCode() + "]";
            case SMENA, PROGRAM -> e.title() + " [" + e.activityCode() + "]";
        };
    }

    static String description(ScheduleEntry e) {
        StringBuilder d = new StringBuilder(e.activityName());
        if (e.kind() == ScheduleEntry.Kind.PROGRAM && !e.tags().isEmpty()) {
            d.append("\nPre: ").append(String.join(", ", e.tags()));
        }
        if (e.kind() == ScheduleEntry.Kind.TERMIN && !e.tags().isEmpty()) {
            d.append("\nSekcia: ").append(e.tags().get(0));
        }
        if (e.ownerName() != null) {
            d.append("\nZodpovedá: ").append(e.ownerName());
        }
        if (e.people() != null) {
            d.append("\nĽudia: ").append(e.people());
        }
        if (e.note() != null) {
            d.append("\n\n").append(e.note());
        }
        return d.toString();
    }

    private static String utc(LocalDateTime local) {
        return UTC.format(local.atZone(ZONE).toInstant());
    }

    /** Escapovanie textu podla RFC 5545 3.3.11. */
    static String text(String s) {
        return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
                .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
    }

    /** Riadky najviac 75 bajtov UTF-8, pokracovanie zacina medzerou; znak sa nikdy nerozdeli. */
    static String fold(String line) {
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        int limit = 75;
        for (int i = 0; i < line.length(); ) {
            int cp = line.codePointAt(i);
            int len = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + len > limit) {
                out.append("\r\n ");
                bytes = 1;
            }
            out.appendCodePoint(cp);
            bytes += len;
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
