package sk.firstglobal.hq.web.asset;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public record Asset(
        long id,
        String inventoryNo,
        String name,
        String category,
        String serialNo,
        LocalDate purchasedOn,
        BigDecimal price,
        Long ledgerEntryId,
        String ledgerLabel,
        Long dealId,
        String dealTitle,
        LocalDate keepUntil,
        String location,
        String status,
        LocalDate retiredOn,
        String retiredReason,
        String note,
        Long loanId,
        Long borrowerId,
        String borrowerName,
        LocalDate lentOn,
        LocalDate loanDueOn) {

    /** § 22 zakona o dani z prijmov: hmotny majetok so vstupnou cenou nad 1 700 € a pouzitelnostou nad rok. */
    public static final BigDecimal LONG_TERM_LIMIT = new BigDecimal("1700");

    public String categoryLabel() {
        return AssetCategory.labelOf(this.category);
    }

    public boolean isLent() {
        return this.loanId != null;
    }

    public boolean isRetired() {
        return "VYRADENY".equals(this.status);
    }

    public String statusLabel() {
        if (this.isRetired()) {
            return "Vyradené";
        }
        if (this.isLent()) {
            return "Požičané";
        }
        return "OPRAVA".equals(this.status) ? "V oprave" : "K dispozícii";
    }

    public boolean isLoanOverdue(LocalDate today) {
        return this.isLent() && this.loanDueOn != null && this.loanDueOn.isBefore(today);
    }

    public List<String> warnings(LocalDate today) {
        List<String> w = new ArrayList<>();
        if (this.isLoanOverdue(today)) {
            w.add("Malo sa vrátiť " + fmt(this.loanDueOn) + " (" + this.borrowerName + ").");
        }
        if (!this.isRetired() && this.price != null && this.price.compareTo(LONG_TERM_LIMIT) > 0) {
            w.add("Cena nad 1 700 € - dlhodobý hmotný majetok. Zaradenie a odpisy riešte s účtovníkom.");
        }
        if (this.dealId != null && this.keepUntil == null && !this.isRetired()) {
            w.add("Kúpené z grantu - doplňte, dokedy ho podľa zmluvy treba udržať.");
        }
        if (this.ledgerEntryId == null && this.price != null && this.price.signum() > 0 && !this.isRetired()) {
            w.add("Chýba prepojenie na doklad o kúpe (položku).");
        }
        return w;
    }

    static String fmt(LocalDate d) {
        return d.getDayOfMonth() + ". " + d.getMonthValue() + ". " + d.getYear();
    }
}
