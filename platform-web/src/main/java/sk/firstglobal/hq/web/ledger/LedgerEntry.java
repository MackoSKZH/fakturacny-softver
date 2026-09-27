package sk.firstglobal.hq.web.ledger;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Polozka penazneho dennika. Suma je vzdy kladna, smer urcuje {@code direction}.
 * Polozka vytvorena z uhrady faktury ma {@code invoiceId} a jej suma/datum sa menia len cez fakturu.
 */
public record LedgerEntry(
        long id,
        LocalDate entryDate,
        String description,
        String direction,
        BigDecimal amount,
        Long projectId,
        String projectCode,
        List<String> tags,
        String category,
        String counterparty,
        String documentRef,
        String paymentMethod,
        String note,
        Long invoiceId,
        String invoiceNumber,
        Long receivedInvoiceId,
        int version) {

    /** Suma, datum a typ tejto polozky patria fakture (vydanej alebo dosslej) - menia sa len tam. */
    public boolean isLockedByInvoice() {
        return this.invoiceId != null || this.receivedInvoiceId != null;
    }

    public String lockReason() {
        return this.receivedInvoiceId != null ? "úhrada došlej faktúry " + this.invoiceNumber
                : "úhrada faktúry " + this.invoiceNumber;
    }

    public static final String INCOME = "PRIJEM";
    public static final String EXPENSE = "VYDAVOK";
    public static final String BANK = "BANKA";
    public static final String CASH = "POKLADNA";
}
