package sk.firstglobal.hq.web.schedule;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Harmonogram: body programu (agenda_item), smeny z roli (activity_role) a terminy uloh.
 * Osobny program = body, ktorych je vlastnikom, body otagovane jeho rolou alebo "všetci", jeho smeny a jeho ulohy.
 */
@Repository
public class ScheduleRepository {
    private static final String EVERYONE_SQL = "('všetci', 'vsetci')";

    private final JdbcClient jdbc;

    public ScheduleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record AgendaRow(LocalDateTime startsAt, LocalDateTime endsAt, String title, String location,
                            Long ownerPersonId, List<String> tags, String note) {
    }

    private static final String AGENDA_SELECT = """
            SELECT ai.id, ai.project_id, pr.code AS activity_code, pr.name AS activity_name, ai.starts_at, ai.ends_at,
                   ai.title, COALESCE(ai.location, pr.location) AS location, ai.owner_person_id,
                   o.full_name AS owner_name, ai.tags, ai.note, NULL AS people
            FROM agenda_item ai JOIN project pr ON pr.id = ai.project_id
                 LEFT JOIN person o ON o.id = ai.owner_person_id
            """;

    private static final String SHIFT_SELECT = """
            SELECT r.id, r.project_id, pr.code AS activity_code, pr.name AS activity_name, r.starts_at, r.ends_at,
                   r.name AS title, pr.location, NULL::bigint AS owner_person_id, NULL AS owner_name,
                   ARRAY[r.name] AS tags, r.description AS note,
                   COALESCE((SELECT string_agg(p.full_name || CASE a.status WHEN 'POZVANY' THEN ' (pozvaný)' ELSE '' END,
                                               ', ' ORDER BY p.full_name)
                             FROM assignment a JOIN person p ON p.id = a.person_id
                             WHERE a.role_id = r.id AND a.status <> 'ODMIETOL'), 'nikto')
                   || ' - obsadené ' || (SELECT count(*) FROM assignment a WHERE a.role_id = r.id
                                         AND a.status IN ('POTVRDENY', 'ZUCASTNIL_SA')) || '/' || r.needed AS people
            FROM activity_role r JOIN project pr ON pr.id = r.project_id
            """;

    private static RowMapper<ScheduleEntry> mapper(ScheduleEntry.Kind kind) {
        return (rs, n) -> new ScheduleEntry(kind, rs.getLong("id"), rs.getLong("project_id"),
                rs.getString("activity_code"), rs.getString("activity_name"), time(rs, "starts_at"),
                time(rs, "ends_at"), rs.getString("title"), rs.getString("location"), nullableLong(rs, "owner_person_id"),
                rs.getString("owner_name"), tags(rs.getArray("tags")), rs.getString("note"), rs.getString("people"));
    }

    /** Cely harmonogram aktivity (alebo vsetkych aktivit, ak projectId je null): program + smeny. */
    public List<ScheduleEntry> ofActivity(Long projectId) {
        List<ScheduleEntry> all = new ArrayList<>(this.jdbc.sql(AGENDA_SELECT
                        + "WHERE CAST(:p AS bigint) IS NULL OR ai.project_id = :p")
                .param("p", projectId).query(mapper(ScheduleEntry.Kind.PROGRAM)).list());
        all.addAll(this.jdbc.sql(SHIFT_SELECT
                        + "WHERE r.starts_at IS NOT NULL AND (CAST(:p AS bigint) IS NULL OR r.project_id = :p)")
                .param("p", projectId).query(mapper(ScheduleEntry.Kind.SMENA)).list());
        return sorted(all);
    }

    /** Osobny program: vlastnictvo, roly (cez tagy), smeny a otvorene ulohy s terminom. Zrusene aktivity vynechava. */
    public List<ScheduleEntry> ofPerson(long personId, Long projectId) {
        List<ScheduleEntry> all = new ArrayList<>(this.jdbc.sql(AGENDA_SELECT + """
                        WHERE pr.status <> 'ZRUSENA' AND (CAST(:p AS bigint) IS NULL OR ai.project_id = :p)
                          AND (ai.owner_person_id = :person OR EXISTS (
                               SELECT 1 FROM assignment a JOIN activity_role r ON r.id = a.role_id, unnest(ai.tags) t
                               WHERE a.person_id = :person AND a.status <> 'ODMIETOL' AND r.project_id = ai.project_id
                                 AND (lower(t) = lower(r.name) OR lower(t) IN\s""" + EVERYONE_SQL + ")))")
                .param("p", projectId).param("person", personId)
                .query(mapper(ScheduleEntry.Kind.PROGRAM)).list());
        all.addAll(this.jdbc.sql("""
                        SELECT r.id, r.project_id, pr.code AS activity_code, pr.name AS activity_name, r.starts_at,
                               r.ends_at, r.name || CASE a.status WHEN 'POZVANY' THEN ' (čaká na potvrdenie)' ELSE '' END
                               AS title, pr.location, NULL::bigint AS owner_person_id, NULL AS owner_name,
                               ARRAY[r.name] AS tags, r.description AS note, NULL AS people
                        FROM assignment a JOIN activity_role r ON r.id = a.role_id JOIN project pr ON pr.id = r.project_id
                        WHERE a.person_id = :person AND a.status <> 'ODMIETOL' AND r.starts_at IS NOT NULL
                          AND pr.status <> 'ZRUSENA' AND (CAST(:p AS bigint) IS NULL OR r.project_id = :p)""")
                .param("p", projectId).param("person", personId)
                .query(mapper(ScheduleEntry.Kind.SMENA)).list());
        all.addAll(this.jdbc.sql("""
                        SELECT t.id, t.project_id, pr.code AS activity_code, pr.name AS activity_name,
                               t.due_on::timestamp AS starts_at, NULL::timestamp AS ends_at, t.title, NULL AS location,
                               t.assignee_person_id AS owner_person_id, NULL AS owner_name, ARRAY[t.section] AS tags,
                               NULL AS note, NULL AS people
                        FROM task t JOIN project pr ON pr.id = t.project_id
                        WHERE t.assignee_person_id = :person AND NOT t.done AND t.due_on IS NOT NULL
                          AND pr.status <> 'ZRUSENA' AND (CAST(:p AS bigint) IS NULL OR t.project_id = :p)""")
                .param("p", projectId).param("person", personId)
                .query(mapper(ScheduleEntry.Kind.TERMIN)).list());
        return sorted(all);
    }

