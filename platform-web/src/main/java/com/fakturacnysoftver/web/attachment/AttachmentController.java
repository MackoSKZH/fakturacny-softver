package com.fakturacnysoftver.web.attachment;

import com.fakturacnysoftver.web.access.AccessFilter;
import com.fakturacnysoftver.web.activity.ActivityRepository;

import jakarta.servlet.http.HttpServletRequest;

import com.fakturacnysoftver.web.partner.PartnerRepository;
import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/** Nahravanie a stahovanie podkladov k aktivitam, partnerom a dohodam. */
@Controller
class AttachmentController {
    private final AttachmentService service;
    private final AttachmentRepository repo;
    private final ActivityRepository activities;
    private final PartnerRepository partners;
    private final com.fakturacnysoftver.web.received.ReceivedInvoiceRepository received;
    private final Clock clock;

    AttachmentController(AttachmentService service, AttachmentRepository repo, ActivityRepository activities,
                         PartnerRepository partners, com.fakturacnysoftver.web.received.ReceivedInvoiceRepository received,
                         Clock clock) {
        this.received = received;
        this.service = service;
        this.repo = repo;
        this.activities = activities;
        this.partners = partners;
        this.clock = clock;
    }

    @PostMapping("/aktivity/{id}/prilohy")
    String toActivity(@PathVariable long id, @RequestParam(required = false) MultipartFile file,
                      @RequestParam(required = false) String category, @RequestParam(defaultValue = "false") boolean editorsOnly,
                      @RequestParam(required = false) String note, Authentication auth, RedirectAttributes redirect) {
        this.activities.findById(id, LocalDate.now(this.clock)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return this.upload(AttachmentRepository.Owner.PROJECT, id, "/aktivity/" + id, file, category, editorsOnly, note,
                auth, redirect);
    }

    @PostMapping("/partneri/{id}/prilohy")
    String toPartner(@PathVariable long id, @RequestParam(required = false) MultipartFile file,
                     @RequestParam(required = false) String category, @RequestParam(defaultValue = "false") boolean editorsOnly,
                     @RequestParam(required = false) String note, Authentication auth, RedirectAttributes redirect) {
        this.partners.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return this.upload(AttachmentRepository.Owner.PARTNER, id, "/partneri/" + id, file, category, true, note, auth,
                redirect);
    }

    @PostMapping("/financovanie/{id}/prilohy")
    String toDeal(@PathVariable long id, @RequestParam(required = false) MultipartFile file,
                  @RequestParam(required = false) String category, @RequestParam(defaultValue = "false") boolean editorsOnly,
                  @RequestParam(required = false) String note, Authentication auth, RedirectAttributes redirect) {
        this.partners.deal(id, LocalDate.now(this.clock)).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return this.upload(AttachmentRepository.Owner.DEAL, id, "/financovanie/" + id, file, category, editorsOnly, note,
                auth, redirect);
    }

    @PostMapping("/prijate-faktury/{id}/prilohy")
    String toReceived(@PathVariable long id, @RequestParam(required = false) MultipartFile file,
                      @RequestParam(required = false) String category, @RequestParam(required = false) String note,
                      Authentication auth, RedirectAttributes redirect) {
        this.received.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return this.upload(AttachmentRepository.Owner.RECEIVED, id, "/prijate-faktury/" + id, file,
                category == null ? "DOKLAD" : category, false, note, auth, redirect);
    }

    private String upload(AttachmentRepository.Owner owner, long id, String back, MultipartFile file, String category,
                          boolean editorsOnly, String note, Authentication auth, RedirectAttributes redirect) {
        try {
            this.service.upload(owner, id, file, category, editorsOnly, note, CurrentUser.name(auth));
            redirect.addFlashAttribute("message", "Súbor je nahraný.");
        } catch (AttachmentException e) {
            redirect.addFlashAttribute("errors", List.of(e.getMessage()));
        }
        return "redirect:" + back + "#podklady";
    }

    @GetMapping("/prilohy/{id}")
    ResponseEntity<byte[]> download(@PathVariable long id, HttpServletRequest request) {
        Attachment a = this.repo.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!a.visibleTo(AccessFilter.of(request))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(a.fileName(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(a.contentType()))
                .body(this.repo.content(id));
    }

    @PostMapping("/prilohy/{id}/zmazat")
    String delete(@PathVariable long id, Authentication auth, HttpServletRequest request, RedirectAttributes redirect) {
        Attachment found = this.repo.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!found.deletableBy(AccessFilter.of(request))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Attachment a = this.service.delete(id, CurrentUser.name(auth));
        redirect.addFlashAttribute("message", "Súbor " + a.fileName() + " je zmazaný.");
        return "redirect:" + a.ownerUrl() + "#podklady";
    }
}
