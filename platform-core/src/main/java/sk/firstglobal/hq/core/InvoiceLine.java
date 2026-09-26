package sk.firstglobal.hq.core;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Polozka faktury. Ceny su vzdy bez DPH.
 *
 * @param description popis plnenia
 * @param quantity    mnozstvo (moze byt desatinne, napr. 1.5 hod)
 * @param unitCode    jednotka podla UN/ECE Rec 20: C62 = ks, HUR = hodina, DAY = den
 * @param unitPrice   jednotkova cena bez DPH
 * @param vatCategory kategoria DPH
 * @param vatRate     sadzba v %, null pre kategoriu NOT_SUBJECT
 */
public record InvoiceLine(
        String description,
        BigDecimal quantity,
        String unitCode,
        BigDecimal unitPrice,
        VatCategory vatCategory,
        BigDecimal vatRate) {

    public static final String UNIT_PIECE = "C62";
    public static final String UNIT_HOUR = "HUR";

    public InvoiceLine {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(vatCategory, "vatCategory");
        unitCode = unitCode == null || unitCode.isBlank() ? UNIT_PIECE : unitCode;
        if (!vatCategory.hasRate()) {
            vatRate = null;
        } else if (vatRate == null) {
            vatRate = BigDecimal.ZERO;
        }
    }

    /** Polozka pre dodavatela, ktory nie je platitel DPH. */
    public static InvoiceLine notSubjectToVat(String description, BigDecimal quantity, BigDecimal unitPrice) {
        return new InvoiceLine(description, quantity, UNIT_PIECE, unitPrice, VatCategory.NOT_SUBJECT, null);
    }

    /** Polozka so sadzbou DPH (23, 19 alebo 5 %). */
    public static InvoiceLine standard(String description, BigDecimal quantity, BigDecimal unitPrice, int ratePercent) {
        return new InvoiceLine(description, quantity, UNIT_PIECE, unitPrice, VatCategory.STANDARD,
                BigDecimal.valueOf(ratePercent));
    }

    /** Cena polozky bez DPH zaokruhlena na centy (BT-131). */
    public BigDecimal netAmount() {
        return Money.round(this.quantity.multiply(this.unitPrice));
    }
}
