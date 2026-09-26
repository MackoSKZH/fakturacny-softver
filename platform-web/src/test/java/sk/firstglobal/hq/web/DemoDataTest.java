package sk.firstglobal.hq.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Ukazkove data sa daju prejst cele bez chyby a lokalny citatel sa prihlasi so svojim programom. */
@TestPropertySource(properties = "app.security.local-readers=eva@demo.example, pokladnik")
class DemoDataTest extends IntegrationTest {
    @Autowired
    DemoData demo;
    @Autowired
    MockMvc mvc;

    @Test
    void seedsOnceAndEveryPageRenders() throws Exception {
        assertTrue(this.demo.seed());
        assertFalse(this.demo.seed(), "existujúce údaje nikdy neprepíše");

        var editor = user("pokladnik").roles("USER", "EDITOR");
        long round = this.jdbc.sql("SELECT id FROM project WHERE code = 'NK-DEMO'").query(Long.class).single();
        long deal = this.jdbc.sql("SELECT id FROM deal WHERE kind = 'GRANT'").query(Long.class).single();
        for (String url : List.of("/aktivity", "/aktivity/" + round, "/aktivity/" + round + "/harmonogram", "/ludia",
                "/partneri", "/financovanie", "/financovanie/" + deal, "/majetok", "/dochadzka", "/polozky", "/exporty",
                "/faktury")) {
            this.mvc.perform(get(url).with(editor)).andExpect(status().isOk());
        }
        this.mvc.perform(get("/financovanie/" + deal).with(editor))
                .andExpect(content().string(containsString("mimo oprávneného obdobia")));
        this.mvc.perform(get("/exporty/vsetko.zip").with(editor)).andExpect(status().isOk());
        this.mvc.perform(get("/podpora/NK-DEMO").with(anonymous())).andExpect(status().isOk())
                .andExpect(content().string(containsString("50 € z 2 000 €")));
        assertEquals(2, this.jdbc.sql("SELECT count(*) FROM assignment WHERE status = 'ZUCASTNIL_SA'")
                .query(Long.class).single(), "minulá akcia má potvrdenia");
    }

    @Test
    void localReaderLogsInAndSeesOwnProgramButCannotEdit() throws Exception {
        this.demo.seed();
        this.mvc.perform(formLogin().user("eva@demo.example").password(PASSWORD))
                .andExpect(authenticated().withUsername("eva@demo.example").withRoles("USER"));
        var eva = user("eva@demo.example").roles("USER");
        this.mvc.perform(get("/moj-program").with(eva)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Školenie rozhodcov k pravidlám")))
                .andExpect(content().string(containsString("Mám požičané")));
        this.mvc.perform(get("/ludia").with(eva)).andExpect(status().isForbidden());
        this.mvc.perform(formLogin().user("pokladnik").password(PASSWORD))
                .andExpect(authenticated().withUsername("pokladnik"));
    }
}
