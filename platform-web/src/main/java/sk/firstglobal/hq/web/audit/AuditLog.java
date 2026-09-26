package sk.firstglobal.hq.web.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Zaznam kto, kedy a co zmenil. Tabulka je v DB chranena proti uprave a mazaniu. */
@Component
public class AuditLog {
    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Entry(LocalDateTime at, String actor, String action, String entity, String entityId, String detail) {
    }

    /** Riadok prehladu pre admina - s id kvoli strankovaniu a s odkazom na zaznam, ak ma vlastnu stranku. */
    public record Row(long id, LocalDateTime at, String actor, String action, String entity, String entityId,
                      String detail) {
        private static final Map<String, String> PAGES = Map.of("aktivita", "/aktivity/", "dohoda", "/financovanie/",
                "dosla_faktura", "/prijate-faktury/", "faktura", "/faktury/", "majetok", "/majetok/", "osoba", "/ludia/",
                "partner", "/partneri/");

        public String link() {
            String base = PAGES.get(this.entity);
            return base != null && this.entityId != null && this.entityId.matches("\\d{1,18}") ? base + this.entityId : null;
        }
    }

    /** Filter prehladu; prazdne polia sa ignoruju. */
    public record Filter(String actor, String entity, String action, LocalDate from, LocalDate to, String q) {
        public boolean isEmpty() {
            return blank(this.actor) && blank(this.entity) && blank(this.action) && this.from == null && this.to == null
                    && blank(this.q);
        }

        /** Query string pre exporty a dalsiu stranu (bez "?"). */
        public String query() {
            StringBuilder b = new StringBuilder();
            add(b, "actor", this.actor);
            add(b, "entity", this.entity);
            add(b, "action", this.action);
            add(b, "from", this.from == null ? null : this.from.toString());
            add(b, "to", this.to == null ? null : this.to.toString());
            add(b, "q", this.q);
            return b.toString();
        }

        private static void add(StringBuilder b, String k, String v) {
            if (!blank(v)) {
                b.append(b.isEmpty() ? "" : "&").append(k).append('=')
                        .append(java.net.URLEncoder.encode(v.trim(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }

        private static boolean blank(String s) {
            return s == null || s.isBlank();
        }
    }

    /** Novsie ako beforeId (null = od najnovsieho), najviac limit riadkov, podla filtra. */
    public List<Row> search(Filter f, Long beforeId, int limit) {
        StringBuilder where = new StringBuilder("WHERE true");
        Map<String, Object> params = new HashMap<>();
        if (beforeId != null) {
            where.append(" AND id < :before");
            params.put("before", beforeId);
        }
        if (!Filter.blank(f.actor())) {
            where.append(" AND actor ILIKE :actor");
            params.put("actor", "%" + like(f.actor().trim()) + "%");
        }
        if (!Filter.blank(f.entity())) {
            where.append(" AND entity = :entity");
            params.put("entity", f.entity().trim());
        }
        if (!Filter.blank(f.action())) {
            where.append(" AND action = :action");
            params.put("action", f.action().trim());
        }
        if (f.from() != null) {
            where.append(" AND at >= (CAST(:from AS date) AT TIME ZONE 'Europe/Bratislava')");
            params.put("from", f.from());
        }
        if (f.to() != null) {
            where.append(" AND at < ((CAST(:to AS date) + 1) AT TIME ZONE 'Europe/Bratislava')");
            params.put("to", f.to());
        }
        if (!Filter.blank(f.q())) {
            where.append(" AND (detail ILIKE :q OR entity_id = :qExact)");
            params.put("q", "%" + like(f.q().trim()) + "%");
            params.put("qExact", f.q().trim());
        }
        params.put("limit", limit);
        return this.jdbc.sql("SELECT id, at AT TIME ZONE 'Europe/Bratislava' AS at, actor, action, entity, entity_id, detail "
                        + "FROM audit_log " + where + " ORDER BY id DESC LIMIT :limit")
                .params(params).query(Row.class).list();
    }

    public List<String> entities() {
        return this.jdbc.sql("SELECT DISTINCT entity FROM audit_log ORDER BY entity").query(String.class).list();
    }

    public List<String> actions() {
        return this.jdbc.sql("SELECT DISTINCT action FROM audit_log ORDER BY action").query(String.class).list();
    }

    private static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
