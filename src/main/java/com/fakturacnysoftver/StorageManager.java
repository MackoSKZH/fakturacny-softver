package com.fakturacnysoftver;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public class StorageManager {
    private static final Path BIN_PATH = Path.of(System.getProperty("user.home"), ".user", "user.bin");

    public static UserData loadUser() {
        try {
            if (Files.exists(BIN_PATH)) {
                try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(BIN_PATH))) {
                    return (UserData)in.readObject();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new UserData();
    }

    public static void saveUser(UserData data) {
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