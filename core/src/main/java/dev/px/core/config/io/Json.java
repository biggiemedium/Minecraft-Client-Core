package dev.px.core.config.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

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

    /** Writes {@code json} to {@code path} atomically: see {@link #writeAtomically}. */
    public static void write(Path path, JsonObject json) throws IOException {
        writeAtomically(path, toBytes(json));
    }

    /** @return the parsed object, or an empty one if the file is missing */
    public static JsonObject read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new JsonObject();
        }
        return parse(Files.readAllBytes(path));
    }

    /** @return {@code json}, pretty-printed, as UTF-8 */
    public static byte[] toBytes(JsonObject json) {
        return GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @return the object in {@code bytes}; an empty one for an empty file
     * @throws IOException if they are not a JSON object
     */
    @SuppressWarnings("deprecation")
    public static JsonObject parse(byte[] bytes) throws IOException {
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.trim().isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement parsed = new JsonParser().parse(text);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IOException("not a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("malformed JSON", e);
        }
    }

    /**
     * Replaces {@code path} with {@code bytes} so a reader sees the old file or the
     * new one, never half of either: written to a temporary file beside it, flushed
     * to disk, then renamed over it. A crash mid-save leaves the last good file.
     */
    public static void writeAtomically(Path path, byte[] bytes) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = parent.resolve(path.getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
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
