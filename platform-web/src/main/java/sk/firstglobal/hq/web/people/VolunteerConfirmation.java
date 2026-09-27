package sk.firstglobal.hq.web.people;

import sk.firstglobal.hq.web.export.Table;
import sk.firstglobal.hq.web.organization.Organization;
import sk.firstglobal.hq.web.organization.OrganizationRepository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Potvrdenie o vykone dobrovolnickej cinnosti (zakon c. 406/2011 Z. z. o dobrovolnictve) - dobrovolnik ho
 * pouzije napr. do zivotopisu, na skolu alebo pri prijimacom konani. Zaklad su priradenia so stavom
 * "zucastnil sa" a odpracovane hodiny z timu aktivity.
 */
@Service
public class VolunteerConfirmation {
    private final JdbcClient jdbc;
    private final PersonRepository people;
    private final OrganizationRepository organizations;
    private final Clock clock;

    public VolunteerConfirmation(JdbcClient jdbc, PersonRepository people, OrganizationRepository organizations,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.people = people;
        this.organizations = organizations;
        this.clock = clock;
    }

    public record Row(LocalDate day, String activity, String location, String role, BigDecimal hours) {
    }

    public List<Row> rows(long personId, Integer year, Long projectId) {
        return this.jdbc.sql("""
                        SELECT COALESCE(r.starts_at::date, pr.starts_on, pr.created_at::date) AS day, pr.name AS activity,
                               pr.location, r.name AS role, a.hours
                        FROM assignment a JOIN activity_role r ON r.id = a.role_id JOIN project pr ON pr.id = r.project_id
                        WHERE a.person_id = :p AND a.status = 'ZUCASTNIL_SA'
                          AND COALESCE(r.starts_at::date, pr.starts_on, pr.created_at::date) <= :today
                          AND (CAST(:project AS bigint) IS NULL OR pr.id = :project)
                          AND (CAST(:year AS int) IS NULL
                               OR extract(year FROM COALESCE(r.starts_at::date, pr.starts_on, pr.created_at::date)) = :year)
                        ORDER BY day, pr.code, r.name""")
                .param("p", personId).param("project", projectId).param("year", year)
                .param("today", LocalDate.now(this.clock))
                .query(Row.class).list();
    }

    /** Roky, za ktore ma osoba zaznamenanu ucast - na vyber v UI. */
    public List<Integer> years(long personId) {
        return this.jdbc.sql("""
                        SELECT DISTINCT extract(year FROM COALESCE(r.starts_at::date, pr.starts_on, pr.created_at::date))::int
                        FROM assignment a JOIN activity_role r ON r.id = a.role_id JOIN project pr ON pr.id = r.project_id
                        WHERE a.person_id = :p AND a.status = 'ZUCASTNIL_SA'
                          AND COALESCE(r.starts_at::date, pr.starts_on, pr.created_at::date) <= :today ORDER BY 1 DESC""")
                .param("p", personId).param("today", LocalDate.now(this.clock)).query(Integer.class).list();
    }

    public byte[] pdf(long personId, Integer year, Long projectId) {
        Person p = this.people.findById(personId).orElseThrow(() -> new IllegalArgumentException("Osoba neexistuje."));
        List<Row> rows = this.rows(personId, year, projectId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException(p.fullName() + " nemá " + (year == null ? "" : "v roku " + year + " ")
                    + "zaznamenanú účasť (stav „zúčastnil sa“).");
        }
        Organization org = this.organizations.find().orElse(null);
        String orgName = org == null || org.name().isBlank() ? "FIRST Global Slovakia" : org.name();
        Table t = new Table("Potvrdenie o výkone dobrovoľníckej činnosti", "Dátum", "Aktivita", "Miesto", "Činnosť",
                "Hodiny").portrait().subtitle("podľa zákona č. 406/2011 Z. z. o dobrovoľníctve");
        t.preamble("Organizácia: " + orgName + (org == null ? "" : address(org)));
        t.preamble("Dobrovoľník: " + p.fullName());
        LocalDate from = rows.getFirst().day();
        LocalDate to = rows.getLast().day();
        BigDecimal total = rows.stream().map(Row::hours).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        t.preamble("Potvrdzujeme, že dobrovoľník vykonával(a) bezodplatne dobrovoľnícku činnosť v prospech organizácie"
                + " v období " + Table.text(from) + (from.equals(to) ? "" : " - " + Table.text(to))
                + " v celkovom rozsahu " + Table.text(total).replace(",00", "") + " hodín, a to nasledovne:");
        rows.forEach(r -> t.row(r.day(), r.activity(), r.location(), r.role(), r.hours()));
        t.row("Spolu", null, null, null, total);
        String city = org == null || org.city() == null || org.city().isBlank() ? "" : "V " + org.city() + " ";
        t.note(city + "dňa " + Table.text(LocalDate.now(this.clock)));
        t.note(" ");
        t.note("______________________________");
        t.note("podpis a pečiatka štatutárneho zástupcu " + orgName);
        return t.bytes(Table.Format.PDF);
    }

    /** Potvrdenia pre vsetkych zucastnenych na jednej aktivite - vydava sa po akcii. */
    public byte[] zipForActivity(long projectId) {
        List<Long> ids = this.jdbc.sql("""
                        SELECT DISTINCT a.person_id FROM assignment a JOIN activity_role r ON r.id = a.role_id
                             JOIN project pr ON pr.id = r.project_id
                        WHERE r.project_id = :p AND a.status = 'ZUCASTNIL_SA'
                          AND COALESCE(r.starts_at::date, pr.starts_on, pr.created_at::date) <= :today""")
                .param("p", projectId).param("today", LocalDate.now(this.clock)).query(Long.class).list();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (long id : ids) {
                Person p = this.people.findById(id).orElseThrow();
                zip.putNextEntry(new ZipEntry("potvrdenie-" + fileSafe(p.fullName()) + "-" + id + ".pdf"));
                zip.write(this.pdf(id, null, projectId));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ids.isEmpty() ? null : bytes.toByteArray();
    }

    public static org.springframework.http.ResponseEntity<byte[]> response(byte[] pdf, String name) {
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".pdf\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_PDF).body(pdf);
    }

    private static String address(Organization o) {
        StringBuilder sb = new StringBuilder();
        String city = String.join(" ", nz(o.postalCode()), nz(o.city())).trim();
        String line = nz(o.street()).isBlank() ? city : city.isEmpty() ? o.street().trim() : o.street().trim() + ", " + city;
        if (!line.isEmpty()) {
            sb.append(", ").append(line);
        }
        if (o.ico() != null && !o.ico().isBlank()) {
            sb.append(", IČO ").append(o.ico());
        }
        return sb.toString();
    }

    static String fileSafe(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
