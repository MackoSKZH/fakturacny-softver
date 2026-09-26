package sk.firstglobal.hq.web.asset;

import java.util.Arrays;

public enum AssetCategory {
    ROBOTIKA("Robotika (sady, riadiace jednotky)"),
    POCITAC("Počítače a tablety"),
    NARADIE("Náradie"),
    DIELY("Diely a spotrebný materiál"),
    PREZENTACIA("Prezentácia (banner, stánok, dresy)"),
    INE("Iné");

    private final String label;

    AssetCategory(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static String labelOf(String name) {
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst().map(AssetCategory::label).orElse(name);
    }

    public static boolean exists(String name) {
        return Arrays.stream(values()).anyMatch(k -> k.name().equals(name));
    }
}
