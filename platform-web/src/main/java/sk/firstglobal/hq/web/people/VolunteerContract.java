package sk.firstglobal.hq.web.people;

import sk.firstglobal.hq.web.export.Table;
import sk.firstglobal.hq.web.organization.Organization;
import sk.firstglobal.hq.web.organization.OrganizationRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zmluva o dobrovolnickej cinnosti (§ 5 zakona c. 406/2011 Z. z.) - na aktivitu alebo na obdobie. Obsahuje zmluvne
 * strany, druh, miesto, cas a rozsah cinnosti (smeny z timu), dobu trvania a zakladne povinnosti. Datum narodenia a
 * adresu dobrovolnika neevidujeme (minimalizacia udajov) - doplnia sa rucne pri podpise.
 */
@Service
public class VolunteerContract {
    private final JdbcClient jdbc;
    private final PersonRepository people;
    private final OrganizationRepository organizations;
    private final Clock clock;

    public VolunteerContract(JdbcClient jdbc, PersonRepository people, OrganizationRepository organizations, Clock clock) {
        this.jdbc = jdbc;
        this.people = people;
        this.organizations = organizations;
        this.clock = clock;
    }

    public record Shift(LocalDateTime startsAt, LocalDateTime endsAt, LocalDate day, String activity, String location,
                        String role, String description) {
    }

    private record ActivityInfo(String code, String name, LocalDate startsOn, LocalDate endsOn) {
    }

    /** Smeny osoby v obdobi (alebo na aktivite) - okrem odmietnutych. */
    List<Shift> shifts(long personId, Long projectId, LocalDate from, LocalDate to) {
        return this.jdbc.sql("""
                        SELECT r.starts_at, r.ends_at, COALESCE(r.starts_at::date, pr.starts_on) AS day, pr.name AS activity,
                               pr.location, r.name AS role, r.description
                        FROM assignment a JOIN activity_role r ON r.id = a.role_id JOIN project pr ON pr.id = r.project_id
                        WHERE a.person_id = :p AND a.status <> 'ODMIETOL'
                          AND (CAST(:project AS bigint) IS NULL OR pr.id = :project)
                          AND (COALESCE(r.starts_at::date, pr.starts_on) IS NULL
                               OR COALESCE(r.starts_at::date, pr.starts_on) BETWEEN :from AND :to)
                        ORDER BY day NULLS LAST, r.starts_at, pr.code, r.name""")
                .param("p", personId).param("project", projectId).param("from", from).param("to", to)
                .query(Shift.class).list();
    }

