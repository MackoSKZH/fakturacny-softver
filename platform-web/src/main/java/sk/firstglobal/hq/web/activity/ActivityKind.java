package sk.firstglobal.hq.web.activity;

/** Typy aktivit FIRST Global Slovakia; kazdy ma vlastnu sablonu checklistu. */
public enum ActivityKind {
    NARODNE_KOLO("Národné kolo FGS"),
    CESTA("Cesta na FGC / výjazd"),
    NTE("NTE (New Technology Experience)"),
    SUSTREDENIE("Sústredenie"),
    FLL_TURNAJ("FLL turnaj"),
    WORKSHOP("Workshop"),
    INE("Iné");

    private final String label;

    ActivityKind(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static ActivityKind parse(String v) {
        try {
            return v == null ? INE : valueOf(v);
        } catch (IllegalArgumentException e) {
            return INE;
        }
    }
}
