package com.fakturacnysoftver.web.organization;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class OrganizationRepository {
    private final JdbcClient jdbc;

    public OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Organization> find() {
        return this.jdbc.sql("""
                        SELECT name, street, city, postal_code, country, ico, dic, ic_dph, email, phone,
                               iban, bic, registration_note, invoice_pattern, due_days
                        FROM organization WHERE id = 1""")
                .query(Organization.class)
                .optional();
    }

    public void save(Organization o) {
        this.jdbc.sql("""
                        INSERT INTO organization (id, name, street, city, postal_code, country, ico, dic, ic_dph,
                                                  email, phone, iban, bic, registration_note, invoice_pattern, due_days)
                        VALUES (1, :name, :street, :city, :postalCode, :country, :ico, :dic, :icDph,
                                :email, :phone, :iban, :bic, :registrationNote, :invoicePattern, :dueDays)
                        ON CONFLICT (id) DO UPDATE SET
                            name = excluded.name, street = excluded.street, city = excluded.city,
                            postal_code = excluded.postal_code, country = excluded.country, ico = excluded.ico,
                            dic = excluded.dic, ic_dph = excluded.ic_dph, email = excluded.email,
                            phone = excluded.phone, iban = excluded.iban, bic = excluded.bic,
                            registration_note = excluded.registration_note,
                            invoice_pattern = excluded.invoice_pattern, due_days = excluded.due_days,
                            updated_at = now()""")
                .paramSource(o)
                .update();
    }
}