    /**
     * Zmluva pre osobu. Bez aktivity plati od dnes do konca roka (alebo od-do); s aktivitou na jej trvanie.
     */
    public byte[] pdf(long personId, Long projectId, LocalDate from, LocalDate to) {
        Person p = this.people.findById(personId).orElseThrow(() -> new IllegalArgumentException("Osoba neexistuje."));
        LocalDate today = LocalDate.now(this.clock);
        ActivityInfo act = projectId == null ? null : this.jdbc.sql("""
                        SELECT code, name, starts_on, COALESCE(ends_on, starts_on) AS ends_on FROM project WHERE id = :id""")
                .param("id", projectId).query(ActivityInfo.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Aktivita neexistuje."));
        LocalDate start = from != null ? from : act != null && act.startsOn() != null && act.startsOn().isAfter(today)
                ? act.startsOn() : today;
        LocalDate end = to != null ? to : act != null && act.endsOn() != null ? act.endsOn() : today.withMonth(12).withDayOfMonth(31);
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Koniec zmluvy je pred začiatkom.");
        }
        List<Shift> shifts = this.shifts(personId, projectId, start, end);

        Organization org = this.organizations.find().orElse(null);
        String orgName = org == null || org.name().isBlank() ? "FIRST Global Slovakia" : org.name();
        Table t = new Table("Zmluva o dobrovoľníckej činnosti", "Dátum", "Čas", "Aktivita", "Miesto", "Činnosť")
                .portrait().subtitle("podľa § 5 zákona č. 406/2011 Z. z. o dobrovoľníctve"
                        + (act == null ? "" : " · " + act.code() + " " + act.name()));
        t.preamble("Prijímateľ: " + orgName + address(org) + ", zastúpený: ______________________________ (ďalej „prijímateľ“)");
        t.preamble("Dobrovoľník: " + p.fullName() + ", dátum narodenia: ______________, adresa: "
                + "__________________________________________" + contact(p) + " (ďalej „dobrovoľník“)");
        if (p.minor()) {
            t.preamble("Zákonný zástupca maloletého dobrovoľníka: " + nz(p.guardianName(), "______________________________")
                    + (p.guardianContact() == null ? "" : ", " + p.guardianContact()));
        }
        t.preamble("Čl. I - Predmet. Dobrovoľník sa zaväzuje bezodplatne, z vlastnej vôle a vo svojom voľnom čase vykonávať "
                + "pre prijímateľa dobrovoľnícku činnosť pri vzdelávacích a súťažných podujatiach pre mládež (robotika, "
                + "STEM) - druh, miesto, čas a rozsah sú v rozpise nižšie. Zmluva sa uzatvára na dobu od "
                + Table.text(start) + " do " + Table.text(end) + ".");
        if (shifts.isEmpty()) {
            t.row("priebežne", "podľa dohody", act == null ? "podujatia prijímateľa" : act.name(), "podľa podujatia",
                    "organizačná a technická pomoc podľa pokynov koordinátora");
        }
        for (Shift s : shifts) {
            t.row(s.day(), time(s), s.activity(), s.location(), s.description() == null ? s.role()
                    : s.role() + " - " + s.description());
        }
        t.note("Čl. II - Povinnosti prijímateľa. Prijímateľ oboznámi dobrovoľníka s náplňou činnosti, s predpismi o "
                + "bezpečnosti a ochrane zdravia a s pravidlami ochrany detí na podujatí, zabezpečí mu podmienky na "
                + "činnosť a na požiadanie mu vydá potvrdenie o vykonanej dobrovoľníckej činnosti.");
        t.note("Čl. III - Povinnosti dobrovoľníka. Dobrovoľník vykonáva činnosť osobne, svedomito a podľa pokynov "
                + "koordinátora, vopred oznámi, ak sa na dohodnutú činnosť nedostaví, a zachováva mlčanlivosť o osobných "
                + "údajoch účastníkov (najmä maloletých), s ktorými sa pri činnosti oboznámi, aj po skončení zmluvy.");
        t.note("Čl. IV - Výdavky. Dobrovoľník nemá nárok na odmenu. Prijímateľ mu môže uhradiť nevyhnutné výdavky "
                + "spojené s činnosťou (cestovné, strava, ubytovanie) po predložení dokladov alebo vopred dohodnutou sumou; "
                + "takáto náhrada nie je odmenou.");
        t.note("Čl. V - Skončenie. Zmluva zaniká uplynutím doby, na ktorú bola uzatvorená, písomnou dohodou alebo "
                + "písomnou výpoveďou ktorejkoľvek strany, a to dňom jej doručenia druhej strane.");
        t.note("Čl. VI - Osobné údaje. Prijímateľ spracúva osobné údaje dobrovoľníka na účel plnenia tejto zmluvy "
                + "(čl. 6 ods. 1 písm. b nariadenia GDPR) a evidencie dobrovoľníckej činnosti; podrobnosti poskytne "
                + "na požiadanie. Údaje po skončení zmluvy uchováva len v rozsahu potrebnom na vydanie potvrdení.");
        t.note("Čl. VII - Záverečné ustanovenia. Zmluva je vyhotovená v dvoch rovnopisoch, každá strana dostane jeden. "
                + "Nadobúda platnosť a účinnosť dňom podpisu oboma stranami."
                + (p.minor() ? " Zákonný zástupca svojím podpisom udeľuje súhlas s dobrovoľníckou činnosťou maloletého." : ""));
        String city = org == null || org.city() == null || org.city().isBlank() ? "V ______________" : "V " + org.city();
        t.note(city + " dňa ______________");
        t.note(" ");
        t.note("______________________________          ______________________________"
                + (p.minor() ? "          ______________________________" : ""));
        t.note("za prijímateľa                                              dobrovoľník"
                + (p.minor() ? "                                                    zákonný zástupca" : ""));
        return t.bytes(Table.Format.PDF);
    }

    /** Zmluvy pre cely tim aktivity (okrem odmietnutych). */
    public byte[] zipForActivity(long projectId) {
        List<Long> ids = this.jdbc.sql("""
                        SELECT DISTINCT a.person_id FROM assignment a JOIN activity_role r ON r.id = a.role_id
                        JOIN person p ON p.id = a.person_id
                        WHERE r.project_id = :p AND a.status <> 'ODMIETOL' AND p.anonymized_at IS NULL""")
                .param("p", projectId).query(Long.class).list();
        if (ids.isEmpty()) {
            return null;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (long id : ids) {
                Person p = this.people.findById(id).orElseThrow();
                zip.putNextEntry(new ZipEntry("zmluva-" + VolunteerConfirmation.fileSafe(p.fullName()) + "-" + id + ".pdf"));
                zip.write(this.pdf(id, projectId, null, null));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static String time(Shift s) {
        if (s.startsAt() == null) {
            return "podľa programu";
        }
        String from = String.format("%d:%02d", s.startsAt().getHour(), s.startsAt().getMinute());
        return s.endsAt() == null ? "od " + from : from + " - " + String.format("%d:%02d", s.endsAt().getHour(),
                s.endsAt().getMinute());
    }

    private static String contact(Person p) {
        String c = p.email() != null ? p.email() : p.phone();
        return c == null ? "" : ", kontakt: " + c;
    }

    private static String address(Organization o) {
        if (o == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        String city = String.join(" ", nz(o.postalCode(), ""), nz(o.city(), "")).trim();
        String street = nz(o.street(), "").trim();
        String line = street.isEmpty() ? city : city.isEmpty() ? street : street + ", " + city;
        if (!line.isEmpty()) {
            sb.append(", so sídlom ").append(line);
        }
        if (o.ico() != null && !o.ico().isBlank()) {
            sb.append(", IČO ").append(o.ico());
        }
        return sb.toString();
    }

    private static String nz(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
