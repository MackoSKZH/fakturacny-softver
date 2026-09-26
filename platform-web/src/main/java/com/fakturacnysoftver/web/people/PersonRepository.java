package com.fakturacnysoftver.web.people;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class PersonRepository {
    private static final String SELECT = """
            SELECT id, full_name, email, phone, organization, roles, is_minor, guardian_name, guardian_contact,
                   data_consent_on, consent_by_guardian, photo_consent, note FROM person""";

    private static final RowMapper<Person> MAPPER = (rs, n) -> {
        Array roles = rs.getArray("roles");
        long id = rs.getLong("id");
        return new Person(id, rs.getString("full_name"), rs.getString("email"), rs.getString("phone"),
                rs.getString("organization"), roles == null ? List.of() : Arrays.asList((String[])roles.getArray()),
                rs.getBoolean("is_minor"), rs.getString("guardian_name"), rs.getString("guardian_contact"),
                rs.getObject("data_consent_on", LocalDate.class), rs.getBoolean("consent_by_guardian"),
                rs.getBoolean("photo_consent"), rs.getString("note"));
    };

    private final JdbcClient jdbc;

    public PersonRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Person> findAll() {
        return this.jdbc.sql(SELECT + " ORDER BY lower(full_name)").query(MAPPER).list();
    }

    public Optional<Person> findById(long id) {
        return this.jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<Person> findByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return this.jdbc.sql(SELECT + " WHERE lower(email) = lower(:e)").param("e", email.trim()).query(MAPPER).optional();
    }

    public boolean emailTaken(String email, Long exceptId) {
        return this.jdbc.sql("SELECT count(*) FROM person WHERE lower(email) = lower(:e) AND id <> COALESCE(:id, -1)")
                .param("e", email).param("id", exceptId).query(Long.class).single() > 0;
    }

    public long insert(Person p) {
        return this.jdbc.sql("""
                        INSERT INTO person (full_name, email, phone, organization, roles, is_minor, guardian_name,
                                            guardian_contact, data_consent_on, consent_by_guardian, photo_consent, note)
                        VALUES (:fullName, :email, :phone, :organization, :roles, :minor, :guardianName,
                                :guardianContact, :dataConsentOn, :consentByGuardian, :photoConsent, :note)
                        RETURNING id""")
                .params(params(p)).query(Long.class).single();
    }

    public void update(long id, Person p) {
        Map<String, Object> m = params(p);
        m.put("id", id);
        this.jdbc.sql("""
                        UPDATE person SET full_name = :fullName, email = :email, phone = :phone, organization = :organization,
                               roles = :roles, is_minor = :minor, guardian_name = :guardianName,
                               guardian_contact = :guardianContact, data_consent_on = :dataConsentOn,
                               consent_by_guardian = :consentByGuardian, photo_consent = :photoConsent, note = :note,
                               updated_at = now()
                        WHERE id = :id""")
                .params(m).update();
    }

    private static Map<String, Object> params(Person p) {
        Map<String, Object> m = new HashMap<>();
        m.put("fullName", p.fullName());
        m.put("email", p.email());
        m.put("phone", p.phone());
        m.put("organization", p.organization());
        m.put("roles", p.roles().toArray(String[]::new));
        m.put("minor", p.minor());
        m.put("guardianName", p.guardianName());
        m.put("guardianContact", p.guardianContact());
        m.put("dataConsentOn", p.dataConsentOn());
        m.put("consentByGuardian", p.consentByGuardian());
        m.put("photoConsent", p.photoConsent());
        m.put("note", p.note());
        return m;
    }

    public record Participation(long projectId, String activityName, String activityCode, String roleName,
                                LocalDateTime startsAt, String status, BigDecimal hours) {
    }

    public List<Participation> participation(long personId) {
        return this.jdbc.sql("""
                        SELECT p.id AS project_id, p.name AS activity_name, p.code AS activity_code, r.name AS role_name,
                               r.starts_at, a.status, a.hours
                        FROM assignment a JOIN activity_role r ON r.id = a.role_id JOIN project p ON p.id = r.project_id
                        WHERE a.person_id = :id ORDER BY COALESCE(r.starts_at, p.starts_on::timestamp) DESC NULLS LAST""")
                .param("id", personId).query(Participation.class).list();
    }

    public BigDecimal volunteerHours(long personId) {
        return this.jdbc.sql("""
                        SELECT COALESCE(sum(a.hours), 0) FROM assignment a
                        WHERE a.person_id = :id AND a.status = 'ZUCASTNIL_SA'""")
                .param("id", personId).query(BigDecimal.class).single();
    }
}
