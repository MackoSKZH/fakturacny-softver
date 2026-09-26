package com.fakturacnysoftver.web.customer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CustomerRepository {
    private static final String COLUMNS =
            "id, name, street, city, postal_code, country, ico, dic, ic_dph, email, phone";

    private final JdbcClient jdbc;

    public CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Customer> findAll() {
        return this.jdbc.sql("SELECT " + COLUMNS + " FROM customer ORDER BY lower(name)")
                .query(Customer.class)
                .list();
    }

    public Optional<Customer> findById(long id) {
        return this.jdbc.sql("SELECT " + COLUMNS + " FROM customer WHERE id = :id")
                .param("id", id)
                .query(Customer.class)
                .optional();
    }

    public long insert(Customer c) {
        return this.jdbc.sql("""
                        INSERT INTO customer (name, street, city, postal_code, country, ico, dic, ic_dph, email, phone)
                        VALUES (:name, :street, :city, :postalCode, :country, :ico, :dic, :icDph, :email, :phone)
                        RETURNING id""")
                .paramSource(c)
                .query(Long.class)
                .single();
    }
}
