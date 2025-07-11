package com.fakturacnysoftver;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;

public class PDFGenerator {

    public static File exportToPdf(FakturaData faktura, FarnostData farnost, Window owner) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Ulož PDF faktúru");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PDF súbor", "*.pdf"));
        fc.setInitialFileName(generateFileName(faktura));
        File file = fc.showSaveDialog(owner);
        if (file == null) return null;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawFaktura(cs, faktura, farnost);
            }
            doc.save(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return file;
    }

    public static void printFaktura(FakturaData faktura, FarnostData farnost, Window owner) {
        // TODO: implement direct printing via javax.print or JavaFX PrinterJob
    }

    private static void drawFaktura(PDPageContentStream cs, FakturaData faktura, FarnostData farnost) throws IOException {
        cs.setFont(PDType1Font.HELVETICA_BOLD, 14);
        cs.beginText();
        cs.newLineAtOffset(50, 780);
        cs.showText(farnost.getNazov());
        cs.endText();
        // … ďalšie texty a tabuľky
    }

    private static String generateFileName(FakturaData faktura) {
        String date = faktura.getDatum().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String name = faktura.getOdberatelMeno().replaceAll("[^a-zA-Z0-9]", "_");
        return "Faktura_" + date + "_" + name + ".pdf";
    }
}