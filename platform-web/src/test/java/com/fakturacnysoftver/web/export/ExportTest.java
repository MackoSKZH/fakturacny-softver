package com.fakturacnysoftver.web.export;

import com.fakturacnysoftver.web.IntegrationTest;
import com.fakturacnysoftver.web.TestData;
import com.fakturacnysoftver.web.customer.CustomerRepository;
import com.fakturacnysoftver.web.invoice.InvoiceService;
import com.fakturacnysoftver.web.organization.OrganizationRepository;
import com.fakturacnysoftver.web.project.ProjectRepository;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExportTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    ExportCatalog catalog;
    @Autowired
    InvoiceService invoices;
    @Autowired
    OrganizationRepository organizations;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProjectRepository projects;

    private static Map<String, String> unzip(byte[] bytes) throws Exception {
        Map<String, String> files = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                files.put(e.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    void xlsxHasRealNumbersDatesAndTextThatIsNeverAFormula() throws Exception {
        Table t = new Table("Položky: január/2027", "Dátum", "Popis", "Suma", "Počet", "Tagy")
                .subtitle("filter").note("Spolu 12,50 €");
        t.row(LocalDate.of(2027, 1, 15), "=HYPERLINK(\"http://zle\") & <b>", new BigDecimal("12.50"), 3,
                List.of("a", "b"));
        t.row(LocalDateTime.of(2027, 1, 15, 12, 0), "riadok\u0001s riadiacim znakom", null, null, null);

        Map<String, String> files = unzip(t.bytes(Table.Format.XLSX));
        assertEquals(6, files.size());
        String sheet = files.get("xl/worksheets/sheet1.xml");
        assertTrue(sheet.contains("<c r=\"A5\" s=\"2\"><v>46402</v></c>"), "15.1.2027 ako dátum Excelu: " + sheet);
        assertTrue(sheet.contains("<c r=\"C5\" s=\"4\"><v>12.50</v></c>"), "suma ako číslo");
        assertTrue(sheet.contains("<c r=\"D5\"><v>3</v></c>"));
        assertTrue(sheet.contains("t=\"inlineStr\"><is><t xml:space=\"preserve\">=HYPERLINK(&quot;http://zle&quot;) &amp; &lt;b&gt;</t>"),
                "text, nie vzorec, a escapovaný");
        assertTrue(sheet.contains("<v>46402.5</v>"), "dátum a čas");
        assertTrue(sheet.contains("riadoks riadiacim"), "nepovolený XML znak vynechaný");
        assertTrue(sheet.contains("<autoFilter ref=\"A4:E6\"/>"), "filter len nad dátami, nie nad poznámkou");
        assertTrue(files.get("xl/workbook.xml").contains("name=\"Položky  január 2027\""), "názov hárku bez : a /");
        assertEquals("AB", XlsxWriter.column(27));
    }

    @Test
    void pdfRendersSlovakTextAcrossPagesWithPageNumbers() throws Exception {
        Table t = new Table("Harmonogram - Národné kolo", "Deň", "Od", "Do", "Aktivita", "Typ", "Bod programu",
                "Miesto", "Zodpovedá", "Pre", "Ľudia", "Poznámka");
        for (int i = 0; i < 120; i++) {
            t.row(LocalDate.of(2027, 4, 24), "14:00", "19:00", "FGS-2027", "Program", "Zápas č. " + i + " ťažký žreb",
                    "STU Bratislava", "Marek Technik", "Hodnotiteľ NTE", "Peter Horváth - obsadené 1/1",
                    "Dlhá poznámka ".repeat(i % 7) + "😀");
        }
        t.note("Podpis: ____");
        byte[] pdf = t.bytes(Table.Format.PDF);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertTrue(doc.getNumberOfPages() > 1);
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("ťažký žreb"), "diakritika");
            assertTrue(text.contains("24.4.2027 14:00 19:00 FGS-2027 Program"), "krátke hodnoty sa nelámu: " + text.substring(0, 400));
            assertTrue(text.contains("strana 1 / " + doc.getNumberOfPages()));
            assertTrue(text.contains("Podpis: ____"));
        }
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void everyCatalogExportBuildsAndZipContainsAll() throws Exception {
        TestData.Ids ids = TestData.setUp(this.organizations, this.customers, this.projects);
        this.invoices.issue(TestData.advertisingDraft(ids), "pokladnik");

        List<String> failures = new ArrayList<>();
        for (ExportCatalog.Export e : this.catalog.all()) {
            try {
                Table t = this.catalog.build(e);
                if (e.key().equals("faktury")) {
                    assertEquals(1, t.rows().size());
                    assertTrue(t.rows().get(0).get(2) instanceof LocalDate, "dátum ostáva dátumom");
                }
                for (Table.Format f : Table.Format.values()) {
                    t.bytes(f);
                }
            } catch (RuntimeException ex) {
                failures.add(e.key() + ": " + ex.getMessage());
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());

        byte[] zip = this.mvc.perform(get("/exporty/vsetko.zip")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertEquals(this.catalog.all().size(), unzip(zip).size());
        this.mvc.perform(get("/exporty/faktury.pdf")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        this.mvc.perform(get("/exporty/neexistuje.xlsx")).andExpect(status().isNotFound());
        this.mvc.perform(get("/api/polozky/export.xlsx").param("projekt", String.valueOf(ids.projectId())))
                .andExpect(status().isOk());
        this.mvc.perform(get("/exporty")).andExpect(status().isOk());
        assertEquals(2, this.jdbc.sql("SELECT count(*) FROM audit_log WHERE action = 'EXPORT'").query(Long.class).single(),
                "hromadné exporty sa auditujú");
    }

    @Test
    @WithMockUser(value = "clen", roles = "USER")
    void bulkExportsAreEditorOnly() throws Exception {
        grant("clen", "VEDENIE");
        this.mvc.perform(get("/exporty")).andExpect(status().isForbidden());
        this.mvc.perform(get("/exporty/ludia.xlsx")).andExpect(status().isForbidden());
        this.mvc.perform(get("/exporty/vsetko.zip")).andExpect(status().isForbidden());
        this.mvc.perform(get("/api/polozky/export.xlsx")).andExpect(status().isOk());
    }
}
