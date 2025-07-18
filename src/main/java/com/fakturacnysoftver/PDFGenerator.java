package com.fakturacnysoftver;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;

/** Generuje finálnu PDF faktúru. */
public class PDFGenerator {

    public static File exportToPdf(FakturaData f, UserData fa, Window owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Ulož PDF faktúru");
        fc.getExtensionFilters()
                .add(new FileChooser.ExtensionFilter("PDF súbor", "*.pdf"));
        fc.setInitialFileName(generateFileName(f));
        File file = fc.showSaveDialog(owner);
        if (file != null) {
            generateAndSavePdf(f, fa, file);
        }
        return file;
    }

    public static void printFaktura(FakturaData f, UserData fa, Window owner) {
        try {
            File tmp = File.createTempFile("faktura_", ".pdf");
            generateAndSavePdf(f, fa, tmp);

            if (Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().print(tmp);
                } catch (IOException ex) {
                    Desktop.getDesktop().open(tmp);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void generateAndSavePdf(FakturaData f, UserData fa, File file) {
        try (PDDocument doc = new PDDocument()) {

            // Unicode fonty (NotoSans Regular + Bold)
            InputStream regular = PDFGenerator.class.getResourceAsStream(
                    "/fonts/NotoSans-Regular.ttf");
            InputStream bold = PDFGenerator.class.getResourceAsStream(
                    "/fonts/NotoSans-Bold.ttf");

            PDType0Font fontReg = PDType0Font.load(doc, regular, true);
            PDType0Font fontBold = PDType0Font.load(doc, bold, true);

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs =
                         new PDPageContentStream(doc, page,
                                 PDPageContentStream.AppendMode.OVERWRITE, true)) {
                drawFaktura(cs, f, fa, fontReg, fontBold);
            }
            doc.save(file);

        } catch (IOException e) {
            throw new RuntimeException("Chyba pri ukladaní PDF", e);
        }
    }

    private static void drawFaktura(PDPageContentStream cs,
                                    FakturaData f,
                                    UserData fa,
                                    PDType0Font reg,
                                    PDType0Font bold) throws IOException {

        final float margin = 50;
        final float width = PDRectangle.A4.getWidth() - 2 * margin;
        float y = PDRectangle.A4.getHeight() - margin;
        cs.setLeading(14);

        cs.beginText();
        cs.setFont(bold, 14);
        cs.newLineAtOffset(margin, y);
        cs.showText("Faktúra - daňový doklad");
        cs.endText();
        y -= 30;

        cs.beginText();
        cs.setFont(bold, 10);
        cs.newLineAtOffset(margin, y);
        cs.showText("Predávajúci:");
        cs.setFont(reg, 10);
        cs.newLine();
        cs.showText("Názov: " + fa.getNazov());
        cs.newLine();
        cs.showText("Sídlo: " + fa.getSidlo());
        cs.newLine();
        cs.showText("IČO: " + fa.getIco());
        cs.newLine();
        cs.showText("DIČ: " + fa.getDic());
        cs.newLine();

        if (!fa.getKontakt().isBlank()) {
            cs.showText("Kontakt: " + fa.getKontakt());
            cs.newLine();
        }
        cs.endText();

        y -= 90;
        cs.beginText();
        cs.setFont(bold, 10);
        cs.newLineAtOffset(margin, y);
        cs.showText("Kupujúci:");
        cs.setFont(reg, 10);
        cs.newLine();
        printIfPresent(cs, "Názov: " + f.getOdberatelMeno());
        printIfPresent(cs, "Sídlo: " + f.getOdberatelAdresa());
        printIfPresent(cs, prefixed("IČO: ",  f.getOdberatelIco()));
        printIfPresent(cs, prefixed("DIČ: ",  f.getOdberatelDic()));
        printIfPresent(cs, prefixed("IČ DPH: ", f.getOdberatelIcdph()));
        printIfPresent(cs, prefixed("Tel.: ", f.getOdberatelTelefon()));
        printIfPresent(cs, prefixed("E-mail: ", f.getOdberatelEmail()));
        printIfPresent(cs, "Dátum vystavenia: "
                + f.getDatum().format(DateTimeFormatter.ofPattern("d.M.yyyy")));
        cs.endText();

        y -= 150;
        float rowH = 18;
        float[] colW = {150, 40, 60, 60, 60, 60};
        String[] header = {"Popis", "Ks", "Cena ks", "bez DPH", "DPH", "Cena"};

        float curX = margin;
        cs.setLineWidth(0.5f);
        for (int i = 0; i < header.length; i++) {
            cs.addRect(curX, y, colW[i], rowH);
            cs.beginText();
            cs.setFont(bold, 9);
            cs.newLineAtOffset(curX + 2, y + 5);
            cs.showText(header[i]);
            cs.endText();
            curX += colW[i];
        }
        cs.stroke();

        float rowY = y - rowH;
        for (FakturaData.Item it : f.getPolozky()) {
            curX = margin;
            String[] data = {
                    it.getPopis(),
                    String.valueOf(it.getMnozstvo()),
                    fmt(it.getCena()),
                    fmt(it.getZaklad()),
                    fmt(it.getDph()),
                    fmt(it.getCenaSDph())
            };
            for (int i = 0; i < data.length; i++) {
                cs.addRect(curX, rowY, colW[i], rowH);
                cs.beginText();
                cs.setFont(reg, 9);
                cs.newLineAtOffset(curX + 2, rowY + 5);
                cs.showText(data[i]);
                cs.endText();
                curX += colW[i];
            }
            rowY -= rowH;
        }
        cs.stroke();

        rowY -= 20;
        cs.beginText();
        cs.setFont(reg, 10);
        cs.newLineAtOffset(margin, rowY);
        cs.showText(String.format("Základ dane: %.2f €", f.getSubtotal()));
        cs.newLine();
        cs.showText(String.format("DPH 23 %%  : %.2f €", f.getDph()));
        cs.newLine();
        cs.setFont(bold, 10);
        cs.showText(String.format("Spolu s DPH: %.2f €", f.getCelkom()));
        cs.endText();

        rowY -= 60;
        cs.beginText();
        cs.setFont(bold, 10);
        cs.newLineAtOffset(margin, rowY);
        cs.showText("Platobné údaje:");
        cs.setFont(reg, 10);
        cs.newLine();
        printIfPresent(cs, prefixed("Banka: ",  f.getBankaNazov()));
        printIfPresent(cs, prefixed("IBAN: ",   f.getBankaIban()));
        printIfPresent(cs, prefixed("BIC/SWIFT: ", f.getBankaSwift()));
        printIfPresent(cs, prefixed("Variabilný symbol: ", f.getVariabilnySymbol()));
        cs.endText();
    }

    private static void printIfPresent(PDPageContentStream cs, String txt) throws IOException {
        if (txt != null && !txt.isBlank()) {
            cs.showText(txt);
            cs.newLine();
        }
    }

    private static String prefixed(String prefix, String val) {
        return (val == null || val.isBlank()) ? "" : prefix + val;
    }
    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private static String generateFileName(FakturaData f) {
        String date = f.getDatum().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String name = f.getOdberatelMeno()
                .replaceAll("[^\\p{IsAlphabetic}\\d]", "_");
        return "Faktura_" + date + "_" + name + ".pdf";
    }
}