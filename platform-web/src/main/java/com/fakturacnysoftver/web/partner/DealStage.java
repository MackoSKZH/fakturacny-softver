package com.fakturacnysoftver.web.partner;

import java.util.Arrays;

/** Pipeline: oslovili sme -> rokujeme -> dohodnuté -> zaplatené (alebo odmietnuté). */
public enum DealStage {
    OSLOVENY("Oslovený"),
    ROKUJEME("Rokujeme"),
    DOHODNUTE("Dohodnuté"),
    ZAPLATENE("Zaplatené"),
    ODMIETNUTE("Odmietnuté");

    private final String label;

    DealStage(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public boolean isOpen() {
        return this == OSLOVENY || this == ROKUJEME;
    }

    public boolean isSecured() {
        return this == DOHODNUTE || this == ZAPLATENE;
    }

    public static DealStage parse(String name) {
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst().orElse(null);
    }
}
