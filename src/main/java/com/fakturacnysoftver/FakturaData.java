package com.fakturacnysoftver;

import javafx.beans.property.*;

import java.time.LocalDate;
import java.util.List;

public class FakturaData {
    private String odberatelMeno;
    private String odberatelAdresa;
    private String odberatelIco;
    private String odberatelDic;
    private String odberatelIcdph;
    private String odberatelTelefon;
    private String odberatelEmail;
    private String bankaNazov;
    private String bankaIban;
    private String bankaSwift;
    private String variabilnySymbol;

    private LocalDate datum;
    private List<Item> polozky;

    // KONŠTRUKTOR (zatiaľ základný, môžeš rozšíriť podľa potreby)
    public FakturaData(String odberatelMeno, String odberatelAdresa, LocalDate datum, List<Item> polozky) {
        this.odberatelMeno = odberatelMeno;
        this.odberatelAdresa = odberatelAdresa;
        this.datum = datum;
        this.polozky = polozky;
    }

    // GETTRE a SETTRE

    public String getOdberatelMeno() {
        return this.odberatelMeno;
    }

    public void setOdberatelMeno(String odberatelMeno) {
        this.odberatelMeno = odberatelMeno;
    }

    public String getOdberatelAdresa() {
        return this.odberatelAdresa;
    }

    public void setOdberatelAdresa(String odberatelAdresa) {
        this.odberatelAdresa = odberatelAdresa;
    }

    public String getOdberatelIco() {
        return this.odberatelIco;
    }

    public void setOdberatelIco(String odberatelIco) {
        this.odberatelIco = odberatelIco;
    }

    public String getOdberatelDic() {
        return this.odberatelDic;
    }

    public void setOdberatelDic(String odberatelDic) {
        this.odberatelDic = odberatelDic;
    }

    public String getOdberatelIcdph() {
        return this.odberatelIcdph;
    }

    public void setOdberatelIcdph(String odberatelIcdph) {
        this.odberatelIcdph = odberatelIcdph;
    }

    public String getOdberatelTelefon() {
        return this.odberatelTelefon;
    }

    public void setOdberatelTelefon(String odberatelTelefon) {
        this.odberatelTelefon = odberatelTelefon;
    }

    public String getOdberatelEmail() {
        return this.odberatelEmail;
    }

    public void setOdberatelEmail(String odberatelEmail) {
        this.odberatelEmail = odberatelEmail;
    }

    public String getBankaNazov() {
        return this.bankaNazov;
    }

    public void setBankaNazov(String bankaNazov) {
        this.bankaNazov = bankaNazov;
    }

    public String getBankaIban() {
        return this.bankaIban;
    }

    public void setBankaIban(String bankaIban) {
        this.bankaIban = bankaIban;
    }

    public String getBankaSwift() {
        return this.bankaSwift;
    }

    public void setBankaSwift(String bankaSwift) {
        this.bankaSwift = bankaSwift;
    }

    public String getVariabilnySymbol() {
        return this.variabilnySymbol;
    }

    public void setVariabilnySymbol(String variabilnySymbol) {
        this.variabilnySymbol = variabilnySymbol;
    }

    public LocalDate getDatum() {
        return this.datum;
    }

    public void setDatum(LocalDate datum) {
        this.datum = datum;
    }

    public List<Item> getPolozky() {
        return this.polozky;
    }

    public void setPolozky(List<Item> polozky) {
        this.polozky = polozky;
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

    // VNORENÁ TRIEDA Item
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
