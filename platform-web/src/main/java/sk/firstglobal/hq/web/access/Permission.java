package sk.firstglobal.hq.web.access;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Opravnenia, z ktorych sa skladaju roly. Kazdy prihlaseny ma navyse vzdy Moj program, Moju dochadzku a svoje
 * potvrdenia. ADMIN znamena vsetko.
 */
public enum Permission {
    ACTIVITIES_READ("Aktivity - čítať všetky", "harmonogramy, tímy, úlohy, majetok"),
    ACTIVITIES_WRITE("Aktivity - upravovať všetky", "aj bez vlastníctva konkrétnej aktivity"),
    PEOPLE("Ľudia a osobné údaje", "adresár, kontakty, súhlasy, rozpisy s e-mailami"),
    PARTNERS("Partneri", "kontakty na sponzorov a darcov, história komunikácie"),
    FINANCE_READ("Financie - čítať", "položky, faktúry, financovanie, rozpočty"),
    FINANCE_WRITE("Financie - zapisovať", "položky, faktúry a dobropisy, dohody a granty"),
    TIMESHEETS("Dochádzka platených", "zmluvy, hodinovky, výkazy"),
    ASSETS("Majetok - spravovať", "zapisovať, požičiavať, vyraďovať"),
    EXPORTS("Hromadné exporty", "všetky dáta naraz vrátane osobných údajov"),
    ADMIN("Admin", "používatelia, roly, pozvánky, nastavenia - a všetko ostatné");

    private final String label;
    private final String hint;

    Permission(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return this.label;
    }

    public String hint() {
        return this.hint;
    }

    /** Spring Security authority, napr. PERM_FINANCE_READ. */
    public String authority() {
        return "PERM_" + this.name();
    }

    /** Co smie dostat aj otvoreny odkaz (bez e-mailu) - ked unikne, skoda je mala. */
    public boolean isSafeForOpenInvite() {
        return this == ACTIVITIES_READ;
    }

    /** Vyplyvajuce opravnenia: ADMIN = vsetko, zapis financii zahrna citanie. */
    public static Set<Permission> expand(Iterable<Permission> granted) {
        EnumSet<Permission> out = EnumSet.noneOf(Permission.class);
        granted.forEach(out::add);
        if (out.contains(ADMIN)) {
            return EnumSet.allOf(Permission.class);
        }
        if (out.contains(FINANCE_WRITE)) {
            out.add(FINANCE_READ);
        }
        if (out.contains(ACTIVITIES_WRITE)) {
            out.add(ACTIVITIES_READ);
        }
        return out;
    }

    public static List<Permission> parse(List<String> names) {
        return names.stream().flatMap(n -> Arrays.stream(values()).filter(p -> p.name().equals(n))).toList();
    }
}
