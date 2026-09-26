package com.fakturacnysoftver.web.donation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Prispevky od verejnosti na aktivitu. Variabilny symbol aktivity je 800000 + id (nekoliduje s cislami faktur).
 * Vyzbierana suma = prijmy, ktore maju v poli "Doklad" tento VS.
 */
@Repository
public class DonationRepository {
    public static final long VS_BASE = 800000;

    private final JdbcClient jdbc;

    public DonationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Settings(long projectId, String code, String name, boolean enabled, String text, BigDecimal goal,
                           BigDecimal collected, int donors) {
        public String variableSymbol() {
            return String.valueOf(VS_BASE + this.projectId);
        }

        /** 1 prispevok, 2-4 prispevky, 0 a 5+ prispevkov. */
        public String donorsLabel() {
            int n = this.donors;
            return n + (n == 1 ? " príspevok" : n >= 2 && n <= 4 ? " príspevky" : " príspevkov");
        }

        public int percent() {
            return this.goal == null ? 0 : Math.min(100, this.collected.multiply(BigDecimal.valueOf(100))
                    .divide(this.goal, 0, java.math.RoundingMode.DOWN).intValue());
        }
    }

    private static final String SELECT = """
            SELECT p.id AS project_id, p.code, p.name, p.donations_public AS enabled, p.donation_text AS text,
                   p.donation_goal AS goal,
                   COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.direction = 'PRIJEM'
                             AND trim(l.document_ref) = (:base + p.id)::text), 0) AS collected,
                   (SELECT count(*) FROM ledger_entry l WHERE l.direction = 'PRIJEM'
                    AND trim(l.document_ref) = (:base + p.id)::text)::int AS donors
            FROM project p
            """;

    public Optional<Settings> of(long projectId) {
        return this.jdbc.sql(SELECT + "WHERE p.id = :id").param("base", VS_BASE).param("id", projectId)
                .query(Settings.class).optional();
    }

    /** Verejna stranka existuje len pre zapnutu a nezrusenu aktivitu. */
    public Optional<Settings> publicByCode(String code) {
        return this.jdbc.sql(SELECT + "WHERE upper(p.code) = upper(:code) AND p.donations_public AND p.status <> 'ZRUSENA'")
                .param("base", VS_BASE).param("code", code).query(Settings.class).optional();
    }

    public void update(long projectId, boolean enabled, String text, BigDecimal goal) {
        this.jdbc.sql("""
                        UPDATE project SET donations_public = :enabled, donation_text = :text, donation_goal = :goal
                        WHERE id = :id""")
                .param("enabled", enabled).param("text", text).param("goal", goal).param("id", projectId).update();
    }
}
