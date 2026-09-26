package com.fakturacnysoftver.web.asset;

import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.people.Person;
import com.fakturacnysoftver.web.people.PersonRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AssetTest extends IntegrationTest {
    @Autowired
    AssetService service;
    @Autowired
    AssetRepository repo;
    @Autowired
    PersonRepository people;
    @Autowired
    MockMvc mvc;

    long student;

    @BeforeEach
    void setUp() {
        this.student = this.people.insert(new Person(null, "Tomáš Študent", "tomas@fgs.example", null, null,
                List.of("STUDENT"), false, null, null, LocalDate.of(2027, 1, 1), false, false, null));
    }

    private AssetService.AssetInput input(String no, String name, String price, String keepUntil) {
        return new AssetService.AssetInput(no, name, "ROBOTIKA", "SN-1", "2027-01-10", price, null, null, keepUntil,
                "dielňa", null);
    }

    @Test
    void numbersInventoryAutomaticallyAndRejectsDuplicates() {
        long a = this.service.create(input(null, "REV Control Hub", "250", null), "pokladnik");
        long b = this.service.create(input(" ", "REV Expansion Hub", "250", null), "pokladnik");
        assertEquals("FGS-2027-001", this.service.asset(a).inventoryNo());
        assertEquals("FGS-2027-002", this.service.asset(b).inventoryNo());

        AssetException e = assertThrows(AssetException.class, () -> this.service.create(new AssetService.AssetInput(
                "fgs-2027-001", " ", "NIECO", null, "2027-02-01", "-5", null, null, "31.2.2030", null, null), "x"));
        assertTrue(e.errors().contains("Inventárne číslo fgs-2027-001 už existuje."), e.errors().toString());
        assertTrue(e.errors().contains("Neznáma kategória."));
        assertTrue(e.errors().contains("Dátum kúpy nemôže byť v budúcnosti."));
        assertTrue(e.errors().stream().anyMatch(x -> x.startsWith("Cena musí byť kladná")));
        assertTrue(e.errors().stream().anyMatch(x -> x.startsWith("Udržať do")));
    }

    @Test
    void lendReturnAndRetireFollowRules() {
        long hub = this.service.create(input(null, "REV Control Hub", "2500", "2030-12-31"), "pokladnik");
        assertTrue(this.service.asset(hub).warnings(this.service.today()).stream()
                .anyMatch(w -> w.startsWith("Cena nad 1 700 €")));

        this.service.lend(hub, this.student, null, "2027-01-20", "s nabíjačkou", "pokladnik");
        Asset lent = this.service.asset(hub);
        assertTrue(lent.isLent());
        assertEquals("Požičané", lent.statusLabel());
        assertEquals(List.of(hub), this.repo.lentTo(this.student).stream().map(Asset::id).toList());
        AssetException twice = assertThrows(AssetException.class,
                () -> this.service.lend(hub, this.student, null, null, null, "x"));
        assertTrue(twice.errors().get(0).startsWith("Už je požičané (Tomáš Študent)"));
        assertThrows(AssetException.class, () -> this.service.retire(hub, null, "pokazené", "x"), "požičané sa nevyraďuje");
        assertThrows(AssetException.class, () -> this.service.giveBack(hub, "2027-01-01", "x"), "vrátené pred požičaním");

        this.service.giveBack(hub, null, "pokladnik");
        assertFalse(this.service.asset(hub).isLent());
        assertEquals(LocalDate.of(2027, 1, 15), this.repo.loans(hub).get(0).returnedOn());

        AssetException keep = assertThrows(AssetException.class, () -> this.service.retire(hub, null, "pokazené", "x"));
        assertTrue(keep.errors().get(0).contains("udržať do 31. 12. 2030"), keep.errors().toString());

        long laptop = this.service.create(input(null, "Notebook", "600", null), "pokladnik");
        this.service.retire(laptop, null, "pokazená doska", "pokladnik");
        assertTrue(this.service.asset(laptop).isRetired());
        assertThrows(AssetException.class, () -> this.service.lend(laptop, this.student, null, null, null, "x"));
    }

    @Test
    void overdueLoanShowsWarning() {
        long kit = this.service.create(input(null, "Sada FGC 2026", "900", null), "pokladnik");
        this.jdbc.sql("INSERT INTO asset_loan (asset_id, person_id, lent_on, due_on, lent_by) VALUES (:a, :p, :l, :d, 'x')")
                .param("a", kit).param("p", this.student).param("l", LocalDate.of(2026, 12, 1))
                .param("d", LocalDate.of(2027, 1, 10)).update();
        Asset a = this.service.asset(kit);
        assertTrue(a.isLoanOverdue(this.service.today()));
        assertEquals("Malo sa vrátiť 10. 1. 2027 (Tomáš Študent).", a.warnings(this.service.today()).get(0));
        assertTrue(a.warnings(this.service.today()).contains("Chýba prepojenie na doklad o kúpe (položku)."));
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void editorManagesAssetsThroughPages() throws Exception {
        String url = this.mvc.perform(post("/majetok").with(csrf()).param("name", "Akumulátorová vŕtačka")
                        .param("category", "NARADIE").param("price", "129,90"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        this.mvc.perform(post(url + "/pozicat").with(csrf()).param("personId", String.valueOf(this.student))
                .param("dueOn", "2027-01-31")).andExpect(status().is3xxRedirection());
        this.mvc.perform(post(url + "/pozicat").with(csrf()).param("personId", String.valueOf(this.student)))
                .andExpect(flash().attributeExists("errors"));
        this.mvc.perform(get("/majetok").param("stav", "pozicane")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Akumulátorová vŕtačka")))
                .andExpect(content().string(containsString("Tomáš Študent")));
        this.mvc.perform(get("/ludia/" + this.student)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Má požičané")));
        this.mvc.perform(get("/majetok/99999")).andExpect(status().isNotFound());
        this.mvc.perform(get("/exporty/majetok.xlsx")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(value = "tomas@fgs.example", roles = "USER")
    void memberSeesRegisterAndOwnLoansButCannotChange() throws Exception {
        long hub = this.service.create(input(null, "REV Control Hub", "250", null), "pokladnik");
        this.service.lend(hub, this.student, null, null, null, "pokladnik");
        this.mvc.perform(get("/majetok")).andExpect(status().isOk());
        this.mvc.perform(get("/majetok/" + hub)).andExpect(status().isOk());
        this.mvc.perform(get("/moj-program")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Mám požičané")))
                .andExpect(content().string(containsString("REV Control Hub")));
        this.mvc.perform(post("/majetok/" + hub + "/vratit").with(csrf())).andExpect(status().isForbidden());
    }
}
