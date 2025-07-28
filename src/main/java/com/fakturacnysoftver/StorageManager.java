package com.fakturacnysoftver;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.logging.Level;
import java.util.logging.Logger;

public class StorageManager {
    private static final Path BIN_PATH = Path.of(System.getProperty("user.home"), ".user", "user.bin");
    private static final Logger LOGGER = Logger.getLogger(StorageManager.class.getName());

    public static UserData loadUser() {
        try {
            if (Files.exists(BIN_PATH)) {
                try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(BIN_PATH))) {
                    return (UserData)in.readObject();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error while loading user data", e);
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
            LOGGER.log(Level.SEVERE, "Error while saving user data", e);
        }
    }
}