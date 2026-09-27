package sk.firstglobal.hq.web.export;

import sk.firstglobal.hq.web.audit.AuditLog;
import sk.firstglobal.hq.web.security.CurrentUser;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** /exporty - vsetky prehlady v Exceli, PDF a CSV a jeden ZIP so vsetkym. Len editori (obsahuje osobne udaje). */
@Controller
class ExportController {
    private final ExportCatalog catalog;
    private final AuditLog audit;
    private final Clock clock;

    ExportController(ExportCatalog catalog, AuditLog audit, Clock clock) {
        this.catalog = catalog;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/exporty")
    String hub(Model model) {
        model.addAttribute("exports", this.catalog.all());
        return "exports";
    }

    @GetMapping("/exporty/{key:[a-z][a-z-]*}.{format:csv|xlsx|pdf}")
    ResponseEntity<byte[]> one(@PathVariable String key, @PathVariable String format, Authentication auth) {
        ExportCatalog.Export e = this.catalog.find(key).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        this.audit.record(CurrentUser.name(auth), "EXPORT", "export", key, format);
        return this.catalog.build(e).subtitle("Stav k " + Table.text(LocalDate.now(this.clock)))
                .response(format, key + "-" + LocalDate.now(this.clock));
    }

    /** Vsetko naraz v Exceli - zaloha do Google Drive, podklad pre uctovnika alebo revizora. */
    @GetMapping("/exporty/vsetko.zip")
    ResponseEntity<byte[]> all(Authentication auth) {
        LocalDate today = LocalDate.now(this.clock);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (ExportCatalog.Export e : this.catalog.all()) {
                zip.putNextEntry(new ZipEntry(e.key() + "-" + today + ".xlsx"));
                zip.write(this.catalog.build(e).subtitle("Stav k " + Table.text(today)).bytes(Table.Format.XLSX));
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        this.audit.record(CurrentUser.name(auth), "EXPORT", "export", "vsetko", "zip");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"fgs-hq-export-" + today + ".zip\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(bytes.toByteArray());
    }
}
