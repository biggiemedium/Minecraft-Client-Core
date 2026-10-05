package dev.px.combat.vector.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.px.combat.explosion.DamageEstimate;
import dev.px.combat.explosion.Explosive;
import dev.px.combat.explosion.state.TargetState;
import dev.px.combat.vector.BlockSnapshot;
import dev.px.combat.vector.TestVector;
import dev.px.combat.vector.VectorSet;
import dev.px.combat.world.BlockShape;
import dev.px.core.math.Box;
import dev.px.core.math.Vec3;
import dev.px.core.util.Validate;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link VectorSet} as one JSON file: readable, diffable, and small enough to
 * keep in a repository beside the version profile it tests.
 *
 * <pre>
 * {
 *   "format": "px-explosion-vectors", "version": 1,
 *   "label": "1.21.1 hard", "metadata": { "server": "example.net" },
 *   "vectors": [ {
 *     "explosive": { "name": "end crystal", "power": 6.0 },
 *     "origin": [0.5, 65.0, 0.5],
 *     "target": { "position": [3.5, 65.0, 0.5], "width": 0.6, "height": 1.8, "eyeHeight": 1.62 },
 *     "state": { "armor": 20.0, "toughness": 12.0 },
 *     "blocks": { "palette": [ [[0,0,0,1,1,1]] ], "cells": [ [2,65,0,0] ] },
 *     "observed": 11.2, "popped": false,
 *     "recorded": { "inRange": true, "distance": 3.0, "exposure": 1.0, "raw": 44.5, "damage": 11.5 }
 *   } ]
 * }
 * </pre>
 *
 * <p>Block shapes are stored once in a palette and cells refer to them by index.
 * {@link #read} gives back a set equal to the one written.
 */
public final class VectorJson {

    public static final String FORMAT = "px-explosion-vectors";
    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeSpecialFloatingPointValues()
            .create();

    private VectorJson() {
    }

    // -------------------------------------------------------------- writing

    /** Writes {@code set} to {@code out}. Does not close it. */
    public static void write(VectorSet set, Writer out) throws IOException {
        Validate.notNull(set, "set");
        Validate.notNull(out, "out");
        out.write(GSON.toJson(toJson(set)));
        out.flush();
    }

    public static JsonObject toJson(VectorSet set) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("label", set.getLabel());
        JsonObject metadata = new JsonObject();
        for (Map.Entry<String, String> entry : set.getMetadata().entrySet()) {
            metadata.addProperty(entry.getKey(), entry.getValue());
        }
        root.add("metadata", metadata);
        JsonArray vectors = new JsonArray();
        for (TestVector vector : set.getVectors()) {
            vectors.add(toJson(vector));
        }
        root.add("vectors", vectors);
        return root;
    }

    private static JsonObject toJson(TestVector vector) {
        JsonObject json = new JsonObject();
        JsonObject explosive = new JsonObject();
        explosive.addProperty("name", vector.getExplosive().getName());
        explosive.addProperty("power", vector.getExplosive().getPower());
        json.add("explosive", explosive);
        json.add("origin", vec(vector.getOrigin()));

        JsonObject target = new JsonObject();
        target.add("position", vec(vector.getPosition()));
        target.addProperty("width", vector.getWidth());
        target.addProperty("height", vector.getHeight());
        target.addProperty("eyeHeight", vector.getEyeHeight());
        json.add("target", target);

        JsonObject state = new JsonObject();
        for (Map.Entry<String, Object> entry : vector.getState().asMap().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number) {
                state.addProperty(entry.getKey(), (Number) value);
            } else if (value instanceof Boolean) {
                state.addProperty(entry.getKey(), (Boolean) value);
            } else {
                state.addProperty(entry.getKey(), String.valueOf(value));
            }
        }
        json.add("state", state);
        json.add("blocks", blocks(vector.getBlocks()));
        json.addProperty("observed", vector.getObserved());
        json.addProperty("popped", vector.isPopped());

        DamageEstimate recorded = vector.getRecorded();
        if (recorded != null) {
            JsonObject estimate = new JsonObject();
            estimate.addProperty("inRange", recorded.isInRange());
            estimate.addProperty("distance", recorded.getDistance());
            estimate.addProperty("exposure", recorded.getExposure());
            estimate.addProperty("raw", recorded.getRaw());
            estimate.addProperty("damage", recorded.getDamage());
            json.add("recorded", estimate);
        }
        return json;
    }

    private static JsonObject blocks(BlockSnapshot snapshot) {
        JsonObject json = new JsonObject();
        JsonArray palette = new JsonArray();
        for (BlockShape shape : snapshot.getPalette()) {
            JsonArray boxes = new JsonArray();
            for (Box box : shape.getBoxes()) {
                JsonArray values = new JsonArray();
                values.add(new JsonPrimitive(box.getMin().getX()));
                values.add(new JsonPrimitive(box.getMin().getY()));
                values.add(new JsonPrimitive(box.getMin().getZ()));
                values.add(new JsonPrimitive(box.getMax().getX()));
                values.add(new JsonPrimitive(box.getMax().getY()));
                values.add(new JsonPrimitive(box.getMax().getZ()));
                boxes.add(values);
            }
            palette.add(boxes);
        }
        json.add("palette", palette);
        JsonArray cells = new JsonArray();
        for (int[] cell : snapshot.getCells()) {
            JsonArray values = new JsonArray();
            for (int value : cell) {
                values.add(new JsonPrimitive(value));
            }
            cells.add(values);
        }
        json.add("cells", cells);
        return json;
    }

    private static JsonArray vec(Vec3 vec) {
        JsonArray array = new JsonArray();
        array.add(new JsonPrimitive(vec.getX()));
        array.add(new JsonPrimitive(vec.getY()));
        array.add(new JsonPrimitive(vec.getZ()));
        return array;
    }

    // -------------------------------------------------------------- reading

    /**
     * @throws IOException if the stream is not a vector file, or is from a newer
     *         version of the format
     */
    @SuppressWarnings("deprecation")
    public static VectorSet read(Reader in) throws IOException {
        Validate.notNull(in, "in");
        JsonObject root;
        try {
            root = new JsonParser().parse(in).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("not a JSON object", e);
        }
        return fromJson(root);
    }

    public static VectorSet fromJson(JsonObject root) throws IOException {
        if (!root.has("format") || !FORMAT.equals(root.get("format").getAsString())) {
            throw new IOException("not a " + FORMAT + " file");
        }
        int version = root.get("version").getAsInt();
        if (version > VERSION) {
            throw new IOException("written by a newer format, version " + version + "; this reads " + VERSION);
        }
        try {
            Map<String, String> metadata = new LinkedHashMap<>();
            if (root.has("metadata")) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("metadata").entrySet()) {
                    metadata.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
            List<TestVector> vectors = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("vectors")) {
                vectors.add(vector(element.getAsJsonObject()));
            }
            return VectorSet.of(root.get("label").getAsString(), metadata, vectors);
        } catch (RuntimeException e) {
            throw new IOException("malformed vector file", e);
        }
    }

    private static TestVector vector(JsonObject json) {
        JsonObject explosive = json.getAsJsonObject("explosive");
        JsonObject target = json.getAsJsonObject("target");
        TestVector.Builder builder = TestVector.builder()
                .explosive(Explosive.of(explosive.get("name").getAsString(), explosive.get("power").getAsDouble()))
                .origin(vec(json.getAsJsonArray("origin")))
                .target(vec(target.getAsJsonArray("position")), target.get("width").getAsDouble(),
                        target.get("height").getAsDouble(), target.get("eyeHeight").getAsDouble())
                .state(state(json.getAsJsonObject("state")))
                .blocks(blocks(json.getAsJsonObject("blocks")))
                .observed(json.get("observed").getAsDouble(), json.get("popped").getAsBoolean());
        if (json.has("recorded")) {
            JsonObject estimate = json.getAsJsonObject("recorded");
            builder.recorded(DamageEstimate.of(estimate.get("inRange").getAsBoolean(),
                    estimate.get("distance").getAsDouble(), estimate.get("exposure").getAsDouble(),
                    estimate.get("raw").getAsDouble(), estimate.get("damage").getAsDouble()));
        }
        return builder.build();
    }

    private static TargetState state(JsonObject json) {
        TargetState.Builder state = TargetState.builder();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            JsonPrimitive value = entry.getValue().getAsJsonPrimitive();
            if (value.isBoolean()) {
                state.put(entry.getKey(), value.getAsBoolean());
            } else if (value.isNumber()) {
                state.put(entry.getKey(), value.getAsDouble());
            } else {
                state.put(entry.getKey(), value.getAsString());
            }
        }
        return state.build();
    }

    private static BlockSnapshot blocks(JsonObject json) {
        List<BlockShape> palette = new ArrayList<>();
        for (JsonElement shape : json.getAsJsonArray("palette")) {
            List<Box> boxes = new ArrayList<>();
            for (JsonElement box : shape.getAsJsonArray()) {
                JsonArray values = box.getAsJsonArray();
                boxes.add(Box.of(values.get(0).getAsDouble(), values.get(1).getAsDouble(), values.get(2).getAsDouble(),
                        values.get(3).getAsDouble(), values.get(4).getAsDouble(), values.get(5).getAsDouble()));
            }
            palette.add(boxes.isEmpty() ? BlockShape.EMPTY : BlockShape.of(boxes.toArray(new Box[0])));
        }
        List<int[]> cells = new ArrayList<>();
        for (JsonElement cell : json.getAsJsonArray("cells")) {
            JsonArray values = cell.getAsJsonArray();
            cells.add(new int[] { values.get(0).getAsInt(), values.get(1).getAsInt(),
                    values.get(2).getAsInt(), values.get(3).getAsInt() });
        }
        return BlockSnapshot.of(palette, cells);
    }

    private static Vec3 vec(JsonArray array) {
        return Vec3.of(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }
}
