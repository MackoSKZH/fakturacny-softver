package sk.firstglobal.hq.web.partner;

import java.util.Arrays;

/** Forma financovania - urcuje, ci ide o dar, fakturu (reklama) alebo grant s vyuctovanim. */
public enum DealKind {
    DAR("Dar", "Bez protiplnenia. Poďakovanie je v poriadku, reklama darcu už nie."),
    REKLAMA("Reklama / sponzoring", "Protiplnenie (logo, banner, zmienka) = služba. Vystavte faktúru."),
    GRANT("Grant / dotácia", "Čerpanie v oprávnenom období a vyúčtovanie v termíne."),
    VECNE("Vecné plnenie", "Materiál, služby, priestory. Zaznamenajte hodnotu a darovaciu zmluvu."),
    INVESTICIA("Investícia (NTE startup)", "Investícia do startupu tímu - nie príjem združenia."),
    INE("Iné", "");

    private final String label;
    private final String hint;

    DealKind(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return this.label;
    }

    public String hint() {
        return this.hint;
    }

    public static String labelOf(String name) {
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst().map(DealKind::label).orElse(name);
    }

    public static boolean exists(String name) {
        return Arrays.stream(values()).anyMatch(k -> k.name().equals(name));
    }
}
