package com.fakturacnysoftver.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebFlowTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;

    @Test
    void anonymousUserIsSentToLogin() throws Exception {
        this.mvc.perform(get("/faktury")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        this.mvc.perform(get("/login")).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"password\"")));
        this.mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void localLoginRequiresCorrectPassword() throws Exception {
        this.mvc.perform(formLogin().user("pokladnik").password("zle-heslo")).andExpect(unauthenticated());
        this.mvc.perform(formLogin().user("pokladnik").password(PASSWORD)).andExpect(authenticated());
    }

    @Test
    @WithMockUser(value = "clen", roles = "USER")
    void readOnlyMemberCanBrowseButNotChangeAnything() throws Exception {
        this.mvc.perform(get("/faktury")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Nová faktúra"))));
        this.mvc.perform(get("/projekty")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("Vytvoriť projekt"))));
        this.mvc.perform(get("/faktury/nova")).andExpect(status().isForbidden());
        this.mvc.perform(post("/projekty").with(csrf()).param("code", "X1").param("name", "X"))
                .andExpect(status().isForbidden());
        this.mvc.perform(post("/nastavenia").with(csrf()).param("name", "Hack")).andExpect(status().isForbidden());
        this.mvc.perform(post("/faktury/1/uhrada").with(csrf()).param("paidOn", "2027-01-01"))
                .andExpect(status().isForbidden());
        assertTrue(this.jdbc.sql("SELECT count(*) FROM project").query(Long.class).single() == 0);
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void postWithoutCsrfTokenIsRejected() throws Exception {
        this.mvc.perform(post("/projekty").param("code", "X1").param("name", "X"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void fullInvoicingFlow() throws Exception {
        this.mvc.perform(get("/faktury/nova")).andExpect(status().isOk())
                .andExpect(content().string(containsString("údaje organizácie")));

        this.mvc.perform(post("/nastavenia").with(csrf())
                        .param("name", "Robotické združenie, o. z.").param("street", "Hlavná 1")
                        .param("city", "Bratislava").param("postalCode", "81101").param("country", "SK")
                        .param("ico", "12345678").param("dic", "2120000000").param("icDph", "")
                        .param("iban", "SK31 1200 0000 1987 4263 7541").param("bic", "TATRSKBX")
                        .param("invoicePattern", "{YYYY}{NNNN}").param("dueDays", "14"))
                .andExpect(redirectedUrl("/nastavenia"));
        this.mvc.perform(post("/odberatelia").with(csrf())
                        .param("name", "Sponzor <script>alert(1)</script> s.r.o.").param("city", "Košice")
                        .param("country", "SK").param("ico", "87654321").param("dic", "2020000000")
                        .param("icDph", "SK2020000000"))
                .andExpect(redirectedUrl("/odberatelia"));
        this.mvc.perform(post("/projekty").with(csrf())
                        .param("code", "fgc-2027").param("name", "FIRST Global Challenge 2027").param("budget", "12 000"))
                .andExpect(redirectedUrl("/projekty"));

        this.mvc.perform(get("/odberatelia")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>alert(1)"))))
                .andExpect(content().string(containsString("0245:2020000000")));

        long customerId = this.jdbc.sql("SELECT id FROM customer").query(Long.class).single();
        long projectId = this.jdbc.sql("SELECT id FROM project").query(Long.class).single();

        MvcResult issued = this.mvc.perform(post("/faktury").with(csrf())
                        .param("customerId", String.valueOf(customerId))
                        .param("projectId", String.valueOf(projectId))
                        .param("lines[0].description", "Charitatívna reklama")
                        .param("lines[0].quantity", "1")
                        .param("lines[0].unit", "C62")
                        .param("lines[0].unitPrice", "1 500,50")
                        .param("lines[1].description", "")
                        .param("lines[1].unitPrice", ""))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String detailUrl = issued.getResponse().getRedirectedUrl();
        assertTrue(detailUrl.matches("/faktury/\\d+"), detailUrl);

        this.mvc.perform(get(detailUrl)).andExpect(status().isOk())
                .andExpect(content().string(containsString("20270001")))
                .andExpect(content().string(containsString("1 500,50 €")));
        this.mvc.perform(get(detailUrl + "/pdf")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", containsString("faktura-20270001.pdf")));
        this.mvc.perform(get(detailUrl + "/xml")).andExpect(status().isOk())
                .andExpect(content().string(containsString("urn:fdc:peppol.eu:2017:poacc:billing:3.0")));

        this.mvc.perform(post(detailUrl + "/uhrada").with(csrf()).param("paidOn", "2027-01-15"))
                .andExpect(redirectedUrl(detailUrl));
        this.mvc.perform(get("/faktury")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Uhradená")));
        this.mvc.perform(get("/projekty")).andExpect(status().isOk())
                .andExpect(content().string(containsString("FGC-2027")))
                .andExpect(content().string(containsString("1 500,50 €")));
        this.mvc.perform(get("/nastavenia")).andExpect(status().isOk())
                .andExpect(content().string(containsString("VYSTAVENIE")));

        this.mvc.perform(get(detailUrl + "/dobropis")).andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"Charitatívna reklama\"")))
                .andExpect(content().string(containsString("1 500,50 €")));
        this.mvc.perform(post(detailUrl + "/dobropis").with(csrf())
                        .param("lines[0].description", "Charitatívna reklama").param("lines[0].quantity", "1")
                        .param("lines[0].unit", "C62").param("lines[0].unitPrice", "500"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Uveďte dôvod opravy.")));
        MvcResult credit = this.mvc.perform(post(detailUrl + "/dobropis").with(csrf())
                        .param("reason", "Polovica reklamy sa nekonala")
                        .param("lines[0].description", "Charitatívna reklama").param("lines[0].quantity", "1")
                        .param("lines[0].unit", "C62").param("lines[0].unitPrice", "500"))
                .andExpect(status().is3xxRedirection()).andReturn();
        this.mvc.perform(get(credit.getResponse().getRedirectedUrl())).andExpect(status().isOk())
                .andExpect(content().string(containsString("D20270001")))
                .andExpect(content().string(containsString("Polovica reklamy sa nekonala")));
        this.mvc.perform(get("/faktury")).andExpect(content().string(containsString("-500,00 €")));
        this.mvc.perform(get("/projekty")).andExpect(content().string(containsString("1 000,50 €")));
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void invalidInvoiceShowsErrorsAndKeepsInput() throws Exception {
        this.mvc.perform(post("/faktury").with(csrf())
                        .param("lines[0].description", "Reklama")
                        .param("lines[0].quantity", "1")
                        .param("lines[0].unitPrice", "abc"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Vyberte odberateľa.")))
                .andExpect(content().string(containsString("Položka 1: neplatná cena.")))
                .andExpect(content().string(containsString("value=\"Reklama\"")));
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void settingsRejectInvalidIbanAndPattern() throws Exception {
        this.mvc.perform(post("/nastavenia").with(csrf())
                        .param("name", "OZ").param("city", "Bratislava").param("iban", "SK0000000000000000000000")
                        .param("invoicePattern", "{YYYY}").param("dueDays", "14"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("IBAN nie je platný.")))
                .andExpect(content().string(containsString("Vzor čísla faktúry")));
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void unknownInvoiceIs404AndPagesSendSecurityHeaders() throws Exception {
        this.mvc.perform(get("/faktury/999")).andExpect(status().isNotFound());
        this.mvc.perform(get("/faktury"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
