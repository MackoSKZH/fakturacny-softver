package com.fakturacnysoftver;

import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;

public class PDFGenerator {

    public static File exportToPdf(FakturaData faktura, FarnostData farnost, Window owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Ulož PDF faktúru");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF súbor", "*.pdf"));
        fc.setInitialFileName(generateFileName(faktura));
        File file = fc.showSaveDialog(owner);
        if (file != null) {
            generateAndSavePdf(faktura, farnost, file);
        }
        return file;
    }

    public static void printFaktura(FakturaData faktura, FarnostData farnost, Window owner) {
        try {
            File temp = File.createTempFile("faktura_", ".pdf");
            generateAndSavePdf(faktura, farnost, temp);

            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().print(temp);
                } catch (IOException ex) {
                    Desktop.getDesktop().open(temp);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void generateAndSavePdf(FakturaData faktura, FarnostData farnost, File file) {
        try (PDDocument doc = new PDDocument()) {

            // Načítaj fonty
            InputStream regularStream = PDFGenerator.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf");
            InputStream boldStream = PDFGenerator.class.getResourceAsStream("/fonts/NotoSans-Bold.ttf");

            PDType0Font fontRegular = PDType0Font.load(doc, regularStream, true);
            PDType0Font fontBold = PDType0Font.load(doc, boldStream, true);

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawFaktura(cs, faktura, farnost, fontRegular, fontBold);
            }

            doc.save(file);

        } catch (IOException e) {
            throw new RuntimeException("Chyba pri ukladaní PDF", e);
        }
    }

    private static void drawFaktura(PDPageContentStream cs, FakturaData faktura, FarnostData farnost,
                                    PDType0Font fontRegular, PDType0Font fontBold) throws IOException {
        float margin = 50;
        float y = PDRectangle.A4.getHeight() - margin;
        cs.setLeading(14f);

        cs.beginText();
        cs.setFont(fontBold, 12);
        cs.newLineAtOffset(margin, y);
        cs.showText(farnost.getNazov());
        cs.newLine();

        cs.setFont(fontRegular, 10);
        cs.showText("Sídlo: " + farnost.getSidlo());
        cs.newLine();
        cs.showText("IČO: " + farnost.getIco() + "   DIČ: " + farnost.getDic());
        cs.newLine();
        cs.showText("Kontakt: " + farnost.getKontakt());
        cs.newLine();
        cs.newLine();

        cs.setFont(fontBold, 11);
        cs.showText("Odberateľ:");
        cs.newLine();

        cs.setFont(fontRegular, 10);
        cs.showText(faktura.getOdberatelMeno());
        cs.newLine();
        cs.showText(faktura.getOdberatelAdresa());
        cs.newLine();
        cs.newLine();
        cs.showText("Dátum vystavenia: " + faktura.getDatum().format(DateTimeFormatter.ISO_LOCAL_DATE));
        cs.endText();

        float tableStartY = y - 160;
        float x = margin;

        // Hlavička tabuľky
        cs.beginText();
        cs.setFont(fontBold, 9);
        cs.newLineAtOffset(x, tableStartY);
        cs.showText(String.format("%-25s %5s %10s %6s %10s %10s %10s",
                "Popis", "MJ", "Cena", "DPH", "Bez DPH", "DPH", "S DPH"));
        cs.endText();

        // Telo tabuľky
        float rowY = tableStartY - 14;
        for (FakturaData.Item item : faktura.getPolozky()) {
            double bezDPH = item.getZaklad();
            double dph = item.getDph();
            double sDPH = item.getCenaSDph();

            cs.beginText();
            cs.setFont(fontRegular, 9);
            cs.newLineAtOffset(x, rowY);
            cs.showText(String.format("%-25s %5d %10.2f %6s %10.2f %10.2f %10.2f",
                    item.getPopis(), item.getMnozstvo(), item.getCena(), "23%", bezDPH, dph, sDPH));
            cs.endText();
            rowY -= 14;
        }

        rowY -= 20;

        // Zhrnutie DPH
        cs.beginText();
        cs.setFont(fontBold, 9);
        cs.newLineAtOffset(x, rowY);
        cs.showText("Zhrnutie DPH:");
        cs.endText();

        rowY -= 12;
        double subtotal = faktura.getSubtotal();
        double dphTotal = faktura.getDph();
        double total = faktura.getCelkom();

        cs.beginText();
        cs.setFont(fontRegular, 9);
        cs.newLineAtOffset(x, rowY);
        cs.showText(String.format("Sadzba: 23%%   Základ: %.2f €   DPH: %.2f €   Spolu: %.2f €", subtotal, dphTotal, total));
        cs.endText();

        rowY -= 16;
        cs.beginText();
        cs.setFont(fontBold, 10);
        cs.newLineAtOffset(x, rowY);
        cs.showText(String.format("Celkom k úhrade: %.2f €", total));
        cs.newLine();
        cs.showText("Zaplatený preddavok: 0.00 €");
        cs.newLine();
        cs.showText(String.format("Zostáva uhradiť: %.2f €", total));
        cs.endText();
    }

    private static String generateFileName(FakturaData f) {
        String date = f.getDatum().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String name = f.getOdberatelMeno().replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "_");
        return "Faktura_" + date + "_" + name + ".pdf";
    }
}
