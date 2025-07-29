package com.fakturacnysoftver;

import javafx.stage.FileChooser;
import javafx.stage.Window;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import java.time.format.DateTimeFormatter;

import java.util.logging.Level;
import java.util.logging.Logger;

public class PDFGenerator {
    private static final Logger LOGGER = Logger.getLogger(PDFGenerator.class.getName());

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

    public static void printFaktura(FakturaData f, UserData fa) {
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
            LOGGER.log(Level.SEVERE, "Chyba pri generovaní dočasného PDF", e);
        }
    }

    private static void generateAndSavePdf(FakturaData f, UserData fa, File file) {
        try (PDDocument doc = new PDDocument()) {
            InputStream regular = PDFGenerator.class.getResourceAsStream("/fonts/NotoSans-Regular.ttf");
            InputStream bold = PDFGenerator.class.getResourceAsStream("/fonts/NotoSans-Bold.ttf");

            BufferedImage logoBuffered = null;
            PDImageXObject logoImage = null;

            try (InputStream logoStream = PDFGenerator.class.getResourceAsStream("/images/logo.png")) {
                if (logoStream != null) {
                    byte[] logoBytes = logoStream.readAllBytes();
                    logoBuffered = ImageIO.read(new java.io.ByteArrayInputStream(logoBytes));
                    if (logoBuffered != null) {
                        logoImage = PDImageXObject.createFromByteArray(doc, logoBytes, "logo");
                    } else {
                        LOGGER.warning("Nepodarilo sa načítať logo.png – neznámy formát alebo poškodený súbor.");
                    }
                } else {
                    LOGGER.info("Logo sa nenašlo v classpath: /images/logo.png");
                }
            } catch (IOException e) {
                LOGGER.warning("Chyba pri načítaní loga: " + e.getMessage());
            }

            PDType0Font fontReg = PDType0Font.load(doc, regular, true);
            PDType0Font fontBold = PDType0Font.load(doc, bold, true);

            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.OVERWRITE, true)) {
                drawFaktura(cs, f, fa, fontReg, fontBold, logoImage, logoBuffered);
            }

            doc.save(file);

        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Chyba pri generovaní alebo ukladaní PDF faktúry", e);
            throw new RuntimeException("Chyba pri ukladaní PDF", e);
        }
    }

    private static void drawFaktura(PDPageContentStream cs,
                                    FakturaData f,
                                    UserData fa,
                                    PDType0Font reg,
                                    PDType0Font bold,
                                    PDImageXObject logoImage,
                                    BufferedImage logoBuffered) throws IOException {

        final boolean jePlatca = fa.isPlatcaDPH();

        final float margin = 50;
        float y = PDRectangle.A4.getHeight() - margin;
        cs.setLeading(14);

        if (logoImage != null && logoBuffered != null) {
            float logoWidth = 100f;
            float aspectRatio = (float)logoBuffered.getHeight() / logoBuffered.getWidth();
            float logoHeight = logoWidth * aspectRatio;
            float logoX = PDRectangle.A4.getWidth() - logoWidth - margin;
            float logoY = PDRectangle.A4.getHeight() - logoHeight - 30;

            cs.drawImage(logoImage, logoX, logoY, logoWidth, logoHeight);
        }

        String cisloFaktury = generateInvoiceNumber(f);
        f.setCisloFaktury(cisloFaktury);

        cs.beginText();
        cs.setFont(bold, 20);
        float textWidth = bold.getStringWidth(cisloFaktury) / 1000 * 20;
        float centerX = (PDRectangle.A4.getWidth() - textWidth) / 2;
        cs.newLineAtOffset(centerX, y);
        cs.showText(cisloFaktury);
        cs.endText();

        y -= 30;

        cs.beginText();
        cs.setFont(bold, 14);
        cs.newLineAtOffset(margin, y);
        cs.showText("Faktúra");
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
        if (!fa.getKontakt().isBlank()) {
            cs.newLine();
            cs.showText("Kontakt: " + fa.getKontakt());
        }
        if (!jePlatca) {
            cs.newLine();
            cs.showText("Nie je platiteľ DPH podľa § 4 zákona o DPH.");
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
        printIfPresent(cs, prefixed("IČO: ", f.getOdberatelIco()));
        printIfPresent(cs, prefixed("DIČ: ", f.getOdberatelDic()));
        printIfPresent(cs, prefixed("IČ DPH: ", f.getOdberatelIcdph()));
        printIfPresent(cs, prefixed("Tel.: ", f.getOdberatelTelefon()));
        printIfPresent(cs, prefixed("E-mail: ", f.getOdberatelEmail()));
        cs.endText();

        y -= 120;

        cs.beginText();
        cs.setFont(bold, 10);
        cs.newLineAtOffset(margin, y);
        cs.showText("Informácie o faktúre:");
        cs.setFont(reg, 10);
        cs.newLine();
        printIfPresent(cs, "Dátum vystavenia: " + f.getDatum().format(DateTimeFormatter.ofPattern("d.M.yyyy")));

        if (f.getDatumDodania() != null && !f.getDatumDodania().equals(f.getDatum())) {
            printIfPresent(cs, "Dátum dodania: " + f.getDatumDodania().format(DateTimeFormatter.ofPattern("d.M.yyyy")));
        }
        cs.endText();

        y -= 150;
        float rowH = 18;

        float[] colW = jePlatca
                ? new float[]{180, 40, 60, 60, 60, 60}
                : new float[]{180, 40, 80};
        String[] header = jePlatca
                ? new String[]{"Popis", "Ks", "Cena ks", "bez DPH", "DPH", "Cena"}
                : new String[]{"Popis", "Ks", "Cena celkom"};

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
            cs.setFont(reg, 9);
            cs.setLeading(12);
            String[] data = buildItemData(it, jePlatca);

            for (int i = 0; i < data.length; i++) {
                cs.addRect(curX, rowY, colW[i], rowH);
                cs.beginText();
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
        cs.showText(String.format("Celková suma: %.2f €", f.getCelkom(jePlatca)));
        cs.endText();

        boolean maPlatbu =
                !(fa.getBankaNazov() == null || fa.getBankaNazov().isBlank()) ||
                        !(fa.getBankaIban() == null || fa.getBankaIban().isBlank()) ||
                        !(fa.getBankaSwift() == null || fa.getBankaSwift().isBlank()) ||
                        !(f.getVariabilnySymbol() == null || f.getVariabilnySymbol().isBlank());

        if (maPlatbu) {
            rowY -= 60;
            cs.beginText();
            cs.setFont(bold, 10);
            cs.newLineAtOffset(margin, rowY);
            cs.showText("Platobné údaje:");
            cs.setFont(reg, 10);
            cs.newLine();
            printIfPresent(cs, prefixed("Banka: ", fa.getBankaNazov()));
            printIfPresent(cs, prefixed("IBAN: ", fa.getBankaIban()));
            printIfPresent(cs, prefixed("BIC/SWIFT: ", fa.getBankaSwift()));
            printIfPresent(cs, prefixed("Variabilný symbol: ", f.getVariabilnySymbol()));
            cs.endText();
        }
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

    public static String generateFileName(FakturaData f) {
        String cislo = f.getCisloFaktury();
        if (cislo == null || cislo.isBlank()) {
            cislo = generateInvoiceNumber(f);
        }
        return cislo.replaceAll("[^\\w-]", "_") + ".pdf";
    }

    private static String generateInvoiceNumber(FakturaData f) {
        if (f.getCisloFaktury() != null && !f.getCisloFaktury().isBlank()) {
            return f.getCisloFaktury();
        }
        String num = "F" + f.getDatum().format(DateTimeFormatter.ofPattern("yyyyMMdd")) +
                "-" + System.currentTimeMillis() % 100000;
        f.setCisloFaktury(num);
        return num;
    }

    private static String[] buildItemData(FakturaData.Item it, boolean jePlatca) {
        if (jePlatca) {
            return new String[]{
                    it.getPopis(),
                    String.valueOf(it.getMnozstvo()),
                    fmt(it.getCena()),
                    fmt(it.getZaklad()),
                    fmt(it.getDph()),
                    fmt(it.getCenaSDph())
            };
        } else {
            return new String[]{
                    it.getPopis(),
                    String.valueOf(it.getMnozstvo()),
                    fmt(it.getZaklad())
            };
        }
    }
}