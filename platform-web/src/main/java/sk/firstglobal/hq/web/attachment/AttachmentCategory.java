package sk.firstglobal.hq.web.attachment;

import java.util.Arrays;

public enum AttachmentCategory {
    VYZVA("Hárok k výzve"),
    PRAVIDLA("Pravidlá a manuál"),
    HODNOTENIE("Hodnotiaci hárok"),
    ZMLUVA("Zmluva (len editori)"),
    DOKLAD("Doklad / faktúra"),
    FOTO("Foto a média"),
    INE("Iné");

    private final String label;

    AttachmentCategory(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static String labelOf(String name) {
        return Arrays.stream(values()).filter(k -> k.name().equals(name)).findFirst().map(AttachmentCategory::label).orElse(name);
    }

    public static boolean exists(String name) {
        return Arrays.stream(values()).anyMatch(k -> k.name().equals(name));
    }
}
