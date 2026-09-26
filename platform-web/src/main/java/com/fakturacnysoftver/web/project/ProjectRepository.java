package com.fakturacnysoftver.web.project;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public class ProjectRepository {
    private static final String SELECT = """
            SELECT p.id, p.code, p.name, p.budget, p.active,
                   COALESCE((SELECT sum(CASE WHEN i.doc_type = 'DOBROPIS' THEN -i.total_payable ELSE i.total_payable END)
                             FROM invoice i WHERE i.project_id = p.id), 0) AS invoiced
            FROM project p""";

    private final JdbcClient jdbc;

    public ProjectRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Project> findAll() {
        return this.jdbc.sql(SELECT + " ORDER BY p.active DESC, p.code").query(Project.class).list();
    }

    public List<Project> findActive() {
        return this.jdbc.sql(SELECT + " WHERE p.active ORDER BY p.code").query(Project.class).list();
    }

    public Optional<Project> findById(long id) {
        return this.jdbc.sql(SELECT + " WHERE p.id = :id").param("id", id).query(Project.class).optional();
    }

    public boolean codeExists(String code) {
        return this.jdbc.sql("SELECT count(*) FROM project WHERE lower(code) = lower(:code)")
                .param("code", code)
                .query(Long.class)
                .single() > 0;
    }

    public long insert(String code, String name, BigDecimal budget) {
        return this.jdbc.sql("INSERT INTO project (code, name, budget) VALUES (:code, :name, :budget) RETURNING id")
                .param("code", code)
                .param("name", name)
                .param("budget", budget)
                .query(Long.class)
                .single();
    }
}
