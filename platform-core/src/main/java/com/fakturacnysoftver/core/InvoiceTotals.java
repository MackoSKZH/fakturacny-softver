package com.fakturacnysoftver.core;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sucty faktury podla EN 16931. DPH sa pocita za kazdu skupinu (kategoria + sadzba),
 * nie po polozkach - inak vznikaju centove rozdiely, ktore Peppol validacia odmietne.
 */
public record InvoiceTotals(
        BigDecimal lineExtensionAmount,
        List<VatBreakdown> vatBreakdown,
        BigDecimal vatAmount,
        BigDecimal amountWithVat,
        BigDecimal payableAmount) {

    public record VatBreakdown(VatCategory category, BigDecimal ratePercent, BigDecimal taxableAmount,
                               BigDecimal taxAmount, String exemptionCode, String exemptionReason) {
    }

    private record GroupKey(VatCategory category, BigDecimal rate) {
    }

    public static InvoiceTotals of(Invoice invoice) {
        Map<GroupKey, BigDecimal> taxableByGroup = new LinkedHashMap<>();
        BigDecimal lineTotal = Money.round(BigDecimal.ZERO);

        for (InvoiceLine line : invoice.lines()) {
            BigDecimal net = line.netAmount();
            lineTotal = lineTotal.add(net);
            BigDecimal rate = line.vatRate() == null ? null : line.vatRate().stripTrailingZeros();
            taxableByGroup.merge(new GroupKey(line.vatCategory(), rate), net, BigDecimal::add);
        }

        List<VatBreakdown> breakdown = new ArrayList<>();
        BigDecimal vatTotal = Money.round(BigDecimal.ZERO);
        for (Map.Entry<GroupKey, BigDecimal> e : taxableByGroup.entrySet()) {
            VatCategory category = e.getKey().category();
            BigDecimal rate = e.getKey().rate();
            BigDecimal taxable = Money.round(e.getValue());
            BigDecimal tax = category == VatCategory.STANDARD ? Money.vat(taxable, rate) : Money.round(BigDecimal.ZERO);
            vatTotal = vatTotal.add(tax);
            breakdown.add(new VatBreakdown(category, rate, taxable, tax,
                    category.defaultExemptionCode(), category.defaultExemptionReason()));
        }

        BigDecimal withVat = lineTotal.add(vatTotal);
        return new InvoiceTotals(lineTotal, List.copyOf(breakdown), vatTotal, withVat, withVat);
    }
}
