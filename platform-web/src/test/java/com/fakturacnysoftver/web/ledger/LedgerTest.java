package com.fakturacnysoftver.web.ledger;

import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.TestData;
import com.fakturacnysoftver.web.customer.CustomerRepository;
import com.fakturacnysoftver.web.invoice.InvoiceService;
import com.fakturacnysoftver.web.organization.OrganizationRepository;
import com.fakturacnysoftver.web.project.Project;
import com.fakturacnysoftver.web.project.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LedgerTest extends IntegrationTest {
    @Autowired
    LedgerService service;
    @Autowired
    LedgerRepository ledger;
    @Autowired
    InvoiceService invoices;
    @Autowired
    OrganizationRepository organizations;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProjectRepository projects;
    @Autowired
    MockMvc mvc;

    TestData.Ids ids;

    @BeforeEach
    void setUp() {
        this.ids = TestData.setUp(this.organizations, this.customers, this.projects);
    }

    private LedgerInput expense(String date, String amount, List<String> tags) {
        return new LedgerInput(date, "Hliníkové profily", "VYDAVOK", amount, this.ids.projectId(), tags,
                "Materiál", "Alza.sk", "FA-778", "BANKA", null, null);
    }

    @Test
    void createsEntryWithNormalizedTagsAndHistory() {
        LedgerEntry e = this.service.create(expense("15.1.2027", "1 245,90", List.of(" hardvér", "Robot", "robot", "")),
                "pokladnik");

        assertEquals(LocalDate.of(2027, 1, 15), e.entryDate());
        assertEquals(new BigDecimal("1245.90"), e.amount());
        assertEquals(List.of("hardvér", "Robot"), e.tags(), "bez medzier a bez duplicít");
        assertEquals("FGC-2027", e.projectCode());
        List<LedgerRepository.HistoryItem> h = this.ledger.history(e.id());
        assertEquals(1, h.size());
        assertEquals("pokladnik", h.get(0).actor());
        assertEquals("INSERT", h.get(0).op());
    }

    @Test
    void rejectsInvalidInputWithReadableErrors() {
        LedgerException e = assertThrows(LedgerException.class, () -> this.service.create(
                new LedgerInput("31.2.2027", " ", "NIECO", "-5", null, null, null, null, null, "KARTA", null, null),
                "pokladnik"));

        assertTrue(e.errors().contains("Dátum „31.2.2027“ nie je platný (napr. 15.1.2027)."), e.errors().toString());
        assertTrue(e.errors().contains("Popis je povinný."));
        assertTrue(e.errors().contains("Typ musí byť príjem alebo výdavok."));
        assertTrue(e.errors().stream().anyMatch(m -> m.startsWith("Suma musí byť kladná")));
        assertTrue(e.errors().contains("Úhrada musí byť banka alebo pokladňa."));
        assertThrows(LedgerException.class, () -> this.service.create(expense("2027-01-16", "10", null), "x"),
                "dátum v budúcnosti");
        assertThrows(LedgerException.class, () -> this.service.create(expense("2027-01-15", "10,005", null), "x"),
                "najviac 2 desatinné miesta");
    }

    @Test
    void staleVersionIsRejectedInsteadOfOverwritingSomeoneElsesChange() {
        LedgerEntry e = this.service.create(expense("2027-01-15", "100", null), "pokladnik");
        LedgerInput edit1 = new LedgerInput("2027-01-15", "Upravil pokladník", "VYDAVOK", "100", null, List.of(),
                null, null, null, "BANKA", null, e.version());
        LedgerInput edit2 = new LedgerInput("2027-01-15", "Upravil predseda", "VYDAVOK", "100", null, List.of(),
                null, null, null, "BANKA", null, e.version());

        this.service.update(e.id(), edit1, "pokladnik");
        LedgerException conflict = assertThrows(LedgerException.class, () -> this.service.update(e.id(), edit2, "predseda"));

        assertTrue(conflict.isConflict());
        assertEquals("Upravil pokladník", this.ledger.findById(e.id()).orElseThrow().description());
        assertEquals(2, this.ledger.history(e.id()).size());
    }

    @Test
    void importIsAllOrNothing() {
        List<LedgerInput> rows = List.of(expense("2027-01-10", "10", null), expense("2027-01-11", "abc", null),
                expense("2027-01-12", "30", null));

        LedgerException e = assertThrows(LedgerException.class, () -> this.service.importRows(rows, "pokladnik"));
        assertTrue(e.errors().get(0).startsWith("Riadok 2:"), e.errors().toString());
        assertTrue(this.ledger.findAll().isEmpty(), "chybný import nesmie zapísať ani časť");

        assertEquals(2, this.service.importRows(List.of(rows.get(0), rows.get(2)), "pokladnik").size());
    }

    @Test
    void bulkAddsTagsAndAssignsProject() {
        LedgerEntry a = this.service.create(expense("2027-01-10", "10", List.of("robot")), "p");
        LedgerEntry b = this.service.create(expense("2027-01-11", "20", null), "p");

        this.service.bulk(List.of(a.id(), b.id()), "addTag", "Cestovné", "p");
        this.service.bulk(List.of(a.id(), b.id()), "project", "", "p");
        this.service.bulk(List.of(a.id()), "removeTag", "ROBOT", "p");

        LedgerEntry a2 = this.ledger.findById(a.id()).orElseThrow();
        assertEquals(List.of("Cestovné"), a2.tags());
        assertEquals(null, a2.projectId());
        assertEquals(List.of("Cestovné"), this.ledger.findById(b.id()).orElseThrow().tags());

        int before = this.ledger.history(b.id()).size();
        this.service.bulk(List.of(b.id()), "project", "", "p");
        assertEquals(before, this.ledger.history(b.id()).size(), "úprava bez zmeny nepíše do histórie");
    }

    @Test
    void invoicePaymentBecomesLockedIncomeEntryAndFeedsProjectBudget() {
        long invoiceId = this.invoices.issue(TestData.advertisingDraft(this.ids), "pokladnik");
        this.service.create(expense("2027-01-12", "300", null), "pokladnik");

        this.invoices.markPaid(invoiceId, LocalDate.of(2027, 1, 14), "pokladnik");

        LedgerEntry income = this.ledger.findByInvoice(invoiceId).orElseThrow();
        assertEquals("PRIJEM", income.direction());
        assertEquals(new BigDecimal("1500.00"), income.amount());
        assertEquals("20270001", income.invoiceNumber());
        Project p = this.projects.findAll().get(0);
        assertEquals(new BigDecimal("300.00"), p.spent());
        assertEquals(new BigDecimal("1500.00"), p.income());
        assertEquals(new BigDecimal("11700.00"), p.remaining());

        LedgerException locked = assertThrows(LedgerException.class, () -> this.service.update(income.id(),
                new LedgerInput("2027-01-14", income.description(), "PRIJEM", "1", null, List.of(), null, null, null,
                        "BANKA", null, income.version()), "pokladnik"));
        assertTrue(locked.errors().get(0).contains("zmeňte ju na faktúre"));
        LedgerEntry tagged = this.service.update(income.id(), new LedgerInput("2027-01-14", income.description(),
                "PRIJEM", "1500", income.projectId(), List.of("sponzor"), "Príjmy z reklamy", null, "20270001", "BANKA",
                null, income.version()), "pokladnik");
        assertEquals(List.of("sponzor"), tagged.tags(), "tagy a kategória sa meniť dajú");
        assertThrows(LedgerException.class, () -> this.service.delete(income.id(), tagged.version(), "pokladnik"));

        this.invoices.markPaid(invoiceId, null, "pokladnik");
        assertTrue(this.ledger.findByInvoice(invoiceId).isEmpty(), "zrušená úhrada odstráni príjem");
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void apiReturnsConflictAndValidationErrorsAsJson() throws Exception {
        LedgerEntry e = this.service.create(expense("2027-01-15", "100", null), "pokladnik");
        String stale = """
                {"entryDate":"2027-01-15","description":"X","direction":"VYDAVOK","amount":"100",
                 "tags":[],"paymentMethod":"BANKA","version":%d}""".formatted(e.version() + 5);

        this.mvc.perform(put("/api/polozky/" + e.id()).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0]", containsString("zmenil niekto iný")));
        this.mvc.perform(post("/api/polozky").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"entryDate\":\"2027-01-15\",\"description\":\"X\",\"direction\":\"VYDAVOK\",\"amount\":\"0\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]", containsString("Suma musí byť kladná")));
        this.mvc.perform(get("/api/polozky")).andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].amount").value(100.0))
                .andExpect(jsonPath("$.categories", hasItem("Materiál")));
        this.mvc.perform(get("/polozky")).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(value = "clen", roles = "USER")
    void readerCanSeeButNotWrite() throws Exception {
        grant("clen", "VEDENIE");
        this.mvc.perform(get("/api/polozky")).andExpect(status().isOk());
        this.mvc.perform(post("/api/polozky").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        this.mvc.perform(put("/api/polozky/1").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void csvExportIsExcelFriendlyAndNeutralizesFormulas() throws Exception {
        this.service.create(new LedgerInput("2027-01-15", "=HYPERLINK(\"http://zle\")", "VYDAVOK", "12,50", null,
                List.of("a"), null, "Firma; s bodkočiarkou", null, "BANKA", null, null), "pokladnik");

        byte[] csv = this.mvc.perform(get("/api/polozky/export.csv")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(csv, StandardCharsets.UTF_8);

        assertTrue(text.startsWith("﻿Dátum;Popis"), "BOM pre Excel");
        assertTrue(text.contains("\"'=HYPERLINK(\"\"http://zle\"\")\""), text);
        assertTrue(text.contains("\"Firma; s bodkočiarkou\""), text);
        assertTrue(text.contains(";12,50;"), text);
    }
}
