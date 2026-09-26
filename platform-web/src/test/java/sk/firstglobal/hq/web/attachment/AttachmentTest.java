package sk.firstglobal.hq.web.attachment;

import sk.firstglobal.hq.web.IntegrationTest;
import sk.firstglobal.hq.web.TestData;
import sk.firstglobal.hq.web.customer.CustomerRepository;
import sk.firstglobal.hq.web.organization.OrganizationRepository;
import sk.firstglobal.hq.web.partner.PartnerService;
import sk.firstglobal.hq.web.project.ProjectRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AttachmentTest extends IntegrationTest {
    @Autowired
    MockMvc mvc;
    @Autowired
    AttachmentService service;
    @Autowired
    AttachmentRepository repo;
    @Autowired
    PartnerService partners;
    @Autowired
    OrganizationRepository organizations;
    @Autowired
    CustomerRepository customers;
    @Autowired
    ProjectRepository projects;

    long project;
    static final byte[] PDF = "%PDF-1.4\n% skusobny harok\n".getBytes(StandardCharsets.US_ASCII);

    @BeforeEach
    void setUp() {
        this.project = TestData.setUp(this.organizations, this.customers, this.projects).projectId();
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    private long upload(String name, byte[] content, String category, boolean editorsOnly) {
        return this.service.upload(AttachmentRepository.Owner.PROJECT, this.project, file(name, content), category,
                editorsOnly, null, "pokladnik");
    }

    @Test
    void checksContentNotJustTheExtension() {
        long ok = upload("../../Hárok výzvy 2027.pdf", PDF, "VYZVA", false);
        Attachment a = this.repo.find(ok).orElseThrow();
        assertEquals("Hárok výzvy 2027.pdf", a.fileName(), "bez cesty");
        assertEquals("application/pdf", a.contentType());

        AttachmentException html = assertThrows(AttachmentException.class,
                () -> upload("utok.pdf", "<html><script>alert(1)</script>".getBytes(), "INE", false));
        assertTrue(html.getMessage().contains("nezodpovedá prípone .pdf"));
        assertThrows(AttachmentException.class, () -> upload("poznamky.txt", "<svg onload=x>".getBytes(), "INE", false),
                "text, ktorý vyzerá ako HTML/SVG");
        AttachmentException exe = assertThrows(AttachmentException.class, () -> upload("setup.exe", PDF, "INE", false));
        assertTrue(exe.getMessage().startsWith("Typ súboru .exe nie je povolený"));
        assertThrows(AttachmentException.class, () -> upload("kopia.pdf", PDF, "VYZVA", false), "rovnaký obsah dvakrát");
        assertThrows(AttachmentException.class, () -> upload("prazdny.pdf", new byte[0], "VYZVA", false));

        long contract = upload("zmluva.docx", new byte[]{'P', 'K', 3, 4, 0, 0}, "ZMLUVA", false);
        assertTrue(this.repo.find(contract).orElseThrow().editorsOnly(), "zmluva je vždy len pre editorov");
        assertEquals("pravidla.csv", AttachmentService.cleanName("C:\\Users\\jana\\pravidla.csv"));
    }

    @Test
    void membersDownloadOnlyWhatTheyMaySee() throws Exception {
        grant("clen", "MENTOR");
        long sheet = upload("harok.pdf", PDF, "VYZVA", false);
        long secret = upload("rozpocet.pdf", "%PDF-1.7 interny".getBytes(), "INE", true);
        long partner = this.partners.createPartner(new PartnerService.PartnerInput("Tech s.r.o.", "FIRMA", null, null,
                null, null, null, null, null, null, null), "x");
        long partnerFile = this.service.upload(AttachmentRepository.Owner.PARTNER, partner,
                file("ponuka.pdf", "%PDF-1.5 ponuka".getBytes()), "INE", false, null, "x");

        var member = user("clen").roles("USER");
        this.mvc.perform(get("/prilohy/" + sheet).with(member)).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        this.mvc.perform(get("/prilohy/" + secret).with(member)).andExpect(status().isForbidden());
        this.mvc.perform(get("/prilohy/" + partnerFile).with(member)).andExpect(status().isForbidden());
        this.mvc.perform(get("/aktivity/" + this.project).with(member)).andExpect(status().isOk())
                .andExpect(content().string(containsString("harok.pdf")))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("rozpocet.pdf"))));
        this.mvc.perform(post("/prilohy/" + sheet + "/zmazat").with(member).with(csrf())).andExpect(status().isForbidden());
        this.mvc.perform(get("/prilohy/" + secret).with(user("pokladnik").roles("USER", "EDITOR"))).andExpect(status().isOk());
    }

    @Test
    @WithMockUser(value = "pokladnik", roles = {"USER", "EDITOR"})
    void editorUploadsThroughPageAndSeesErrors() throws Exception {
        this.mvc.perform(multipart("/aktivity/" + this.project + "/prilohy").file(file("pravidla.pdf", PDF))
                        .param("category", "PRAVIDLA").param("note", "verzia 1.2").with(csrf()))
                .andExpect(redirectedUrl("/aktivity/" + this.project + "#podklady"))
                .andExpect(flash().attribute("message", "Súbor je nahraný."));
        this.mvc.perform(multipart("/aktivity/" + this.project + "/prilohy").file(file("virus.exe", PDF))
                        .param("category", "INE").with(csrf()))
                .andExpect(flash().attribute("errors", List.of("Typ súboru .exe nie je povolený. Povolené: "
                        + AttachmentService.ALLOWED + ".")));
        List<Attachment> list = this.repo.of(AttachmentRepository.Owner.PROJECT, this.project);
        assertEquals(1, list.size());
        this.mvc.perform(post("/prilohy/" + list.get(0).id() + "/zmazat").with(csrf()))
                .andExpect(redirectedUrl("/aktivity/" + this.project + "#podklady"));
        assertTrue(this.repo.of(AttachmentRepository.Owner.PROJECT, this.project).isEmpty());
        this.mvc.perform(get("/prilohy/99999")).andExpect(status().isNotFound());
        assertEquals("/aktivity/5", UploadLimitAdvice.samePath("https://hq.example/aktivity/5?x=1", "/"));
        assertEquals("/x", UploadLimitAdvice.samePath("//zla.example/x", "/"), "z cudzej domény berieme len cestu");
        assertEquals("/", UploadLimitAdvice.samePath("https://hq.example//zla.example", "/"), "cesta // by viedla von");
    }
}
