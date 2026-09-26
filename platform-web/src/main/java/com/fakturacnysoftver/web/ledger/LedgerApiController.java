package com.fakturacnysoftver.web.ledger;

import com.fakturacnysoftver.web.security.CurrentUser;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** JSON API pre tabulku poloziek. Zapis je v SecurityConfig obmedzeny na editorov. */
@RestController
@RequestMapping("/api/polozky")
class LedgerApiController {
    private final LedgerService service;
    private final LedgerRepository ledger;

    LedgerApiController(LedgerService service, LedgerRepository ledger) {
        this.service = service;
        this.ledger = ledger;
    }

    record Listing(List<LedgerEntry> entries, List<String> tags, List<String> categories) {
    }

    record Bulk(List<Long> ids, String action, String value) {
    }

    record Import(List<LedgerInput> rows) {
    }

    @GetMapping
    Listing list() {
        return new Listing(this.ledger.findAll(), this.ledger.allTags(), this.ledger.allCategories());
    }

    @PostMapping
    LedgerEntry create(@RequestBody LedgerInput in, Authentication auth) {
        return this.service.create(in, CurrentUser.name(auth));
    }

    @PutMapping("/{id}")
    LedgerEntry update(@PathVariable long id, @RequestBody LedgerInput in, Authentication auth) {
        return this.service.update(id, in, CurrentUser.name(auth));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable long id, @RequestParam int version, Authentication auth) {
        this.service.delete(id, version, CurrentUser.name(auth));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/hromadne")
    List<LedgerEntry> bulk(@RequestBody Bulk bulk, Authentication auth) {
        return this.service.bulk(bulk.ids(), bulk.action(), bulk.value(), CurrentUser.name(auth));
    }

    @PostMapping("/import")
    List<LedgerEntry> importRows(@RequestBody Import imp, Authentication auth) {
        return this.service.importRows(imp.rows(), CurrentUser.name(auth));
    }

    @GetMapping("/{id}/historia")
    List<LedgerRepository.HistoryItem> history(@PathVariable long id) {
        return this.ledger.history(id);
    }

    /** CSV pre Excel (bodkociarka, BOM kvoli diakritike). */
    @GetMapping(value = "/export.csv")
    ResponseEntity<byte[]> export() {
        StringBuilder sb = new StringBuilder("﻿Dátum;Popis;Typ;Suma;Projekt;Tagy;Kategória;Protistrana;Doklad;Úhrada;Poznámka\r\n");
        for (LedgerEntry e : this.ledger.findAll()) {
            sb.append(String.join(";", List.of(e.entryDate().toString(), csv(e.description()),
                    LedgerEntry.INCOME.equals(e.direction()) ? "Príjem" : "Výdavok",
                    e.amount().toPlainString().replace('.', ','), csv(e.projectCode()), csv(String.join(", ", e.tags())),
                    csv(e.category()), csv(e.counterparty()), csv(e.documentRef()),
                    LedgerEntry.CASH.equals(e.paymentMethod()) ? "Pokladňa" : "Banka", csv(e.note())))).append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"polozky.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<Map<String, Object>> handle(LedgerException e) {
        return ResponseEntity.status(e.isConflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST)
                .body(Map.of("errors", e.errors()));
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        // Obrana proti CSV/formula injection v Exceli.
        String v = s.matches("^[=+\\-@].*") ? "'" + s : s;
        return v.contains(";") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
