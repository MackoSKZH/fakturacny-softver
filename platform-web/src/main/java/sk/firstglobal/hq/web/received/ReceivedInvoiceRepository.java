package sk.firstglobal.hq.web.received;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ReceivedInvoiceRepository {
    private final JdbcClient jdbc;

    public ReceivedInvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record ReceivedInvoice(long id, String docType, String number, String supplierName, String supplierIco,
                                  String supplierDic, String supplierIban, LocalDate issueDate, LocalDate dueDate,
                                  String currency, BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalPayable,
                                  String paymentRef, Long projectId, String projectCode, String category, String note,
                                  String source, LocalDate paidOn, String createdBy, OffsetDateTime createdAt) {
        public boolean isCreditNote() {
            return "DOBROPIS".equals(this.docType);
        }

        public boolean isOverdue(LocalDate today) {
            return this.paidOn == null && this.dueDate != null && this.dueDate.isBefore(today);
        }

        public boolean isEInvoice() {
            return "UBL".equals(this.source);
        }
    }

    private static final String SELECT = """
            SELECT r.id, r.doc_type, r.number, r.supplier_name, r.supplier_ico, r.supplier_dic, r.supplier_iban,
                   r.issue_date, r.due_date, r.currency, r.total_net, r.total_vat, r.total_payable, r.payment_ref,
                   r.project_id, p.code AS project_code, r.category, r.note, r.source, r.paid_on, r.created_by, r.created_at
            FROM received_invoice r LEFT JOIN project p ON p.id = r.project_id
            """;

    public List<ReceivedInvoice> findAll() {
        return this.jdbc.sql(SELECT + "ORDER BY r.paid_on IS NOT NULL, r.due_date NULLS LAST, r.issue_date DESC, r.id DESC")
                .query(ReceivedInvoice.class).list();
    }

    public Optional<ReceivedInvoice> find(long id) {
        return this.jdbc.sql(SELECT + "WHERE r.id = :id").param("id", id).query(ReceivedInvoice.class).optional();
    }

    public List<ReceivedInvoice> unpaid() {
        return this.jdbc.sql(SELECT + "WHERE r.paid_on IS NULL").query(ReceivedInvoice.class).list();
    }

    public boolean shaExists(String sha) {
        return this.jdbc.sql("SELECT count(*) FROM received_invoice WHERE sha256 = :s").param("s", sha)
                .query(Long.class).single() > 0;
    }

    public record Row(String docType, String number, String supplierName, String supplierIco, String supplierDic,
                      String supplierIban, LocalDate issueDate, LocalDate dueDate, String currency, BigDecimal totalNet,
                      BigDecimal totalVat, BigDecimal totalPayable, String paymentRef, Long projectId, String category,
                      String note, String source, String xml, String sha256) {
    }

    public long insert(Row r, String actor) {
        Map<String, Object> m = new HashMap<>();
        m.put("docType", r.docType());
        m.put("number", r.number());
        m.put("supplierName", r.supplierName());
        m.put("supplierIco", r.supplierIco());
        m.put("supplierDic", r.supplierDic());
        m.put("supplierIban", r.supplierIban());
        m.put("issueDate", r.issueDate());
        m.put("dueDate", r.dueDate());
        m.put("currency", r.currency());
        m.put("totalNet", r.totalNet());
        m.put("totalVat", r.totalVat());
        m.put("totalPayable", r.totalPayable());
        m.put("paymentRef", r.paymentRef());
        m.put("projectId", r.projectId());
        m.put("category", r.category());
        m.put("note", r.note());
        m.put("source", r.source());
        m.put("xml", r.xml());
        m.put("sha", r.sha256());
        m.put("actor", actor);
        return this.jdbc.sql("""
                        INSERT INTO received_invoice (doc_type, number, supplier_name, supplier_ico, supplier_dic,
                               supplier_iban, issue_date, due_date, currency, total_net, total_vat, total_payable,
                               payment_ref, project_id, category, note, source, xml, sha256, created_by)
                        VALUES (:docType, :number, :supplierName, :supplierIco, :supplierDic, :supplierIban, :issueDate,
                                :dueDate, :currency, :totalNet, :totalVat, :totalPayable, :paymentRef, :projectId,
                                :category, :note, :source, :xml, :sha, :actor) RETURNING id""")
                .params(m).query(Long.class).single();
    }

    public void updateDetails(long id, Long projectId, String category, String note) {
        this.jdbc.sql("UPDATE received_invoice SET project_id = :p, category = :c, note = :n WHERE id = :id")
                .param("p", projectId).param("c", category).param("n", note).param("id", id).update();
    }

    public void setPaid(long id, LocalDate paidOn) {
        this.jdbc.sql("UPDATE received_invoice SET paid_on = :d WHERE id = :id").param("d", paidOn).param("id", id).update();
    }

    public String xml(long id) {
        return this.jdbc.sql("SELECT xml FROM received_invoice WHERE id = :id").param("id", id).query(String.class)
                .optional().orElse(null);
    }

    public int delete(long id) {
        return this.jdbc.sql("DELETE FROM received_invoice WHERE id = :id AND paid_on IS NULL").param("id", id).update();
    }
}
