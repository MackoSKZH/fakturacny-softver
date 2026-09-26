package com.fakturacnysoftver.web.ledger;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class LedgerRepository {
    private static final String SELECT = """
            SELECT l.id, l.entry_date, l.description, l.direction, l.amount, l.project_id, p.code AS project_code,
                   l.tags, l.category, l.counterparty, l.document_ref, l.payment_method, l.note, l.invoice_id,
                   COALESCE(i.number, ri.number) AS invoice_number, l.received_invoice_id, l.version
            FROM ledger_entry l
            LEFT JOIN project p ON p.id = l.project_id
            LEFT JOIN invoice i ON i.id = l.invoice_id
            LEFT JOIN received_invoice ri ON ri.id = l.received_invoice_id""";

    private static final RowMapper<LedgerEntry> MAPPER = (rs, n) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getObject("entry_date", LocalDate.class),
            rs.getString("description"),
            rs.getString("direction"),
            rs.getBigDecimal("amount"),
            nullableLong(rs, "project_id"),
            rs.getString("project_code"),
            tags(rs.getArray("tags")),
            rs.getString("category"),
            rs.getString("counterparty"),
            rs.getString("document_ref"),
            rs.getString("payment_method"),
            rs.getString("note"),
            nullableLong(rs, "invoice_id"),
            rs.getString("invoice_number"),
            nullableLong(rs, "received_invoice_id"),
            rs.getInt("version"));

    private final JdbcClient jdbc;

    public LedgerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Kto meni - trigger ho zapise do historie. Plati do konca transakcie. */
    public void setActor(String actor) {
        this.jdbc.sql("SELECT set_config('app.actor', :actor, true)").param("actor", actor).query(String.class).single();
    }

    public List<LedgerEntry> findAll() {
        return this.jdbc.sql(SELECT + " ORDER BY l.entry_date DESC, l.id DESC").query(MAPPER).list();
    }

    public Optional<LedgerEntry> findById(long id) {
        return this.jdbc.sql(SELECT + " WHERE l.id = :id").param("id", id).query(MAPPER).optional();
    }

    public Optional<LedgerEntry> findByInvoice(long invoiceId) {
        return this.jdbc.sql(SELECT + " WHERE l.invoice_id = :id").param("id", invoiceId).query(MAPPER).optional();
    }

    public List<String> allTags() {
        return this.jdbc.sql("SELECT DISTINCT unnest(tags) AS t FROM ledger_entry ORDER BY t").query(String.class).list();
    }

    public List<String> allCategories() {
        return this.jdbc.sql("SELECT DISTINCT category FROM ledger_entry WHERE category IS NOT NULL ORDER BY category")
                .query(String.class).list();
    }

    public record Row(LocalDate entryDate, String description, String direction, BigDecimal amount, Long projectId,
                      String[] tags, String category, String counterparty, String documentRef, String paymentMethod,
                      String note, Long invoiceId, Long receivedInvoiceId) {
    }

    public long insert(Row r) {
        return this.jdbc.sql("""
                        INSERT INTO ledger_entry (entry_date, description, direction, amount, project_id, tags, category,
                                                  counterparty, document_ref, payment_method, note, invoice_id,
                                                  received_invoice_id)
                        VALUES (:entryDate, :description, :direction, :amount, :projectId, :tags, :category,
                                :counterparty, :documentRef, :paymentMethod, :note, :invoiceId, :receivedInvoiceId)
                        RETURNING id""")
                .paramSource(r)
                .query(Long.class)
                .single();
    }

    /** Uprava s optimistickym zamkom; vrati false, ak medzitym niekto riadok zmenil. */
    public boolean update(long id, int version, Row r) {
        return this.jdbc.sql("""
                        UPDATE ledger_entry SET entry_date = :entryDate, description = :description,
                               direction = :direction, amount = :amount, project_id = :projectId, tags = :tags,
                               category = :category, counterparty = :counterparty, document_ref = :documentRef,
                               payment_method = :paymentMethod, note = :note,
                               version = version + 1, updated_at = now()
                        WHERE id = :id AND version = :version""")
                .params(params(r, id, version))
                .update() == 1;
    }

    private static java.util.Map<String, Object> params(Row r, long id, int version) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("entryDate", r.entryDate());
        m.put("description", r.description());
        m.put("direction", r.direction());
        m.put("amount", r.amount());
        m.put("projectId", r.projectId());
        m.put("tags", r.tags());
        m.put("category", r.category());
        m.put("counterparty", r.counterparty());
        m.put("documentRef", r.documentRef());
        m.put("paymentMethod", r.paymentMethod());
        m.put("note", r.note());
        m.put("id", id);
        m.put("version", version);
        return m;
    }

    public boolean delete(long id, int version) {
        return this.jdbc.sql("DELETE FROM ledger_entry WHERE id = :id AND version = :version")
                .param("id", id).param("version", version).update() == 1;
    }

    public void deleteByReceivedInvoice(long receivedInvoiceId) {
        this.jdbc.sql("DELETE FROM ledger_entry WHERE received_invoice_id = :id").param("id", receivedInvoiceId).update();
    }

    public Optional<LedgerEntry> findByReceivedInvoice(long receivedInvoiceId) {
        return this.jdbc.sql(SELECT + " WHERE l.received_invoice_id = :id").param("id", receivedInvoiceId)
                .query(MAPPER).optional();
    }

    public void deleteByInvoice(long invoiceId) {
        this.jdbc.sql("DELETE FROM ledger_entry WHERE invoice_id = :id").param("id", invoiceId).update();
    }

    public record HistoryItem(OffsetDateTime at, String actor, String op, String oldRow, String newRow) {
    }

    public List<HistoryItem> history(long entryId) {
        return this.jdbc.sql("""
                        SELECT at, actor, op, old_row::text AS old_row, new_row::text AS new_row
                        FROM ledger_history WHERE entry_id = :id ORDER BY id""")
                .param("id", entryId)
                .query(HistoryItem.class)
                .list();
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static List<String> tags(Array a) throws SQLException {
        return a == null ? List.of() : Arrays.asList((String[])a.getArray());
    }
}
