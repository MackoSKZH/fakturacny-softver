package sk.firstglobal.hq.web.partner;

import java.util.Arrays;

public enum PartnerKind {
    FIRMA("Firma"),
    NADACIA("Nadácia / fond"),
    VEREJNY("Verejný sektor"),
    SKOLA("Škola / univerzita"),
    JEDNOTLIVEC("Jednotlivec"),
    INE("Iné");

    private final String label;

    PartnerKind(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static String labelOf(String name) {
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst().map(PartnerKind::label).orElse(name);
    }

    public static boolean exists(String name) {
        return Arrays.stream(values()).anyMatch(k -> k.name().equals(name));
    }
}
