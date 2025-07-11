package updater;

import java.io.*;
import java.net.URL;
import java.nio.file.*;
import java.util.zip.*;

public class Updater {
    public static void main(String[] args) {
        try {
            System.out.println("Sťahujem aktualizáciu...");
            URL zipUrl = new URL("https://tvojweb.sk/update/FakturaUpdate.zip");
            Path zipPath = Files.createTempFile("update", ".zip");

            try (InputStream in = zipUrl.openStream()) {
                Files.copy(in, zipPath, StandardCopyOption.REPLACE_EXISTING);
            }

            unzip(zipPath.toString(), "./app");

            System.out.println("Spúšťam novú verziu...");
            new ProcessBuilder("java", "-jar", "app/faktura.jar").start();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void unzip(String zipFilePath, String destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new File(newFile.getParent()).mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        zis.transferTo(fos);
                    }
                }
            }
        }
    }
}
