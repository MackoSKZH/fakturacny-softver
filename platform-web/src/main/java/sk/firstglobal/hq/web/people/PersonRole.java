package sk.firstglobal.hq.web.people;

import java.util.Arrays;
import java.util.List;

/** Roly cloveka v komunite - jeden clovek moze mat viac roli (rodic + dobrovolnik + sponzor). */
public enum PersonRole {
    ORGANIZATOR("Organizátor"),
    DOBROVOLNIK("Dobrovoľník"),
    HODNOTITEL("Hodnotiteľ / porotca"),
    ROZHODCA("Rozhodca"),
    MENTOR("Mentor"),
    STUDENT("Študent"),
    RODIC("Rodič"),
    PARTNER("Partner"),
    INVESTOR("Investor"),
    SPONZOR("Sponzor / darca"),
    PLATENY("Platený spolupracovník");

    private final String label;

    PersonRole(String label) {
        this.label = label;
    }

    public String label() {
        return this.label;
    }

    public static String labelOf(String code) {
        return Arrays.stream(values()).filter(r -> r.name().equals(code)).map(PersonRole::label).findFirst().orElse(code);
    }

    public static List<String> codes() {
        return Arrays.stream(values()).map(Enum::name).toList();
    }
}
