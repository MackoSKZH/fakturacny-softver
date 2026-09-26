package com.fakturacnysoftver.web;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTest.FixedClock.class)
@TestPropertySource(properties = {
        "app.security.mode=local",
        "app.security.local-username=pokladnik",
        "app.security.local-password=" + IntegrationTest.PASSWORD,
        "server.servlet.session.cookie.secure=false"
})
public abstract class IntegrationTest {
    public static final String PASSWORD = "dlhe-testovacie-heslo";
    /** Vsetky testy bezia 15. 1. 2027 - prvy mesiac povinnej e-fakturacie. */
    public static final Instant NOW = Instant.parse("2027-01-15T09:00:00Z");

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.start();
        registry.add("spring.datasource.url", TestDatabase::url);
        registry.add("spring.datasource.username", TestDatabase::user);
        registry.add("spring.datasource.password", TestDatabase::password);
    }

    @BeforeEach
    void cleanDatabase() {
        this.jdbc.sql("TRUNCATE invoice, invoice_series, customer, project, organization, audit_log "
                + "RESTART IDENTITY CASCADE").update();
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneId.of("Europe/Bratislava"));
        }
    }
}
