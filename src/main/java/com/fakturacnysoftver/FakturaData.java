package com.fakturacnysoftver;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.List;

public class FakturaData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String odberatelMeno;
    private String odberatelAdresa;
    private LocalDate datum;
    private List<Item> polozky;

    public record Item(String popis, int mnozstvo, double cena) implements Serializable { }

    // gettery + pomocné výpočty …
}