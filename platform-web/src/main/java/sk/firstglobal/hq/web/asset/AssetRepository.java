package sk.firstglobal.hq.web.asset;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AssetRepository {
    private final JdbcClient jdbc;

    public AssetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
            SELECT a.id, a.inventory_no, a.name, a.category, a.serial_no, a.purchased_on, a.price, a.ledger_entry_id,
                   CASE WHEN l.id IS NULL THEN NULL
                        ELSE to_char(l.entry_date, 'FMDD.FMMM.YYYY') || ' ' || l.description || COALESCE(' (' || l.document_ref || ')', '')
                   END AS ledger_label,
                   a.deal_id, d.title AS deal_title, a.keep_until, a.location, a.status, a.retired_on, a.retired_reason,
                   a.note, lo.id AS loan_id, lo.person_id AS borrower_id, p.full_name AS borrower_name, lo.lent_on,
                   lo.due_on AS loan_due_on
            FROM asset a
                 LEFT JOIN ledger_entry l ON l.id = a.ledger_entry_id
                 LEFT JOIN deal d ON d.id = a.deal_id
                 LEFT JOIN asset_loan lo ON lo.asset_id = a.id AND lo.returned_on IS NULL
                 LEFT JOIN person p ON p.id = lo.person_id
            """;

    public List<Asset> findAll() {
        return this.jdbc.sql(SELECT + "ORDER BY a.status = 'VYRADENY', a.category, a.inventory_no").query(Asset.class).list();
    }

    public Optional<Asset> findById(long id) {
        return this.jdbc.sql(SELECT + "WHERE a.id = :id").param("id", id).query(Asset.class).optional();
    }

    public List<Asset> lentTo(long personId) {
        return this.jdbc.sql(SELECT + "WHERE lo.person_id = :p ORDER BY lo.due_on NULLS LAST").param("p", personId)
                .query(Asset.class).list();
    }

    public boolean inventoryNoTaken(String no, Long exceptId) {
        return this.jdbc.sql("SELECT count(*) FROM asset WHERE lower(inventory_no) = lower(:n) AND id <> COALESCE(:id, -1)")
                .param("n", no).param("id", exceptId).query(Long.class).single() > 0;
    }

    /** Dalsie cislo v rade FGS-RRRR-NNN. */
    public String nextInventoryNo(int year) {
        String prefix = "FGS-" + year + "-";
        int next = this.jdbc.sql("""
                        SELECT COALESCE(max(substring(inventory_no FROM '(\\d+)$')::int), 0) + 1 FROM asset
                        WHERE inventory_no LIKE :prefix""")
                .param("prefix", prefix + "%").query(Integer.class).single();
        return prefix + String.format("%03d", next);
    }

    public record Row(String inventoryNo, String name, String category, String serialNo, LocalDate purchasedOn,
                      BigDecimal price, Long ledgerEntryId, Long dealId, LocalDate keepUntil, String location,
                      String note) {
    }

    private static Map<String, Object> params(Row r) {
        Map<String, Object> m = new HashMap<>();
        m.put("no", r.inventoryNo());
        m.put("name", r.name());
        m.put("category", r.category());
        m.put("serial", r.serialNo());
        m.put("purchasedOn", r.purchasedOn());
        m.put("price", r.price());
        m.put("ledger", r.ledgerEntryId());
        m.put("deal", r.dealId());
        m.put("keepUntil", r.keepUntil());
        m.put("location", r.location());
        m.put("note", r.note());
        return m;
    }

    public long insert(Row r) {
        return this.jdbc.sql("""
                        INSERT INTO asset (inventory_no, name, category, serial_no, purchased_on, price, ledger_entry_id,
                                           deal_id, keep_until, location, note)
                        VALUES (:no, :name, :category, :serial, :purchasedOn, :price, :ledger, :deal, :keepUntil,
                                :location, :note) RETURNING id""")
                .params(params(r)).query(Long.class).single();
    }

    public void update(long id, Row r) {
        this.jdbc.sql("""
                        UPDATE asset SET inventory_no = :no, name = :name, category = :category, serial_no = :serial,
                               purchased_on = :purchasedOn, price = :price, ledger_entry_id = :ledger, deal_id = :deal,
                               keep_until = :keepUntil, location = :location, note = :note
                        WHERE id = :id""")
                .params(params(r)).param("id", id).update();
    }

    public void setStatus(long id, String status) {
        this.jdbc.sql("UPDATE asset SET status = :s WHERE id = :id AND status <> 'VYRADENY'")
                .param("s", status).param("id", id).update();
    }

    public void retire(long id, LocalDate on, String reason) {
        this.jdbc.sql("UPDATE asset SET status = 'VYRADENY', retired_on = :on, retired_reason = :r WHERE id = :id")
                .param("on", on).param("r", reason).param("id", id).update();
    }

    // ---------- vypozicky ----------

    public record Loan(long id, long personId, String personName, String projectCode, LocalDate lentOn, LocalDate dueOn,
                       LocalDate returnedOn, String note, String lentBy) {
    }

    public List<Loan> loans(long assetId) {
        return this.jdbc.sql("""
                        SELECT lo.id, lo.person_id, p.full_name AS person_name, pr.code AS project_code, lo.lent_on,
                               lo.due_on, lo.returned_on, lo.note, lo.lent_by
                        FROM asset_loan lo JOIN person p ON p.id = lo.person_id LEFT JOIN project pr ON pr.id = lo.project_id
                        WHERE lo.asset_id = :a ORDER BY lo.lent_on DESC, lo.id DESC""")
                .param("a", assetId).query(Loan.class).list();
    }

    public void lend(long assetId, long personId, Long projectId, LocalDate lentOn, LocalDate dueOn, String note,
                     String actor) {
        this.jdbc.sql("""
                        INSERT INTO asset_loan (asset_id, person_id, project_id, lent_on, due_on, note, lent_by)
                        VALUES (:a, :p, :project, :lentOn, :dueOn, :note, :actor)""")
                .param("a", assetId).param("p", personId).param("project", projectId).param("lentOn", lentOn)
                .param("dueOn", dueOn).param("note", note).param("actor", actor).update();
    }

    public int returnLoan(long assetId, LocalDate on) {
        return this.jdbc.sql("UPDATE asset_loan SET returned_on = :on WHERE asset_id = :a AND returned_on IS NULL")
                .param("on", on).param("a", assetId).update();
    }

    // ---------- moznosti formulara ----------

    public record Option(long id, String label) {
    }

    public List<Option> expenseOptions() {
        return this.jdbc.sql("""
                        SELECT id, to_char(entry_date, 'FMDD.FMMM.YYYY') || ' · ' || description || ' · '
                                   || replace(to_char(amount, 'FM999999990.00'), '.', ',') || ' €' AS label
                        FROM ledger_entry WHERE direction = 'VYDAVOK' ORDER BY entry_date DESC, id DESC LIMIT 300""")
                .query(Option.class).list();
    }

    public List<Option> grantOptions() {
        return this.jdbc.sql("""
                        SELECT d.id, d.title || ' (' || pa.name || ')' AS label FROM deal d JOIN partner pa ON pa.id = d.partner_id
                        WHERE d.kind = 'GRANT' AND d.stage IN ('DOHODNUTE', 'ZAPLATENE') ORDER BY d.created_at DESC""")
                .query(Option.class).list();
    }
}