    /** Ludia, ktori maju na aktivite nejaky program: priradeni do roli (okrem odmietnutych) a vlastnici bodov. */
    public record Member(long personId, String fullName, String email, String roles) {
    }

    public List<Member> members(long projectId) {
        return this.jdbc.sql("""
                        SELECT p.id AS person_id, p.full_name, p.email,
                               string_agg(DISTINCT r.name, ', ') FILTER (WHERE r.name IS NOT NULL) AS roles
                        FROM person p
                             LEFT JOIN assignment a ON a.person_id = p.id AND a.status <> 'ODMIETOL'
                             LEFT JOIN activity_role r ON r.id = a.role_id AND r.project_id = :p
                        WHERE r.id IS NOT NULL
                           OR EXISTS (SELECT 1 FROM agenda_item ai WHERE ai.project_id = :p AND ai.owner_person_id = p.id)
                        GROUP BY p.id, p.full_name, p.email ORDER BY p.full_name""")
                .param("p", projectId).query(Member.class).list();
    }

    /** Roly aktivity a tagy z programu - moznosti filtra "pre koho". */
    public List<String> audiences(long projectId) {
        return this.jdbc.sql("""
                        SELECT DISTINCT a FROM (
                            SELECT name AS a FROM activity_role WHERE project_id = :p
                            UNION SELECT unnest(tags) FROM agenda_item WHERE project_id = :p) x
                        ORDER BY a""")
                .param("p", projectId).query(String.class).list();
    }

    public long insert(long projectId, AgendaRow r) {
        return this.jdbc.sql("""
                        INSERT INTO agenda_item (project_id, starts_at, ends_at, title, location, owner_person_id, tags, note)
                        VALUES (:p, :startsAt, :endsAt, :title, :location, :owner, :tags, :note) RETURNING id""")
                .param("p", projectId).param("startsAt", r.startsAt()).param("endsAt", r.endsAt())
                .param("title", r.title()).param("location", r.location()).param("owner", r.ownerPersonId())
                .param("tags", r.tags().toArray(String[]::new)).param("note", r.note())
                .query(Long.class).single();
    }

    public int update(long projectId, long id, AgendaRow r) {
        return this.jdbc.sql("""
                        UPDATE agenda_item SET starts_at = :startsAt, ends_at = :endsAt, title = :title,
                               location = :location, owner_person_id = :owner, tags = :tags, note = :note
                        WHERE id = :id AND project_id = :p""")
                .param("p", projectId).param("id", id).param("startsAt", r.startsAt()).param("endsAt", r.endsAt())
                .param("title", r.title()).param("location", r.location()).param("owner", r.ownerPersonId())
                .param("tags", r.tags().toArray(String[]::new)).param("note", r.note())
                .update();
    }

    public int delete(long projectId, long id) {
        return this.jdbc.sql("DELETE FROM agenda_item WHERE id = :id AND project_id = :p")
                .param("id", id).param("p", projectId).update();
    }

    // ---------- odber kalendara ----------

    public Optional<String> calendarToken(long personId) {
        return this.jdbc.sql("SELECT calendar_token FROM person WHERE id = :id AND calendar_token IS NOT NULL")
                .param("id", personId).query(String.class).optional();
    }

    public void setCalendarToken(long personId, String token) {
        this.jdbc.sql("UPDATE person SET calendar_token = :t WHERE id = :id")
                .param("t", token).param("id", personId).update();
    }

    public record FeedOwner(long id, String fullName) {
    }

    public Optional<FeedOwner> byCalendarToken(String token) {
        return this.jdbc.sql("SELECT id, full_name FROM person WHERE calendar_token = :t")
                .param("t", token).query(FeedOwner.class).optional();
    }

    private static List<ScheduleEntry> sorted(List<ScheduleEntry> all) {
        all.sort(Comparator.comparing(ScheduleEntry::startsAt).thenComparing(ScheduleEntry::activityCode)
                .thenComparing(ScheduleEntry::title));
        return all;
    }

    private static LocalDateTime time(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDateTime.class);
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static List<String> tags(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[])a.getArray());
    }
}
