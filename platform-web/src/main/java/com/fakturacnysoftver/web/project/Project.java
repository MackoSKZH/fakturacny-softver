package com.fakturacnysoftver.web.project;

import java.math.BigDecimal;

/**
 * Projekt s rozpoctom. Prijmy su zatial len z vystavenych faktur - vydavky pribudnu
 * s prijatymi e-fakturami a bankovym vypisom.
 */
public record Project(Long id, String code, String name, BigDecimal budget, boolean active, BigDecimal invoiced,
                      BigDecimal income, BigDecimal spent) {

    /** Zostatok rozpoctu = rozpocet - vydavky. */
    public BigDecimal remaining() {
        return this.budget.subtract(this.spent);
    }

    public boolean isOverBudget() {
        return this.budget.signum() > 0 && this.spent.compareTo(this.budget) > 0;
    }
}
