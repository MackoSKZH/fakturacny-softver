package com.fakturacnysoftver.web.invoice;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class InvoiceRepository {
    private static final String SUMMARY = """
            SELECT i.id, i.number, i.issue_date, i.due_date, i.buyer_name, p.code AS project_code,
                   i.total_payable, i.currency, i.paid_on, (i.ubl_xml IS NOT NULL) AS has_ubl
            FROM invoice i LEFT JOIN project p ON p.id = i.project_id""";

    private final JdbcClient jdbc;

    public InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record NewInvoice(String number, LocalDate issueDate, LocalDate dueDate, long customerId, Long projectId,
                             String buyerName, BigDecimal totalNet, BigDecimal totalVat, BigDecimal totalPayable,
                             String currency, String document, String ublXml, byte[] pdf, String issuedBy) {
    }

    public long insert(NewInvoice n) {
        return this.jdbc.sql("""
                        INSERT INTO invoice (number, issue_date, due_date, customer_id, project_id, buyer_name,
                                             total_net, total_vat, total_payable, currency, document, ubl_xml,
                                             pdf, issued_by)
                        VALUES (:number, :issueDate, :dueDate, :customerId, :projectId, :buyerName,
                                :totalNet, :totalVat, :totalPayable, :currency, CAST(:document AS jsonb), :ublXml,
                                :pdf, :issuedBy)
                        RETURNING id""")
                .paramSource(n)
                .query(Long.class)
                .single();
    }

    public List<InvoiceSummary> findAll() {
        return this.jdbc.sql(SUMMARY + " ORDER BY i.issue_date DESC, i.id DESC")
                .query(InvoiceSummary.class)
                .list();
    }

    public Optional<InvoiceSummary> findSummary(long id) {
        return this.jdbc.sql(SUMMARY + " WHERE i.id = :id").param("id", id).query(InvoiceSummary.class).optional();
    }

    public Optional<String> findDocument(long id) {
        return this.jdbc.sql("SELECT document::text FROM invoice WHERE id = :id")
                .param("id", id).query(String.class).optional();
    }

    public Optional<byte[]> findPdf(long id) {
        return this.jdbc.sql("SELECT pdf FROM invoice WHERE id = :id").param("id", id).query(byte[].class).optional();
    }

    public Optional<String> findUbl(long id) {
        return this.jdbc.sql("SELECT ubl_xml FROM invoice WHERE id = :id AND ubl_xml IS NOT NULL")
                .param("id", id).query(String.class).optional();
    }

    public Optional<LocalDate> latestIssueDate(int year) {
        List<LocalDate> max = this.jdbc.sql(
                        "SELECT max(issue_date) FROM invoice WHERE extract(year FROM issue_date) = :year")
                .param("year", year)
                .query(LocalDate.class)
                .list();
        return max.isEmpty() ? Optional.empty() : Optional.ofNullable(max.get(0));
    }

    public int markPaid(long id, LocalDate paidOn) {
        return this.jdbc.sql("UPDATE invoice SET paid_on = :paidOn WHERE id = :id")
                .param("paidOn", paidOn)
                .param("id", id)
                .update();
    }
}
