package sk.firstglobal.hq.web.received;

import sk.firstglobal.hq.web.access.AccessFilter;
import sk.firstglobal.hq.web.attachment.AttachmentRepository;
import sk.firstglobal.hq.web.project.ProjectRepository;
import sk.firstglobal.hq.web.security.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** /prijate-faktury - kniha dosslych faktur (citanie: financie, zapis: zapis financii). */
@Controller
class ReceivedInvoiceController {
    private static final int MAX_XML = 5 * 1024 * 1024;

    private final ReceivedInvoiceService service;
    private final ReceivedInvoiceRepository repo;
    private final ProjectRepository projects;
    private final AttachmentRepository attachments;
    private final Clock clock;

    ReceivedInvoiceController(ReceivedInvoiceService service, ReceivedInvoiceRepository repo, ProjectRepository projects,
                              AttachmentRepository attachments, Clock clock) {
        this.service = service;
        this.repo = repo;
        this.projects = projects;
        this.attachments = attachments;
        this.clock = clock;
    }

    @GetMapping("/prijate-faktury")
    String list(@RequestParam(required = false) String stav, Model model) {
        LocalDate today = LocalDate.now(this.clock);
        String st = stav == null ? "nezaplatene" : stav;
        List<ReceivedInvoiceRepository.ReceivedInvoice> all = this.repo.findAll();
        List<ReceivedInvoiceRepository.ReceivedInvoice> shown = all.stream().filter(r -> switch (st) {
            case "nezaplatene" -> r.paidOn() == null;
            case "po-splatnosti" -> r.isOverdue(today);
            default -> true;
        }).toList();
        model.addAttribute("invoices", shown);
        model.addAttribute("stav", st);
        model.addAttribute("today", today);
        model.addAttribute("unpaidTotal", all.stream().filter(r -> r.paidOn() == null && !r.isCreditNote())
                .map(ReceivedInvoiceRepository.ReceivedInvoice::totalPayable).reduce(BigDecimal.ZERO, BigDecimal::add));
        model.addAttribute("overdueCount", all.stream().filter(r -> r.isOverdue(today)).count());
        model.addAttribute("projects", this.projects.findAll());
        return "received/list";
    }

    @PostMapping("/prijate-faktury/xml")
    String uploadXml(@RequestParam(required = false) MultipartFile file, @RequestParam(required = false) Long projectId,
                     Authentication auth, RedirectAttributes redirect) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ReceivedInvoiceException("Vyberte XML súbor e-faktúry.");
        }
        if (file.getSize() > MAX_XML) {
            throw new ReceivedInvoiceException("E-faktúra má viac než 5 MB - to nie je bežná faktúra.");
        }
        long id = this.service.importUbl(file.getBytes(), projectId, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "E-faktúra je zapísaná v knihe došlých faktúr.");
        return "redirect:/prijate-faktury/" + id;
    }

    @PostMapping("/prijate-faktury")
    String createManual(ReceivedInvoiceService.ManualInput in, Authentication auth, RedirectAttributes redirect) {
        long id = this.service.createManual(in, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Faktúra je zapísaná. Priložte k nej PDF alebo sken.");
        return "redirect:/prijate-faktury/" + id;
    }

    @GetMapping("/prijate-faktury/{id}")
    String detail(@PathVariable long id, Model model, HttpServletRequest request) {
        ReceivedInvoiceRepository.ReceivedInvoice r = this.repo.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("r", r);
        model.addAttribute("today", LocalDate.now(this.clock));
        model.addAttribute("projects", this.projects.findAll());
        model.addAttribute("attachments", this.attachments.visible(AttachmentRepository.Owner.RECEIVED, id,
                AccessFilter.of(request)));
        model.addAttribute("uploadUrl", "/prijate-faktury/" + id + "/prilohy");
        return "received/detail";
    }

    @PostMapping("/prijate-faktury/{id}")
    String update(@PathVariable long id, @RequestParam(required = false) Long projectId,
                  @RequestParam(required = false) String category, @RequestParam(required = false) String note,
                  Authentication auth, RedirectAttributes redirect) {
        this.service.updateDetails(id, projectId, category, note, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Uložené.");
        return "redirect:/prijate-faktury/" + id;
    }

    @PostMapping("/prijate-faktury/{id}/uhrada")
    String pay(@PathVariable long id, @RequestParam(required = false) LocalDate paidOn, Authentication auth,
               RedirectAttributes redirect) {
        this.service.markPaid(id, paidOn, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", paidOn == null ? "Úhrada je zrušená a výdavok z položiek odstránený."
                : "Faktúra je uhradená - výdavok je v položkách.");
        return "redirect:/prijate-faktury/" + id;
    }

    @PostMapping("/prijate-faktury/{id}/zmazat")
    String delete(@PathVariable long id, Authentication auth, RedirectAttributes redirect) {
        this.service.delete(id, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Faktúra je zmazaná z knihy.");
        return "redirect:/prijate-faktury";
    }

    @GetMapping("/prijate-faktury/{id}/xml")
    ResponseEntity<byte[]> xml(@PathVariable long id) {
        ReceivedInvoiceRepository.ReceivedInvoice r = this.repo.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String xml = this.repo.xml(id);
        if (xml == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("dosla-" + r.number().replaceAll("[^A-Za-z0-9_-]", "_") + ".xml").build().toString())
                .contentType(MediaType.APPLICATION_XML).body(xml.getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler({ReceivedInvoiceException.class, ReceivedInvoiceService.ValidationErrors.class})
    String handle(RuntimeException e, HttpServletRequest request, RedirectAttributes redirect) {
        redirect.addFlashAttribute("errors", e instanceof ReceivedInvoiceService.ValidationErrors v ? v.errors()
                : List.of(e.getMessage()));
        String path = request.getRequestURI().substring(request.getContextPath().length());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^/prijate-faktury/(\\d+)").matcher(path);
        return m.find() && this.repo.find(Long.parseLong(m.group(1))).isPresent()
                ? "redirect:/prijate-faktury/" + m.group(1) : "redirect:/prijate-faktury";
    }
}
