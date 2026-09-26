package com.fakturacnysoftver.web.invoice;

import com.fakturacnysoftver.core.InvoiceNumbering;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prideluje poradove cisla bez medzier a duplicit. UPDATE ... RETURNING drzi zamok na riadku
 * radu az do konca transakcie vystavenia - subezne vystavenia cakaju v rade a pri rollbacku
 * sa cislo vrati.
 */
@Component
class InvoiceNumberAllocator {
    private final JdbcClient jdbc;

    InvoiceNumberAllocator(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    String next(String pattern, int year) {
        this.jdbc.sql("INSERT INTO invoice_series (pattern, year) VALUES (:p, :y) ON CONFLICT DO NOTHING")
                .param("p", pattern)
                .param("y", year)
                .update();
        long seq = this.jdbc.sql("""
                        UPDATE invoice_series SET last_number = last_number + 1
                        WHERE pattern = :p AND year = :y
                        RETURNING last_number""")
                .param("p", pattern)
                .param("y", year)
                .query(Long.class)
                .single();
        return InvoiceNumbering.format(pattern, year, seq);
    }
}
