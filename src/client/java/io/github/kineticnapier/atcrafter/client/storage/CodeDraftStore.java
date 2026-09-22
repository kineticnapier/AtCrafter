package io.github.kineticnapier.atcrafter.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

public final class CodeDraftStore {
    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create();
    private static final Path FILE = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("atcrafter-code-drafts.json");
    private static final Map<String, String> DRAFTS = new LinkedHashMap<>();
    private static boolean loaded;

    private CodeDraftStore() {
    }

    public static synchronized String get(String problemId) {
        ensureLoaded();
        return DRAFTS.get(problemId);
    }

    public static synchronized void put(String problemId, String code) {
        if (problemId == null || problemId.isBlank() || code == null) {
            return;
        }
        ensureLoaded();
        String previous = DRAFTS.put(problemId, code);
        if (!code.equals(previous)) {
            save();
        }
    }

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        if (!Files.isRegularFile(FILE)) {
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(Files.readString(FILE, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject drafts = root.getAsJsonObject("drafts");
            if (drafts == null) {
                return;
            }
            drafts.entrySet().forEach(entry -> {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                    DRAFTS.put(entry.getKey(), entry.getValue().getAsString());
                }
            });
        } catch (Exception error) {
            System.err.println("[AtCrafter] Failed to load code drafts: " + error);
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        JsonObject drafts = new JsonObject();
        DRAFTS.forEach(drafts::addProperty);
        root.add("drafts", drafts);

        try {
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            Files.writeString(temp, GSON.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            System.err.println("[AtCrafter] Failed to save code drafts: " + error);
        }
    }
}
