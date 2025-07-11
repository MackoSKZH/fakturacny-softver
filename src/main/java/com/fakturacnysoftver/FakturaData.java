package com.fakturacnysoftver;

import javafx.beans.property.*;

import java.time.LocalDate;
import java.util.List;

public class FakturaData {
    private String odberatelMeno;
    private String odberatelAdresa;
    private LocalDate datum;
    private List<Item> polozky;

    public FakturaData(String odberatelMeno, String odberatelAdresa, LocalDate datum, List<Item> polozky) {
        this.odberatelMeno = odberatelMeno;
        this.odberatelAdresa = odberatelAdresa;
        this.datum = datum;
        this.polozky = polozky;
    }

    public List<Item> getPolozky() {
        return this.polozky;
    }

    public double getSubtotal() {
        return this.polozky.stream().mapToDouble(Item::getZaklad).sum();
    }

    public double getDph() {
        return this.polozky.stream().mapToDouble(Item::getDph).sum();
    }

    public double getCelkom() {
        return this.polozky.stream().mapToDouble(Item::getCenaSDph).sum();
    }

    public String getOdberatelMeno() {
        return this.odberatelMeno;
    }

    public String getOdberatelAdresa() {
        return this.odberatelAdresa;
    }

    public LocalDate getDatum() {
        return this.datum;
    }


    public static class Item {
        private final StringProperty popis = new SimpleStringProperty("");
        private final IntegerProperty mnozstvo = new SimpleIntegerProperty(1);
        private final DoubleProperty cena = new SimpleDoubleProperty(0.0);
        private static final double DPH_SADZBA = 23.0;

        public Item(String popis, int mnozstvo, double cena) {
            this.popis.set(popis);
            this.mnozstvo.set(mnozstvo);
            this.cena.set(cena);
        }

        public String getPopis() {
            return this.popis.get();
        }

        public void setPopis(String popis) {
            this.popis.set(popis);
        }

        public StringProperty popisProperty() {
            return this.popis;
        }

        public int getMnozstvo() {
            return this.mnozstvo.get();
        }

        public void setMnozstvo(int mnozstvo) {
            this.mnozstvo.set(mnozstvo);
        }

        public IntegerProperty mnozstvoProperty() {
            return this.mnozstvo;
        }

        public double getCena() {
            return this.cena.get();
        }

        public void setCena(double cena) {
            this.cena.set(cena);
        }

        public DoubleProperty cenaProperty() {
            return this.cena;
        }

        public double getZaklad() {
            return this.getMnozstvo() * this.getCena();
        }

        public double getDph() {
            return this.getZaklad() * (DPH_SADZBA / 100.0);
        }

        public double getCenaSDph() {
            return this.getZaklad() + this.getDph();
        }
    }
}
