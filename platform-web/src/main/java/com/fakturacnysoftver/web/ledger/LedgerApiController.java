package com.fakturacnysoftver.web.ledger;

import com.fakturacnysoftver.web.export.Table;
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

import java.math.BigDecimal;
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

    /** Export do Excelu, PDF alebo CSV; volitelne len jedna aktivita (?projekt=). */
    @GetMapping(value = "/export.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> export(@PathVariable String format, @RequestParam(required = false) Long projekt) {
        List<LedgerEntry> entries = this.ledger.findAll().stream()
                .filter(e -> projekt == null || projekt.equals(e.projectId())).toList();
        Table table = new Table("Položky - príjmy a výdavky", "Dátum", "Popis", "Typ", "Suma (€)", "Projekt", "Tagy",
                "Kategória", "Protistrana", "Doklad", "Úhrada", "Poznámka");
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        for (LedgerEntry e : entries) {
            boolean in = LedgerEntry.INCOME.equals(e.direction());
            income = in ? income.add(e.amount()) : income;
            expense = in ? expense : expense.add(e.amount());
            table.row(e.entryDate(), e.description(), in ? "Príjem" : "Výdavok", e.amount(), e.projectCode(), e.tags(),
                    e.category(), e.counterparty(), e.documentRef(),
                    LedgerEntry.CASH.equals(e.paymentMethod()) ? "Pokladňa" : "Banka", e.note());
        }
        if (projekt != null && !entries.isEmpty()) {
            table.subtitle("Aktivita " + entries.get(0).projectCode());
        }
        table.note("Príjmy " + Table.text(income) + " € · výdavky " + Table.text(expense) + " € · rozdiel "
                + Table.text(income.subtract(expense)) + " € · počet položiek " + entries.size());
        return table.response(format, projekt == null ? "polozky" : "polozky-" + projekt);
    }

    @ExceptionHandler(LedgerException.class)
    ResponseEntity<Map<String, Object>> handle(LedgerException e) {
        return ResponseEntity.status(e.isConflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST)
                .body(Map.of("errors", e.errors()));
    }
}
