package sk.firstglobal.hq.core;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dobropis (opravna faktura podla § 71 ods. 2 zakona o DPH). Polozky a sumy su kladne a znamenaju
 * sumu v prospech odberatela; odkazuje na povodnu fakturu a uvadza dovod opravy.
 *
 * @param body              udaje dokladu - cislo dobropisu, datumy, strany, polozky na dobropisanie
 * @param originalNumber    cislo opravovanej faktury
 * @param originalIssueDate datum vyhotovenia opravovanej faktury
 * @param reason            dovod opravy
 */
public record CreditNote(Invoice body, String originalNumber, LocalDate originalIssueDate, String reason) {

    public CreditNote {
        Objects.requireNonNull(body, "body");
    }

    public InvoiceTotals totals() {
        return this.body.totals();
    }

    public List<String> validate() {
        List<String> errors = new ArrayList<>(InvoiceValidator.validate(this.body));
        errors.removeIf(e -> e.equals("Chýba dátum splatnosti."));
        if (this.originalNumber == null || this.originalNumber.isBlank()) {
            errors.add("Dobropis musí odkazovať na pôvodnú faktúru.");
        }
        if (this.reason == null || this.reason.isBlank()) {
            errors.add("Uveďte dôvod opravy.");
        }
        if (this.originalIssueDate != null && this.body.issueDate() != null
                && this.body.issueDate().isBefore(this.originalIssueDate)) {
            errors.add("Dobropis nemôže byť vystavený skôr než pôvodná faktúra.");
        }
        return errors;
    }

    public List<String> validateForPeppol() {
        List<String> errors = this.validate();
        List<String> peppol = new ArrayList<>(InvoiceValidator.validateForPeppol(this.body));
        peppol.removeAll(InvoiceValidator.validate(this.body));
        errors.addAll(peppol);
        return errors;
    }
}
