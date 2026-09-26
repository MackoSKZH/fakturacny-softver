package sk.firstglobal.hq.web.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** Zaznam kto, kedy a co zmenil. Tabulka je v DB chranena proti uprave a mazaniu. */
@Component
public class AuditLog {
    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(LocalDateTime at, String actor, String action, String entity, String entityId, String detail) {
    }

    public void record(String actor, String action, String entity, Object entityId, String detail) {
        this.jdbc.sql("""
                        INSERT INTO audit_log (actor, action, entity, entity_id, detail)
                        VALUES (:actor, :action, :entity, :entityId, :detail)""")
                .param("actor", actor)
                .param("action", action)
                .param("entity", entity)
                .param("entityId", entityId == null ? null : entityId.toString())
                .param("detail", detail)
                .update();
    }

    public List<Entry> latest(int limit) {
        // Cas zobrazujeme v slovenskej zone bez ohladu na zonu servera.
        return this.jdbc.sql("""
                        SELECT at AT TIME ZONE 'Europe/Bratislava' AS at, actor, action, entity, entity_id, detail
                        FROM audit_log ORDER BY id DESC LIMIT :limit""")
                .param("limit", limit)
                .query(Entry.class)
                .list();
    }
}
