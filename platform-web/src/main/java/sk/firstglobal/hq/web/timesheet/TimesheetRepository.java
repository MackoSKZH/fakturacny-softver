package sk.firstglobal.hq.web.timesheet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class TimesheetRepository {
    private final JdbcClient jdbc;

    public TimesheetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Contract(long id, long personId, String personName, String personEmail, String kind, String title,
                           String jobDescription, BigDecimal hourlyRate, LocalDate validFrom, LocalDate validTo,
                           Long projectId, String projectCode, String note) {
        public String kindLabel() {
            ContractKind k = ContractKind.parse(this.kind);
            return k == null ? this.kind : k.label();
        }

        public boolean isActiveOn(LocalDate d) {
            return !d.isBefore(this.validFrom) && !d.isAfter(this.validTo);
        }
    }

    private static final String CONTRACT = """
            SELECT c.id, c.person_id, p.full_name AS person_name, p.email AS person_email, c.kind, c.title,
                   c.job_description, c.hourly_rate, c.valid_from, c.valid_to, c.project_id, pr.code AS project_code, c.note
            FROM work_contract c JOIN person p ON p.id = c.person_id LEFT JOIN project pr ON pr.id = c.project_id""";

    public List<Contract> contracts() {
        return this.jdbc.sql(CONTRACT + " ORDER BY c.valid_to DESC, p.full_name").query(Contract.class).list();
    }

    public List<Contract> contractsOf(long personId) {
        return this.jdbc.sql(CONTRACT + " WHERE c.person_id = :p ORDER BY c.valid_to DESC")
                .param("p", personId).query(Contract.class).list();
    }

    public Optional<Contract> contract(long id) {
        return this.jdbc.sql(CONTRACT + " WHERE c.id = :id").param("id", id).query(Contract.class).optional();
    }

    public long insertContract(long personId, String kind, String title, String jobDescription, BigDecimal rate,
                               LocalDate from, LocalDate to, Long projectId, String note) {
        return this.jdbc.sql("""
                        INSERT INTO work_contract (person_id, kind, title, job_description, hourly_rate, valid_from, valid_to,
                                                   project_id, note)
                        VALUES (:person, :kind, :title, :job, :rate, :from, :to, :project, :note) RETURNING id""")
                .param("person", personId).param("kind", kind).param("title", title).param("job", jobDescription)
                .param("rate", rate).param("from", from).param("to", to).param("project", projectId).param("note", note)
                .query(Long.class).single();
    }

    public record Log(long id, LocalDate workDate, BigDecimal hours, Long projectId, String projectCode,
                      String description, String createdBy) {
    }

    public List<Log> logs(long contractId, LocalDate from, LocalDate to) {
        return this.jdbc.sql("""
                        SELECT l.id, l.work_date, l.hours, l.project_id, p.code AS project_code, l.description, l.created_by
                        FROM work_log l LEFT JOIN project p ON p.id = l.project_id
                        WHERE l.contract_id = :c AND l.work_date BETWEEN :from AND :to
                        ORDER BY l.work_date, l.id""")
                .param("c", contractId).param("from", from).param("to", to).query(Log.class).list();
    }

    public long insertLog(long contractId, LocalDate date, BigDecimal hours, Long projectId, String description,
                          String actor) {
        return this.jdbc.sql("""
                        INSERT INTO work_log (contract_id, work_date, hours, project_id, description, created_by)
                        VALUES (:c, :d, :h, :p, :desc, :actor) RETURNING id""")
                .param("c", contractId).param("d", date).param("h", hours).param("p", projectId)
                .param("desc", description).param("actor", actor).query(Long.class).single();
    }

    public int deleteLog(long contractId, long logId) {
        return this.jdbc.sql("DELETE FROM work_log WHERE id = :id AND contract_id = :c")
                .param("id", logId).param("c", contractId).update();
    }

    /** Sucet hodin cloveka v dany den cez vsetky jeho zmluvy (limit 12 h za 24 h). */
    public BigDecimal personHoursOn(long personId, LocalDate day) {
        return this.sum("""
                SELECT COALESCE(sum(l.hours), 0) FROM work_log l JOIN work_contract c ON c.id = l.contract_id
                WHERE c.person_id = :x AND l.work_date = :from AND l.work_date = :to""", personId, day, day);
    }

    public BigDecimal contractHours(long contractId, LocalDate from, LocalDate to) {
        return this.sum("""
                SELECT COALESCE(sum(hours), 0) FROM work_log WHERE contract_id = :x AND work_date BETWEEN :from AND :to""",
                contractId, from, to);
    }

    /** Vsetky dohody o vykonani prace cloveka v roku - limit 350 h sa neda obist viacerymi dohodami. */
    public BigDecimal personDovpHours(long personId, LocalDate from, LocalDate to) {
        return this.sum("""
                SELECT COALESCE(sum(l.hours), 0) FROM work_log l JOIN work_contract c ON c.id = l.contract_id
                WHERE c.person_id = :x AND c.kind = 'DOVP' AND l.work_date BETWEEN :from AND :to""", personId, from, to);
    }

    private BigDecimal sum(String sql, long x, LocalDate from, LocalDate to) {
        return this.jdbc.sql(sql).param("x", x).param("from", from).param("to", to).query(BigDecimal.class).single();
    }

    public record Closure(LocalDate month, BigDecimal totalHours, BigDecimal reward, String closedBy, OffsetDateTime closedAt) {
    }

    public Optional<Closure> closure(long contractId, LocalDate month) {
        return this.jdbc.sql("""
                        SELECT month, total_hours, reward, closed_by, closed_at FROM timesheet_close
                        WHERE contract_id = :c AND month = :m""")
                .param("c", contractId).param("m", month).query(Closure.class).optional();
    }

    public void close(long contractId, LocalDate month, BigDecimal hours, BigDecimal reward, String actor) {
        this.jdbc.sql("""
                        INSERT INTO timesheet_close (contract_id, month, total_hours, reward, closed_by)
                        VALUES (:c, :m, :h, :r, :actor)""")
                .param("c", contractId).param("m", month).param("h", hours).param("r", reward).param("actor", actor)
                .update();
    }
}
