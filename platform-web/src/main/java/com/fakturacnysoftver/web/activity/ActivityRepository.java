package com.fakturacnysoftver.web.activity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class ActivityRepository {
    private static final String SELECT = """
            SELECT p.id, p.code, p.name, p.kind, p.status, p.starts_on, p.ends_on, p.location, p.description, p.budget,
                   COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.project_id = p.id AND l.direction = 'VYDAVOK'), 0) AS spent,
                   COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.project_id = p.id AND l.direction = 'PRIJEM'), 0) AS income,
                   COALESCE((SELECT sum(r.needed) FROM activity_role r WHERE r.project_id = p.id), 0) AS seats_needed,
                   COALESCE((SELECT sum(LEAST(r.needed, (SELECT count(*) FROM assignment a WHERE a.role_id = r.id
                                                         AND a.status IN ('POTVRDENY', 'ZUCASTNIL_SA'))))
                             FROM activity_role r WHERE r.project_id = p.id), 0) AS seats_filled,
                   (SELECT count(*) FROM task t WHERE t.project_id = p.id) AS tasks_total,
                   (SELECT count(*) FROM task t WHERE t.project_id = p.id AND t.done) AS tasks_done,
                   (SELECT count(*) FROM task t WHERE t.project_id = p.id AND NOT t.done AND t.due_on < :today) AS tasks_overdue
            FROM project p""";

    private final JdbcClient jdbc;

    public ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Activity> findAll(LocalDate today) {
        return this.jdbc.sql(SELECT + " ORDER BY CASE p.status WHEN 'PREBIEHA' THEN 0 WHEN 'PRIPRAVA' THEN 1 ELSE 2 END,"
                        + " p.starts_on NULLS LAST, p.code")
                .param("today", today)
                .query(Activity.class)
                .list();
    }

    public Optional<Activity> findById(long id, LocalDate today) {
        return this.jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).param("today", today)
                .query(Activity.class).optional();
    }

    public record Fields(String code, String name, String kind, String status, LocalDate startsOn, LocalDate endsOn,
                         String location, String description, BigDecimal budget) {
    }

    public long create(Fields f) {
        return this.jdbc.sql("""
                        INSERT INTO project (code, name, kind, status, starts_on, ends_on, location, description, budget)
                        VALUES (:code, :name, :kind, :status, :startsOn, :endsOn, :location, :description, :budget)
                        RETURNING id""")
                .paramSource(f).query(Long.class).single();
    }

    public void update(long id, Fields f) {
        this.jdbc.sql("""
                        UPDATE project SET name = :name, kind = :kind, status = :status, starts_on = :startsOn,
                               ends_on = :endsOn, location = :location, description = :description, budget = :budget,
                               active = (:status IN ('PRIPRAVA', 'PREBIEHA'))
                        WHERE id = :id""")
                .param("id", id)
                .param("name", f.name()).param("kind", f.kind()).param("status", f.status())
                .param("startsOn", f.startsOn()).param("endsOn", f.endsOn()).param("location", f.location())
                .param("description", f.description()).param("budget", f.budget())
                .update();
    }

    public boolean codeExists(String code) {
        return this.jdbc.sql("SELECT count(*) FROM project WHERE lower(code) = lower(:code)").param("code", code)
                .query(Long.class).single() > 0;
    }
}
