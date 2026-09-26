package com.fakturacnysoftver.web;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Skutocny PostgreSQL pre testy. Standardne embedded (bez Dockeru); ak je nastavena premenna
 * TEST_DATABASE_URL, pouzije existujucu databazu a pred testami vymaze jej schemu public.
 */
final class TestDatabase {
    private static String url;
    private static String user;
    private static String password;
    private static EmbeddedPostgres embedded;

    private TestDatabase() {
    }

    static synchronized void start() {
        if (url != null) {
            return;
        }
        String external = System.getenv("TEST_DATABASE_URL");
        if (external != null && !external.isBlank()) {
            url = external;
            user = env("TEST_DATABASE_USER", "ucto");
            password = env("TEST_DATABASE_PASSWORD", "ucto");
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS public CASCADE");
                s.execute("CREATE SCHEMA public");
            } catch (SQLException e) {
                throw new IllegalStateException("Testovacia DB nie je dostupná: " + url, e);
            }
            return;
        }
        try {
            embedded = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new UncheckedIOException("Embedded PostgreSQL sa nespustil (beží test ako root?)", e);
        }
        url = embedded.getJdbcUrl("postgres", "postgres");
        user = "postgres";
        password = "postgres";
    }

    static String url() {
        return url;
    }

    static String user() {
        return user;
    }

    static String password() {
        return password;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }
}
