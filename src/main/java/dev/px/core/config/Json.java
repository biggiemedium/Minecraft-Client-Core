package dev.px.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The shared Gson instance and file helpers.
 *
 * <p>Pretty printing is on because configs are meant to be hand-editable; that
 * was the one real virtue of the old plain-text format and it is worth keeping.
 * Gson ships with the game on every version, so this adds no dependency.
 */
public final class Json {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private Json() {
    }

    public static Gson gson() {
        return GSON;
    }

    public static void write(Path path, JsonObject json) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        }
    }

    /** @return the parsed object, or an empty one if the file is missing or malformed. */
    public static JsonObject read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new JsonObject();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement parsed = new JsonParser().parse(reader);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Malformed config at " + path, e);
        }
    }

    /** @return the named child object, or an empty one. Saves a has-then-get at every call site. */
    public static JsonObject child(JsonObject parent, String key) {
        if (parent.has(key) && parent.get(key).isJsonObject()) {
            return parent.getAsJsonObject(key);
        }
        return new JsonObject();
    }
}
