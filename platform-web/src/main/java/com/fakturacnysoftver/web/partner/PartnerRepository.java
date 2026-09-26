package com.fakturacnysoftver.web.partner;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Partneri, historia komunikacie, dohody o financovani, protiplnenia a prepojenie na polozky. */
@Repository
public class PartnerRepository {
    private final JdbcClient jdbc;

    public PartnerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- partneri ----------

    private static final String PARTNER_SELECT = """
            SELECT p.id, p.name, p.kind, p.ico, p.web, p.contact_name, p.contact_email, p.contact_phone, p.customer_id,
                   p.owner_person_id, o.full_name AS owner_name, p.tags, p.note,
                   COALESCE((SELECT sum(amount) FROM deal d WHERE d.partner_id = p.id
                             AND d.stage IN ('DOHODNUTE', 'ZAPLATENE')), 0) AS secured,
                   COALESCE((SELECT sum(amount) FROM deal d WHERE d.partner_id = p.id
                             AND d.stage IN ('OSLOVENY', 'ROKUJEME')), 0) AS negotiating,
                   COALESCE((SELECT sum(l.amount) FROM ledger_entry l JOIN deal d ON d.id = l.deal_id
                             WHERE d.partner_id = p.id AND l.direction = 'PRIJEM'), 0) AS received,
                   (SELECT max(happened_on) FROM partner_note n WHERE n.partner_id = p.id) AS last_contact,
                   (SELECT min(next_step_on) FROM deal d WHERE d.partner_id = p.id
                    AND d.stage NOT IN ('ZAPLATENE', 'ODMIETNUTE')) AS next_step_on
            FROM partner p LEFT JOIN person o ON o.id = p.owner_person_id
            """;

    private static final RowMapper<Partner> PARTNER = (rs, n) -> new Partner(rs.getLong("id"), rs.getString("name"),
            rs.getString("kind"), rs.getString("ico"), rs.getString("web"), rs.getString("contact_name"),
            rs.getString("contact_email"), rs.getString("contact_phone"), nullableLong(rs, "customer_id"),
            nullableLong(rs, "owner_person_id"), rs.getString("owner_name"), tags(rs.getArray("tags")),
            rs.getString("note"), rs.getBigDecimal("secured"), rs.getBigDecimal("negotiating"),
            rs.getBigDecimal("received"), rs.getObject("last_contact", LocalDate.class),
            rs.getObject("next_step_on", LocalDate.class));

    public List<Partner> findAll() {
        return this.jdbc.sql(PARTNER_SELECT + "ORDER BY lower(p.name)").query(PARTNER).list();
    }

    public Optional<Partner> findById(long id) {
        return this.jdbc.sql(PARTNER_SELECT + "WHERE p.id = :id").param("id", id).query(PARTNER).optional();
    }

    public boolean nameTaken(String name, Long exceptId) {
        return this.jdbc.sql("SELECT count(*) FROM partner WHERE lower(name) = lower(:n) AND id <> COALESCE(:id, -1)")
                .param("n", name).param("id", exceptId).query(Long.class).single() > 0;
    }

    public record PartnerRow(String name, String kind, String ico, String web, String contactName, String contactEmail,
                             String contactPhone, Long customerId, Long ownerPersonId, List<String> tags, String note) {
    }

