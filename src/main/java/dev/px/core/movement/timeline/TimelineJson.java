package dev.px.core.movement.timeline;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.px.core.event.impl.PacketEvent.Direction;
import dev.px.core.event.impl.PacketEvent.Phase;
import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.movement.simulation.MovementInput;
import dev.px.core.network.packet.PacketDescription;
import dev.px.core.network.packet.PacketKind;
import dev.px.core.util.Validate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Timeline} as JSON Lines: one header object, then one object per entry.
 *
 * <pre>
 * {"format":"px-timeline","version":1,"label":"velocity test","ticks":412,...}
 * {"seq":0,"nanos":0,"tick":1,"type":"TICK_START"}
 * {"seq":1,"nanos":1204,"tick":1,"type":"PACKET","phase":"SENT","direction":"OUTBOUND","packetType":"C04PacketPlayerPosition",...}
 * </pre>
 *
 * <p>One entry per line means a recording can be grepped, diffed and streamed
 * without loading it whole. Fields that do not apply are left out rather than
 * written as null. Vectors are arrays: {@code [x, y, z]}, and {@code [yaw, pitch]}.
 *
 * <p>{@link #read} gives back a timeline equal, entry for entry, to the one that
 * was written.
 */
public final class TimelineJson {

    public static final String FORMAT = "px-timeline";
    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeSpecialFloatingPointValues()
            .create();

    private TimelineJson() {
    }

    // -------------------------------------------------------------- writing

    /** Writes {@code timeline} to {@code out}. Does not close it. */
    public static void write(Timeline timeline, Writer out) throws IOException {
        Validate.notNull(timeline, "timeline");
        Validate.notNull(out, "out");
        out.write(GSON.toJson(header(timeline)));
        out.write('\n');
        for (TimelineEntry entry : timeline.getEntries()) {
            out.write(GSON.toJson(toJson(entry)));
            out.write('\n');
        }
        out.flush();
    }

    private static JsonObject header(Timeline timeline) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("version", VERSION);
        json.addProperty("label", timeline.getLabel());
        json.addProperty("entries", timeline.size());
        json.addProperty("ticks", timeline.getTicks());
        json.addProperty("dropped", timeline.getDropped());
        json.addProperty("durationNanos", timeline.getDurationNanos());
        JsonObject metadata = new JsonObject();
        for (Map.Entry<String, String> pair : timeline.getMetadata().entrySet()) {
            metadata.addProperty(pair.getKey(), pair.getValue());
        }
        json.add("metadata", metadata);
        return json;
    }

    static JsonObject toJson(TimelineEntry entry) {
        JsonObject json = new JsonObject();
        json.addProperty("seq", entry.getSeq());
        json.addProperty("nanos", entry.getNanos());
        json.addProperty("tick", entry.getTick());
        json.addProperty("type", entry.getType().name());
        if (entry.getPhase() != null) {
            json.addProperty("phase", entry.getPhase().name());
        }
        if (entry.getDirection() != null) {
            json.addProperty("direction", entry.getDirection().name());
        }
        if (entry.getPacketType() != null) {
            json.addProperty("packetType", entry.getPacketType());
        }
        if (entry.getKind() != null) {
            json.addProperty("kind", entry.getKind().name());
        }
        if (entry.isCancelled()) {
            json.addProperty("cancelled", true);
        }
        if (entry.hasLink()) {
            json.addProperty("linkedSeq", entry.getLinkedSeq());
        }
        if (entry.getCorrelationKey() != null) {
            json.addProperty("correlationKey", entry.getCorrelationKey());
        }
        if (entry.getPosition() != null) {
            json.add("position", vec3(entry.getPosition()));
        }
        if (entry.getVelocity() != null) {
            json.add("velocity", vec3(entry.getVelocity()));
        }
        if (entry.getOnGround() != null) {
            json.addProperty("onGround", entry.getOnGround());
        }
        if (entry.getInput() != null) {
            json.add("input", input(entry.getInput()));
        }
        if (entry.getSprinting() != null) {
            json.addProperty("sprinting", entry.getSprinting());
        }
        if (entry.getSneaking() != null) {
            json.addProperty("sneaking", entry.getSneaking());
        }
        if (entry.getRotation() != null) {
            json.add("rotation", vec2(entry.getRotation()));
        }
        if (entry.getPreviousRotation() != null) {
            json.add("previousRotation", vec2(entry.getPreviousRotation()));
        }
        if (entry.getLabel() != null) {
            json.addProperty("label", entry.getLabel());
        }
        if (!entry.getFields().isEmpty()) {
            JsonObject fields = new JsonObject();
            for (Map.Entry<String, Object> pair : entry.getFields().entrySet()) {
                Object value = pair.getValue();
                if (value instanceof Boolean) {
                    fields.addProperty(pair.getKey(), (Boolean) value);
                } else if (value instanceof Number) {
                    fields.addProperty(pair.getKey(), (Number) value);
                } else {
                    fields.addProperty(pair.getKey(), String.valueOf(value));
                }
            }
            json.add("fields", fields);
        }
        return json;
    }

    private static JsonArray vec3(Vec3 v) {
        JsonArray array = new JsonArray();
        array.add(new JsonPrimitive(v.getX()));
        array.add(new JsonPrimitive(v.getY()));
        array.add(new JsonPrimitive(v.getZ()));
        return array;
    }

    private static JsonArray vec2(Vec2 v) {
        JsonArray array = new JsonArray();
        array.add(new JsonPrimitive(v.getX()));
        array.add(new JsonPrimitive(v.getY()));
        return array;
    }

    private static JsonObject input(MovementInput input) {
        JsonObject json = new JsonObject();
        json.addProperty("yaw", input.getYaw());
        json.addProperty("forward", input.getForward());
        json.addProperty("strafe", input.getStrafe());
        json.addProperty("jump", input.isJump());
        json.addProperty("sneak", input.isSneak());
        json.addProperty("sprint", input.isSprint());
        return json;
    }

    // -------------------------------------------------------------- reading

    /**
     * Reads a timeline written by {@link #write}. Does not close {@code in}.
     *
     * @throws IOException if the stream is not a timeline, or is a newer version
     */
    public static Timeline read(Reader in) throws IOException {
        Validate.notNull(in, "in");
        BufferedReader lines = in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in);
        JsonObject header = parse(lines.readLine(), 1);
        if (!FORMAT.equals(string(header, "format"))) {
            throw new IOException("not a " + FORMAT + " stream");
        }
        int version = header.get("version").getAsInt();
        if (version > VERSION) {
            throw new IOException(FORMAT + " version " + version + " is newer than this reader (" + VERSION + ")");
        }

        List<TimelineEntry> entries = new ArrayList<>();
        String line;
        int number = 1;
        while ((line = lines.readLine()) != null) {
            number++;
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                entries.add(fromJson(parse(line, number)));
            } catch (RuntimeException e) {
                throw new IOException("malformed entry on line " + number, e);
            }
        }

        Map<String, String> metadata = new LinkedHashMap<>();
        if (header.has("metadata")) {
            for (Map.Entry<String, JsonElement> pair : header.getAsJsonObject("metadata").entrySet()) {
                metadata.put(pair.getKey(), pair.getValue().getAsString());
            }
        }
        return new Timeline(string(header, "label"), entries,
                header.get("ticks").getAsLong(),
                header.get("dropped").getAsLong(),
                header.get("durationNanos").getAsLong(),
                metadata);
    }

    @SuppressWarnings("deprecation")
    private static JsonObject parse(String line, int number) throws IOException {
        if (line == null) {
            throw new IOException("empty stream: expected a " + FORMAT + " header");
        }
        try {
            return new JsonParser().parse(line).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("malformed JSON on line " + number, e);
        }
    }

    static TimelineEntry fromJson(JsonObject json) {
        TimelineEntry.Builder entry = TimelineEntry.builder(EntryType.valueOf(json.get("type").getAsString()));
        if (json.has("phase")) {
            entry.phase = Phase.valueOf(json.get("phase").getAsString());
        }
        if (json.has("direction")) {
            entry.direction = Direction.valueOf(json.get("direction").getAsString());
        }
        entry.packetType = string(json, "packetType");
        if (json.has("kind")) {
            entry.kind = PacketKind.valueOf(json.get("kind").getAsString());
        }
        entry.cancelled = json.has("cancelled") && json.get("cancelled").getAsBoolean();
        if (json.has("linkedSeq")) {
            entry.linkedSeq = json.get("linkedSeq").getAsLong();
        }
        entry.correlationKey = string(json, "correlationKey");
        if (json.has("position")) {
            entry.position = vec3(json.getAsJsonArray("position"));
        }
        if (json.has("velocity")) {
            entry.velocity = vec3(json.getAsJsonArray("velocity"));
        }
        entry.onGround = bool(json, "onGround");
        if (json.has("input")) {
            JsonObject input = json.getAsJsonObject("input");
            entry.input = MovementInput.of(input.get("yaw").getAsFloat(),
                            input.get("forward").getAsDouble(), input.get("strafe").getAsDouble())
                    .withJump(input.get("jump").getAsBoolean())
                    .withSneak(input.get("sneak").getAsBoolean())
                    .withSprint(input.get("sprint").getAsBoolean());
        }
        entry.sprinting = bool(json, "sprinting");
        entry.sneaking = bool(json, "sneaking");
        if (json.has("rotation")) {
            entry.rotation = vec2(json.getAsJsonArray("rotation"));
        }
        if (json.has("previousRotation")) {
            entry.previousRotation = vec2(json.getAsJsonArray("previousRotation"));
        }
        entry.label = string(json, "label");
        if (json.has("fields")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> pair : json.getAsJsonObject("fields").entrySet()) {
                fields.put(pair.getKey(), value(pair.getValue().getAsJsonPrimitive()));
            }
            entry.fields = fields;
        }
        return entry.build(json.get("seq").getAsLong(), json.get("nanos").getAsLong(), json.get("tick").getAsLong());
    }

    /** Numbers come back as the Long or Double {@link PacketDescription#with} stored them as. */
    private static Object value(JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isNumber()) {
            String text = primitive.getAsString();
            boolean integral = text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0;
            return integral ? (Object) primitive.getAsLong() : (Object) primitive.getAsDouble();
        }
        return primitive.getAsString();
    }

    private static Vec3 vec3(JsonArray array) {
        return Vec3.of(array.get(0).getAsDouble(), array.get(1).getAsDouble(), array.get(2).getAsDouble());
    }

    private static Vec2 vec2(JsonArray array) {
        return Vec2.of(array.get(0).getAsDouble(), array.get(1).getAsDouble());
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
    }

    private static Boolean bool(JsonObject json, String key) {
        return json.has(key) ? json.get(key).getAsBoolean() : null;
    }
}
