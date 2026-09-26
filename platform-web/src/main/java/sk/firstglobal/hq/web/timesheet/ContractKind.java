package sk.firstglobal.hq.web.timesheet;

import java.math.BigDecimal;

/**
 * Typy zmluv a ich zakonne limity (Zakonnik prace, stav 2026):
 * DoVP najviac 350 h za kalendarny rok (§ 226), DoPC najviac 10 h tyzdenne (§ 228a),
 * DoBPS v priemere najviac 20 h tyzdenne (§ 227), dohody najviac na 12 mesiacov, najviac 12 h za 24 h.
 */
public enum ContractKind {
    DOVP("Dohoda o vykonaní práce", true),
    DOPC("Dohoda o pracovnej činnosti", true),
    DOBPS("Dohoda o brigádnickej práci študentov", true),
    PRACOVNY_POMER("Pracovný pomer", true),
    ZIVNOST("Živnosť / faktúra", false);

    /** Minimalna hodinova mzda od 1. 1. 2026 pri 40-hodinovom tyzdni. */
    public static final BigDecimal MIN_HOURLY_WAGE_2026 = new BigDecimal("5.259");
    public static final BigDecimal DOVP_YEAR_LIMIT = new BigDecimal("350");
    public static final BigDecimal DOPC_WEEK_LIMIT = new BigDecimal("10");
    public static final BigDecimal DOBPS_WEEK_AVERAGE_LIMIT = new BigDecimal("20");
    public static final BigDecimal DAY_LIMIT = new BigDecimal("12");

    private final String label;
    private final boolean employment;

    ContractKind(String label, boolean employment) {
        this.label = label;
        this.employment = employment;
    }

    public String label() {
        return this.label;
    }

    /** Pracovnopravny vztah - plati minimalna mzda a evidencia pracovneho casu. */
    public boolean isEmployment() {
        return this.employment;
    }

    public boolean isAgreement() {
        return this == DOVP || this == DOPC || this == DOBPS;
    }

    public static ContractKind parse(String v) {
        for (ContractKind k : values()) {
            if (k.name().equals(v)) {
                return k;
            }
        }
        return null;
    }
}
