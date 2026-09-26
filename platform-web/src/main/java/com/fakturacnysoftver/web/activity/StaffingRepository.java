package com.fakturacnysoftver.web.activity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Roly/smeny na aktivite a priradenia ludi. */
@Repository
public class StaffingRepository {
    private final JdbcClient jdbc;

    public StaffingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Seat(long assignmentId, long personId, String personName, String status, BigDecimal hours,
                       boolean minor, boolean consentMissing) {
        public String statusLabel() {
            return switch (this.status) {
                case "POTVRDENY" -> "potvrdený";
                case "ODMIETOL" -> "odmietol";
                case "ZUCASTNIL_SA" -> "zúčastnil sa";
                default -> "pozvaný";
            };
        }
    }

    public record Role(long id, String name, LocalDateTime startsAt, LocalDateTime endsAt, int needed,
                       String description, List<Seat> seats) {
        public long confirmed() {
            return this.seats.stream().filter(s -> s.status().equals("POTVRDENY") || s.status().equals("ZUCASTNIL_SA")).count();
        }

        public boolean isFull() {
            return this.confirmed() >= this.needed;
        }
    }

    private record RoleRow(long id, String name, LocalDateTime startsAt, LocalDateTime endsAt, int needed,
                           String description) {
    }

    private record SeatRow(long roleId, long assignmentId, long personId, String personName, String status,
                           BigDecimal hours, boolean minor, boolean consentMissing) {
    }

    public List<Role> rolesOf(long projectId) {
        List<RoleRow> roles = this.jdbc.sql("""
                        SELECT id, name, starts_at, ends_at, needed, description FROM activity_role
                        WHERE project_id = :p ORDER BY starts_at NULLS FIRST, name, id""")
                .param("p", projectId).query(RoleRow.class).list();
        List<SeatRow> seats = this.jdbc.sql("""
                        SELECT a.role_id, a.id AS assignment_id, p.id AS person_id, p.full_name AS person_name, a.status,
                               a.hours, p.is_minor AS minor,
                               (p.data_consent_on IS NULL OR (p.is_minor AND NOT p.consent_by_guardian)) AS consent_missing
                        FROM assignment a JOIN person p ON p.id = a.person_id JOIN activity_role r ON r.id = a.role_id
                        WHERE r.project_id = :p ORDER BY p.full_name""")
                .param("p", projectId).query(SeatRow.class).list();
        Map<Long, List<Seat>> byRole = new LinkedHashMap<>();
        for (SeatRow s : seats) {
            byRole.computeIfAbsent(s.roleId(), k -> new ArrayList<>()).add(new Seat(s.assignmentId(), s.personId(),
                    s.personName(), s.status(), s.hours(), s.minor(), s.consentMissing()));
        }
        return roles.stream().map(r -> new Role(r.id(), r.name(), r.startsAt(), r.endsAt(), r.needed(), r.description(),
                byRole.getOrDefault(r.id(), List.of()))).toList();
    }

    public long addRole(long projectId, String name, LocalDateTime startsAt, LocalDateTime endsAt, int needed,
                        String description) {
        return this.jdbc.sql("""
                        INSERT INTO activity_role (project_id, name, starts_at, ends_at, needed, description)
                        VALUES (:p, :name, :startsAt, :endsAt, :needed, :description) RETURNING id""")
                .param("p", projectId).param("name", name).param("startsAt", startsAt).param("endsAt", endsAt)
                .param("needed", needed).param("description", description)
                .query(Long.class).single();
    }

    public int deleteRole(long projectId, long roleId) {
        return this.jdbc.sql("DELETE FROM activity_role WHERE id = :r AND project_id = :p")
                .param("r", roleId).param("p", projectId).update();
    }

    public Optional<Long> roleProject(long roleId) {
        return this.jdbc.sql("SELECT project_id FROM activity_role WHERE id = :r").param("r", roleId)
                .query(Long.class).optional();
    }

    /** Kedy sa smena/akcia zacina - "zucastnil sa" sa neda zapisat vopred. */
    public Optional<java.time.LocalDate> assignmentDay(long projectId, long assignmentId) {
        return this.jdbc.sql("""
                        SELECT COALESCE(r.starts_at::date, p.starts_on) FROM assignment a
                        JOIN activity_role r ON r.id = a.role_id JOIN project p ON p.id = r.project_id
                        WHERE a.id = :a AND r.project_id = :p""")
                .param("a", assignmentId).param("p", projectId).query(java.time.LocalDate.class).optional();
    }

    public boolean assign(long roleId, long personId) {
        return this.jdbc.sql("""
                        INSERT INTO assignment (role_id, person_id) VALUES (:r, :person)
                        ON CONFLICT (role_id, person_id) DO NOTHING""")
                .param("r", roleId).param("person", personId).update() == 1;
    }

    public int update(long projectId, long assignmentId, String status, BigDecimal hours) {
        return this.jdbc.sql("""
                        UPDATE assignment a SET status = :status, hours = :hours
                        FROM activity_role r WHERE a.id = :a AND r.id = a.role_id AND r.project_id = :p""")
                .param("status", status).param("hours", hours).param("a", assignmentId).param("p", projectId)
                .update();
    }

    public int remove(long projectId, long assignmentId) {
        return this.jdbc.sql("""
                        DELETE FROM assignment a USING activity_role r
                        WHERE a.id = :a AND r.id = a.role_id AND r.project_id = :p""")
                .param("a", assignmentId).param("p", projectId).update();
    }
}
