package com.fakturacnysoftver;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class StorageManager {
    private static final Path BIN_PATH = Path.of(System.getProperty("user.home"), ".farnost", "farnost.bin");

    public static FarnostData loadFarnost() {
        try {
            if (Files.exists(BIN_PATH)) {
                try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(BIN_PATH))) {
                    return (FarnostData)in.readObject();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new FarnostData(); // default values
    }

    public static void saveFarnost(FarnostData data) {
        try {
            Files.createDirectories(BIN_PATH.getParent());
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(BIN_PATH))) {
                out.writeObject(data);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}