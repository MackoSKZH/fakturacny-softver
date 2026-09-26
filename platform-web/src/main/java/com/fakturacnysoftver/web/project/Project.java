package com.fakturacnysoftver.web.project;

import java.math.BigDecimal;

/**
 * Projekt s rozpoctom. Prijmy su zatial len z vystavenych faktur - vydavky pribudnu
 * s prijatymi e-fakturami a bankovym vypisom.
 */
public record Project(Long id, String code, String name, BigDecimal budget, boolean active, BigDecimal invoiced) {
}
