package com.fakturacnysoftver;

import java.io.Serial;
import java.io.Serializable;

public class UserData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String nazov = "Company, LLC";
    private String sidlo = "City";
    private String ico = "123123";
    private String dic = "1231231231";
    private String kontakt = "0900 012 345";
    private String zastupeny = "Mgr. Prezident, Phd.";

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