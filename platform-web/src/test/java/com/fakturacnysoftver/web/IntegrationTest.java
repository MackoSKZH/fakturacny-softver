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
        this.jdbc.sql("TRUNCATE invite_use, invite, activity_owner, user_role, app_user, attachment, asset_loan, asset, deal_deliverable, partner_note, deal, partner, timesheet_close, work_log, work_contract, agenda_item, person, ledger_entry, ledger_history, invoice, invoice_series, customer, project, organization, audit_log "
                + "RESTART IDENTITY CASCADE").update();
    }

    /** Zalozi pouzivatela s rolami (kody z app_role), napr. grant("clen", "MENTOR"). */
    protected void grant(String identity, String... roleCodes) {
        long id = this.jdbc.sql("INSERT INTO app_user (email, created_by) VALUES (lower(:e), 'test') "
                        + "ON CONFLICT (email) DO UPDATE SET active = true RETURNING id")
                .param("e", identity).query(Long.class).single();
        for (String code : roleCodes) {
            this.jdbc.sql("INSERT INTO user_role (user_id, role_id) SELECT :u, id FROM app_role WHERE code = :c "
                    + "ON CONFLICT DO NOTHING").param("u", id).param("c", code).update();
        }
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
