package sk.firstglobal.hq.web.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InvoiceSummary(
        long id,
        String number,
        LocalDate issueDate,
        LocalDate dueDate,
        String buyerName,
        String projectCode,
        BigDecimal totalPayable,
        String currency,
        LocalDate paidOn,
        boolean hasUbl,
        String docType,
        Long correctsId,
        String correctsNumber,
        String correctionReason) {

    public static final String INVOICE = "FAKTURA";
    public static final String CREDIT_NOTE = "DOBROPIS";

    public boolean isCreditNote() {
        return CREDIT_NOTE.equals(this.docType);
    }

    /** Suma so znamienkom - dobropis znizuje prijmy projektu. */
    public BigDecimal signedTotal() {
        return this.isCreditNote() ? this.totalPayable.negate() : this.totalPayable;
    }

    public boolean isOverdue() {
        return this.paidOn == null && this.dueDate != null && this.dueDate.isBefore(LocalDate.now());
    }
}
