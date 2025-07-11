package com.fakturacnysoftver;

import java.io.Serializable;

public class FarnostData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String nazov = "Rímskokatolícka cirkev, Farnosť Sedembolestnej Panny Márie";
    private String sidlo = "M. Chrásteka 2772/28, 965 01 Žiar nad Hronom";
    private String ico = "45021635";
    private String dic = "2022704266";
    private String kontakt = "mob. 0902 398 422, e‑mail: ziar.nhspm@fara.sk";
    private String zastupeny = "JCLic. Vladimír Václavík, farár";

    public String getNazov() {
        return this.nazov;
    }
    public String getSidlo() {
        return this.sidlo;
    }
    public String getIco() {
        return this.ico;
    }
    public String getDic() {
        return this.dic;
    }
    public String getKontakt() {
        return this.kontakt;
    }
    public String getZastupeny() {
        return this.zastupeny;
    }

    public void setNazov(String nazov) {
        this.nazov = nazov;
    }
    public void setSidlo(String sidlo) {
        this.sidlo = sidlo;
    }
    public void setIco(String ico) {
        this.ico = ico;
    }
    public void setDic(String dic) {
        this.dic = dic;
    }
    public void setKontakt(String kontakt) {
        this.kontakt = kontakt;
    }
    public void setZastupeny(String zastupeny) {
        this.zastupeny = zastupeny;
    }
}