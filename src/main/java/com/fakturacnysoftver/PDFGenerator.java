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
        float startX = margin;
        float width = PDRectangle.A4.getWidth() - 2 * margin;
        float rowHeight = 18;

        cs.setLeading(14f);

        // Nadpis
        cs.beginText();
        cs.setFont(fontBold, 14);
        cs.newLineAtOffset(startX, y);
        cs.showText("Faktúra – daňový doklad");
        cs.endText();

        y -= 30;

        // Predávajúci (Farnost)
        cs.beginText();
        cs.setFont(fontBold, 11);
        cs.newLineAtOffset(startX, y);
        cs.showText("Predávajúci:");
        cs.newLine();
        cs.setFont(fontRegular, 10);
        cs.showText("Názov:" + farnost.getNazov());
        cs.newLine();
        cs.showText("Sídlo" + farnost.getSidlo());
        cs.newLine();
        cs.showText("IČO: " + farnost.getIco());
        cs.newLine();
        cs.showText("DIČ: " + farnost.getDic());
        cs.newLine();
        cs.showText("Kontakt: " + farnost.getKontakt());
        cs.endText();

        y -= 100;

        // Odberateľ
        cs.beginText();
        cs.setFont(fontBold, 11);
        cs.newLineAtOffset(startX, y);
        cs.showText("Odberateľ:");
        cs.newLine();
        cs.setFont(fontRegular, 10);
        cs.showText("Názov" + faktura.getOdberatelMeno());
        cs.newLine();
        cs.showText("Sídlo" + faktura.getOdberatelAdresa());
        cs.newLine();

        if (faktura.getOdberatelIco() != null && !faktura.getOdberatelIco().isBlank())
            cs.showText("IČO: " + faktura.getOdberatelIco());
        cs.newLine();

        if (faktura.getOdberatelDic() != null && !faktura.getOdberatelDic().isBlank())
            cs.showText("DIČ: " + faktura.getOdberatelDic());
        cs.newLine();

        if (faktura.getOdberatelIcdph() != null && !faktura.getOdberatelIcdph().isBlank())
            cs.showText("IČ DPH: " + faktura.getOdberatelIcdph());
        cs.newLine();

        if (faktura.getOdberatelTelefon() != null && !faktura.getOdberatelTelefon().isBlank())
            cs.showText("Telefón: " + faktura.getOdberatelTelefon());
        cs.newLine();

        if (faktura.getOdberatelEmail() != null && !faktura.getOdberatelEmail().isBlank())
            cs.showText("Email: " + faktura.getOdberatelEmail());
        cs.newLine();

        if (faktura.getBankaNazov() != null && !faktura.getBankaNazov().isBlank())
            cs.showText("Banka: " + faktura.getBankaNazov());
        cs.newLine();

        if (faktura.getBankaIban() != null && !faktura.getBankaIban().isBlank())
            cs.showText("IBAN: " + faktura.getBankaIban());
        cs.newLine();

        if (faktura.getBankaSwift() != null && !faktura.getBankaSwift().isBlank())
            cs.showText("BIC/SWIFT: " + faktura.getBankaSwift());
        cs.newLine();

        if (faktura.getVariabilnySymbol() != null && !faktura.getVariabilnySymbol().isBlank())
            cs.showText("Variabilný symbol: " + faktura.getVariabilnySymbol());
        cs.newLine();
        cs.newLine();
        cs.showText("Dátum vystavenia: " + faktura.getDatum().format(DateTimeFormatter.ISO_LOCAL_DATE));
        cs.endText();

        y -= 90;

        // Tabuľka hlavička
        float tableX = startX;
        float[] colWidths = {150, 60, 60, 60, 60, 60}; // Popis, MJ, Cena, Základ, DPH, Spolu
        String[] headers = {"Popis", "Množ.", "Cena", "Základ", "DPH", "Spolu"};

        float tableY = y;
        float currentX;

        // Hlavička tabuľky
        currentX = tableX;
        for (int i = 0; i < headers.length; i++) {
            cs.addRect(currentX, tableY, colWidths[i], rowHeight);
            cs.beginText();
            cs.setFont(fontBold, 9);
            cs.newLineAtOffset(currentX + 2, tableY + 5);
            cs.showText(headers[i]);
            cs.endText();
            currentX += colWidths[i];
        }

        cs.stroke();

        // Riadky s položkami
        float contentY = tableY - rowHeight;
        for (FakturaData.Item item : faktura.getPolozky()) {
            currentX = tableX;
            String[] values = {
                    item.getPopis(),
                    String.valueOf(item.getMnozstvo()),
                    String.format("%.2f", item.getCena()),
                    String.format("%.2f", item.getZaklad()),
                    String.format("%.2f", item.getDph()),
                    String.format("%.2f", item.getCenaSDph())
            };

            for (int i = 0; i < values.length; i++) {
                cs.addRect(currentX, contentY, colWidths[i], rowHeight);
                cs.beginText();
                cs.setFont(fontRegular, 9);
                cs.newLineAtOffset(currentX + 2, contentY + 5);
                cs.showText(values[i]);
                cs.endText();
                currentX += colWidths[i];
            }

            contentY -= rowHeight;
        }

        cs.stroke();

        // Súhrn
        contentY -= 20;
        cs.beginText();
        cs.setFont(fontRegular, 10);
        cs.newLineAtOffset(startX, contentY);
        cs.showText(String.format("Celkový základ: %.2f €", faktura.getSubtotal()));
        cs.newLine();
        cs.showText(String.format("DPH (23%%): %.2f €", faktura.getDph()));
        cs.newLine();
        cs.setFont(fontBold, 10);
        cs.showText(String.format("Spolu s DPH: %.2f €", faktura.getCelkom()));
        cs.endText();
    }

    private static String generateFileName(FakturaData f) {
        String date = f.getDatum().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String name = f.getOdberatelMeno().replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "_");
        return "Faktura_" + date + "_" + name + ".pdf";
    }
}
