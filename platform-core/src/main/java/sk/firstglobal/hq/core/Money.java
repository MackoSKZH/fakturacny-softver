package sk.firstglobal.hq.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Peniaze su vzdy BigDecimal so 2 desatinnymi miestami, nikdy double. */
public final class Money {
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {
    }

    public static BigDecimal round(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(String value) {
        return round(new BigDecimal(value));
    }

    /** Dan z polozky/skupiny: zaklad * sadzba / 100, zaokruhlene na centy (BR-CO-17). */
    public static BigDecimal vat(BigDecimal taxable, BigDecimal ratePercent) {
        if (ratePercent == null) {
            return round(BigDecimal.ZERO);
        }
        return round(taxable.multiply(ratePercent).divide(HUNDRED));
    }
}
