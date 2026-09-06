package com.mcphoneultra.client.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** 简单的 JSON 持久化小助手：每个 App 一个文件，原子写入。 */
public final class Store {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private JsonObject root;

    private Store(Path file) {
        this.file = file;
        this.root = new JsonObject();
        if (Files.isRegularFile(file)) {
            try {
                JsonObject parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                if (parsed != null) root = parsed;
            } catch (Exception ignored) {
                // 文件坏了就当作空配置，下次保存会覆盖
            }
        }
    }

    public static Store of(String name) {
        return new Store(Paths.file("state", name + ".json"));
    }

    public JsonObject obj() {
        return root;
    }

    public String get(String key, String def) {
        return root.has(key) ? root.get(key).getAsString() : def;
    }

    public void set(String key, String value) {
        root.addProperty(key, value);
        save();
    }

    public boolean getBool(String key, boolean def) {
        return root.has(key) ? root.get(key).getAsBoolean() : def;
    }

    public void setBool(String key, boolean value) {
        root.addProperty(key, value);
        save();
    }

    public int getInt(String key, int def) {
        return root.has(key) ? root.get(key).getAsInt() : def;
    }

    public void setInt(String key, int value) {
        root.addProperty(key, value);
        save();
    }

    public void save() {
        try {
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("無法寫入 " + file, e);
        }
    }

    public static Optional<String> readAll(Path p) {
        try {
            if (!Files.isRegularFile(p)) return Optional.empty();
            return Optional.of(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public static void writeAll(Path p, String content) {
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("無法寫入 " + p, e);
        }
    }
}