    private static Map<String, Object> params(PartnerRow r) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", r.name());
        m.put("kind", r.kind());
        m.put("ico", r.ico());
        m.put("web", r.web());
        m.put("contactName", r.contactName());
        m.put("contactEmail", r.contactEmail());
        m.put("contactPhone", r.contactPhone());
        m.put("customerId", r.customerId());
        m.put("owner", r.ownerPersonId());
        m.put("tags", r.tags().toArray(String[]::new));
        m.put("note", r.note());
        return m;
    }

    public long insert(PartnerRow r) {
        return this.jdbc.sql("""
                        INSERT INTO partner (name, kind, ico, web, contact_name, contact_email, contact_phone, customer_id,
                                             owner_person_id, tags, note)
                        VALUES (:name, :kind, :ico, :web, :contactName, :contactEmail, :contactPhone, :customerId, :owner,
                                :tags, :note) RETURNING id""")
                .params(params(r)).query(Long.class).single();
    }

    public void update(long id, PartnerRow r) {
        this.jdbc.sql("""
                        UPDATE partner SET name = :name, kind = :kind, ico = :ico, web = :web, contact_name = :contactName,
                               contact_email = :contactEmail, contact_phone = :contactPhone, customer_id = :customerId,
                               owner_person_id = :owner, tags = :tags, note = :note
                        WHERE id = :id""")
                .params(params(r)).param("id", id).update();
    }

    public record PartnerInvoice(long id, String number, String docType, LocalDate issueDate, BigDecimal totalPayable,
                                 LocalDate paidOn) {
    }

    /** Faktury (reklama) vystavene odberatelovi, ku ktoremu je partner prepojeny. */
    public List<PartnerInvoice> invoices(long customerId) {
        return this.jdbc.sql("""
                        SELECT id, number, doc_type, issue_date, total_payable, paid_on FROM invoice
                        WHERE customer_id = :c ORDER BY issue_date DESC, number DESC""")
                .param("c", customerId).query(PartnerInvoice.class).list();
    }

    // ---------- komunikacia ----------

    public record Note(long id, LocalDate happenedOn, String text, String author) {
    }

    public List<Note> notes(long partnerId) {
        return this.jdbc.sql("""
                        SELECT id, happened_on, text, author FROM partner_note WHERE partner_id = :p
                        ORDER BY happened_on DESC, id DESC""")
                .param("p", partnerId).query(Note.class).list();
    }

    public void addNote(long partnerId, LocalDate on, String text, String author) {
        this.jdbc.sql("INSERT INTO partner_note (partner_id, happened_on, text, author) VALUES (:p, :on, :t, :a)")
                .param("p", partnerId).param("on", on).param("t", text).param("a", author).update();
    }

    public int deleteNote(long partnerId, long noteId) {
        return this.jdbc.sql("DELETE FROM partner_note WHERE id = :id AND partner_id = :p")
                .param("id", noteId).param("p", partnerId).update();
    }

    // ---------- dohody ----------

    private static final String DEAL_SELECT = """
            SELECT d.id, d.partner_id, pa.name AS partner_name, d.project_id, pr.code AS project_code, d.title, d.kind,
                   d.stage, d.amount, d.expected_on, d.next_step, d.next_step_on, d.program, d.applied_on,
                   d.period_from, d.period_to, d.report_due_on, d.reported_on, d.note,
                   COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.deal_id = d.id
                             AND l.direction = 'PRIJEM'), 0) AS received,
                   COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.deal_id = d.id
                             AND l.direction = 'VYDAVOK'), 0) AS spent,
                   (SELECT count(*) FROM ledger_entry l WHERE l.deal_id = d.id AND l.direction = 'VYDAVOK'
                     AND ((d.period_from IS NOT NULL AND l.entry_date < d.period_from)
                       OR (d.period_to IS NOT NULL AND l.entry_date > d.period_to)))::int AS out_of_period,
                   (SELECT count(*) FROM deal_deliverable x WHERE x.deal_id = d.id)::int AS deliverables_total,
                   (SELECT count(*) FROM deal_deliverable x WHERE x.deal_id = d.id AND x.done)::int AS deliverables_done,
                   (SELECT count(*) FROM deal_deliverable x WHERE x.deal_id = d.id AND NOT x.done
                     AND x.due_on < :today)::int AS deliverables_overdue
            FROM deal d JOIN partner pa ON pa.id = d.partner_id LEFT JOIN project pr ON pr.id = d.project_id
            """;

    public List<Deal> deals(LocalDate today) {
        return this.jdbc.sql(DEAL_SELECT + """
                        ORDER BY array_position(ARRAY['ROKUJEME', 'OSLOVENY', 'DOHODNUTE', 'ZAPLATENE', 'ODMIETNUTE'], d.stage),
                                 d.next_step_on NULLS LAST, d.id""")
                .param("today", today).query(Deal.class).list();
    }

    public List<Deal> dealsOf(long partnerId, LocalDate today) {
        return this.jdbc.sql(DEAL_SELECT + "WHERE d.partner_id = :p ORDER BY d.created_at DESC")
                .param("p", partnerId).param("today", today).query(Deal.class).list();
    }

    public Optional<Deal> deal(long id, LocalDate today) {
        return this.jdbc.sql(DEAL_SELECT + "WHERE d.id = :id").param("id", id).param("today", today)
                .query(Deal.class).optional();
    }

    public record DealRow(long partnerId, Long projectId, String title, String kind, String stage, BigDecimal amount,
                          LocalDate expectedOn, String nextStep, LocalDate nextStepOn, String program,
                          LocalDate appliedOn, LocalDate periodFrom, LocalDate periodTo, LocalDate reportDueOn,
                          LocalDate reportedOn, String note) {
    }

    private static Map<String, Object> params(DealRow r) {
        Map<String, Object> m = new HashMap<>();
        m.put("partner", r.partnerId());
        m.put("project", r.projectId());
        m.put("title", r.title());
        m.put("kind", r.kind());
        m.put("stage", r.stage());
        m.put("amount", r.amount());
        m.put("expectedOn", r.expectedOn());
        m.put("nextStep", r.nextStep());
        m.put("nextStepOn", r.nextStepOn());
        m.put("program", r.program());
        m.put("appliedOn", r.appliedOn());
        m.put("periodFrom", r.periodFrom());
        m.put("periodTo", r.periodTo());
        m.put("reportDueOn", r.reportDueOn());
        m.put("reportedOn", r.reportedOn());
        m.put("note", r.note());
        return m;
    }

    public long insertDeal(DealRow r) {
        return this.jdbc.sql("""
                        INSERT INTO deal (partner_id, project_id, title, kind, stage, amount, expected_on, next_step,
                                          next_step_on, program, applied_on, period_from, period_to, report_due_on,
                                          reported_on, note)
                        VALUES (:partner, :project, :title, :kind, :stage, :amount, :expectedOn, :nextStep, :nextStepOn,
                                :program, :appliedOn, :periodFrom, :periodTo, :reportDueOn, :reportedOn, :note)
                        RETURNING id""")
                .params(params(r)).query(Long.class).single();
    }

    public void updateDeal(long id, DealRow r) {
        this.jdbc.sql("""
                        UPDATE deal SET project_id = :project, title = :title, kind = :kind, stage = :stage,
                               amount = :amount, expected_on = :expectedOn, next_step = :nextStep,
                               next_step_on = :nextStepOn, program = :program, applied_on = :appliedOn,
                               period_from = :periodFrom, period_to = :periodTo, report_due_on = :reportDueOn,
                               reported_on = :reportedOn, note = :note, updated_at = now()
                        WHERE id = :id""")
                .params(params(r)).param("id", id).update();
    }

    public int deleteDeal(long id) {
        return this.jdbc.sql("DELETE FROM deal WHERE id = :id").param("id", id).update();
    }

    // ---------- protiplnenia ----------

    public record Deliverable(long id, String title, LocalDate dueOn, boolean done, String doneBy) {
        public boolean isOverdue(LocalDate today) {
            return !this.done && this.dueOn != null && this.dueOn.isBefore(today);
        }
    }

    public List<Deliverable> deliverables(long dealId) {
        return this.jdbc.sql("""
                        SELECT id, title, due_on, done, done_by FROM deal_deliverable WHERE deal_id = :d
                        ORDER BY done, due_on NULLS LAST, id""")
                .param("d", dealId).query(Deliverable.class).list();
    }

    public void addDeliverable(long dealId, String title, LocalDate dueOn) {
        this.jdbc.sql("INSERT INTO deal_deliverable (deal_id, title, due_on) VALUES (:d, :t, :due)")
                .param("d", dealId).param("t", title).param("due", dueOn).update();
    }

    public int toggleDeliverable(long dealId, long id, String actor) {
        return this.jdbc.sql("""
                        UPDATE deal_deliverable SET done = NOT done,
                               done_at = CASE WHEN done THEN NULL ELSE now() END,
                               done_by = CASE WHEN done THEN NULL ELSE :actor END
                        WHERE id = :id AND deal_id = :d""")
                .param("actor", actor).param("id", id).param("d", dealId).update();
    }

    public int deleteDeliverable(long dealId, long id) {
        return this.jdbc.sql("DELETE FROM deal_deliverable WHERE id = :id AND deal_id = :d")
                .param("id", id).param("d", dealId).update();
    }

    // ---------- prepojenie na polozky ----------

    public record LinkedEntry(long id, LocalDate entryDate, String description, String direction, BigDecimal amount,
                              String projectCode, String documentRef, String counterparty, Long dealId) {
        public boolean isIncome() {
            return "PRIJEM".equals(this.direction);
        }
    }

    private static final String ENTRY_SELECT = """
            SELECT l.id, l.entry_date, l.description, l.direction, l.amount, pr.code AS project_code, l.document_ref,
                   l.counterparty, l.deal_id
            FROM ledger_entry l LEFT JOIN project pr ON pr.id = l.project_id
            """;

    public List<LinkedEntry> linkedEntries(long dealId) {
        return this.jdbc.sql(ENTRY_SELECT + "WHERE l.deal_id = :d ORDER BY l.entry_date, l.id")
                .param("d", dealId).query(LinkedEntry.class).list();
    }

    /** Kandidati na prepojenie: nepriradene polozky, najprv z tej istej aktivity. */
    public List<LinkedEntry> candidates(Long projectId, boolean incomeOnly) {
        return this.jdbc.sql(ENTRY_SELECT + """
                        WHERE l.deal_id IS NULL AND (NOT :incomeOnly OR l.direction = 'PRIJEM')
                        ORDER BY (l.project_id IS NOT DISTINCT FROM CAST(:p AS bigint)) DESC, l.entry_date DESC, l.id DESC
                        LIMIT 100""")
                .param("incomeOnly", incomeOnly).param("p", projectId).query(LinkedEntry.class).list();
    }

    public Optional<LinkedEntry> entry(long id) {
        return this.jdbc.sql(ENTRY_SELECT + "WHERE l.id = :id").param("id", id).query(LinkedEntry.class).optional();
    }

    /** Zmena ide cez historiu poloziek (trigger) a zvysi verziu - otvorena tabulka poloziek nic neprepise. */
    public int link(long entryId, long dealId, String actor) {
        this.setActor(actor);
        return this.jdbc.sql("""
                        UPDATE ledger_entry SET deal_id = :d, version = version + 1, updated_at = now()
                        WHERE id = :id AND deal_id IS NULL""")
                .param("d", dealId).param("id", entryId).update();
    }

    public int unlink(long entryId, long dealId, String actor) {
        this.setActor(actor);
        return this.jdbc.sql("""
                        UPDATE ledger_entry SET deal_id = NULL, version = version + 1, updated_at = now()
                        WHERE id = :id AND deal_id = :d""")
                .param("d", dealId).param("id", entryId).update();
    }

    public long linkedCount(long dealId) {
        return this.jdbc.sql("SELECT count(*) FROM ledger_entry WHERE deal_id = :d").param("d", dealId)
                .query(Long.class).single();
    }

    private void setActor(String actor) {
        this.jdbc.sql("SELECT set_config('app.actor', :actor, true)").param("actor", actor).query(String.class).single();
    }

    // ---------- financovanie aktivit ----------

    public record ActivityFunding(long id, String code, String name, BigDecimal budget, BigDecimal secured,
                                  BigDecimal negotiating, BigDecimal received) {
        public BigDecimal missing() {
            BigDecimal m = this.budget.subtract(this.secured);
            return m.signum() > 0 ? m : BigDecimal.ZERO;
        }

        public int securedPercent() {
            return this.budget.signum() == 0 ? 0
                    : Math.min(100, this.secured.multiply(BigDecimal.valueOf(100)).divide(this.budget, 0,
                    java.math.RoundingMode.DOWN).intValue());
        }
    }

    /** Investicie do startupov (NTE) nie su prijem zdruzenia, preto sa do krytia rozpoctu nepocitaju. */
    public List<ActivityFunding> fundingByActivity() {
        return this.jdbc.sql("""
                        SELECT p.id, p.code, p.name, p.budget,
                               COALESCE(sum(d.amount) FILTER (WHERE d.stage IN ('DOHODNUTE', 'ZAPLATENE')), 0) AS secured,
                               COALESCE(sum(d.amount) FILTER (WHERE d.stage IN ('OSLOVENY', 'ROKUJEME')), 0) AS negotiating,
                               COALESCE((SELECT sum(amount) FROM ledger_entry l WHERE l.project_id = p.id
                                         AND l.direction = 'PRIJEM'), 0) AS received
                        FROM project p LEFT JOIN deal d ON d.project_id = p.id AND d.kind <> 'INVESTICIA'
                        WHERE p.status <> 'ZRUSENA'
                        GROUP BY p.id ORDER BY p.starts_on NULLS LAST, p.code""")
                .query(ActivityFunding.class).list();
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static List<String> tags(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[])a.getArray());
    }
}
