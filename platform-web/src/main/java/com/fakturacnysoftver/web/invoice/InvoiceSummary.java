package com.fakturacnysoftver.web.invoice;

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
        boolean hasUbl) {

    public boolean isOverdue() {
        return this.paidOn == null && this.dueDate != null && this.dueDate.isBefore(LocalDate.now());
    }
}
