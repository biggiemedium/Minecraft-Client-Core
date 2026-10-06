package dev.px.core.movement.prediction.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.px.core.math.Box;
import dev.px.core.util.Validate;
import dev.px.core.util.math.PhysicsProfile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link MovementRecording} as JSON Lines: one header object, then one object
 * per line, like a timeline.
 *
 * <pre>
 * {"format":"px-movement","version":1,"label":"strafe vs legit","ticks":1200,"sectionSize":8,...}
 * {"type":"track","id":1,"name":"Notch","history":30,"updateGap":1}
 * {"type":"sample","id":1,"tick":1,"at":[12.5,64.0,-3.2],"yaw":90.0,"size":[0.6,1.8],"stamp":-1}
 * {"type":"rules","id":1,"tick":1,"profile":{"walkSpeed":0.21585,...}}
 * {"type":"blocks","tick":1,"section":[1,8,-1],"boxes":[[8.0,63.0,-8.0,9.0,64.0,-7.0],...]}
 * </pre>
 *
 * <p>A rules line without a profile is an entity moving in a way the rules do not
 * model. {@link #read} gives back a recording that writes out identically.
 */
public final class MovementJson {

    public static final String FORMAT = "px-movement";
    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeSpecialFloatingPointValues()
            .create();

    private MovementJson() {
    }

    /** Writes {@code recording} to {@code out}. Does not close it. */
    public static void write(MovementRecording recording, Writer out) throws IOException {
        Validate.notNull(recording, "recording");
        Validate.notNull(out, "out");
        JsonObject header = new JsonObject();
        header.addProperty("format", FORMAT);
        header.addProperty("version", VERSION);
        header.addProperty("label", recording.getLabel());
        header.addProperty("ticks", recording.getTicks());
        header.addProperty("sectionSize", recording.getSectionSize());
        JsonObject metadata = new JsonObject();
        for (Map.Entry<String, String> entry : recording.getMetadata().entrySet()) {
            metadata.addProperty(entry.getKey(), entry.getValue());
        }
        header.add("metadata", metadata);
        header.add("base", profile(recording.getBase()));
        line(out, header);

        for (MovementRecording.Track track : recording.getTracks()) {
            JsonObject json = new JsonObject();
            json.addProperty("type", "track");
            json.addProperty("id", track.getId());
            if (track.getName() != null) {
                json.addProperty("name", track.getName());
            }
            json.addProperty("history", track.getHistory());
            json.addProperty("updateGap", track.getUpdateGap());
            line(out, json);
        }
        for (MovementRecording.Track track : recording.getTracks()) {
            for (MovementRecording.Rules rules : track.getRules()) {
                JsonObject json = new JsonObject();
                json.addProperty("type", "rules");
                json.addProperty("id", track.getId());
                json.addProperty("tick", rules.getTick());
                if (rules.getProfile() != null) {
                    json.add("profile", profile(rules.getProfile()));
                }
                line(out, json);
            }
            for (MovementRecording.Sample sample : track.getSamples()) {
                JsonObject json = new JsonObject();
                json.addProperty("type", "sample");
                json.addProperty("id", track.getId());
                json.addProperty("tick", sample.getTick());
                json.add("at", array(sample.getX(), sample.getY(), sample.getZ()));
                json.addProperty("yaw", sample.getYaw());
                json.add("size", array(sample.getWidth(), sample.getHeight()));
                json.addProperty("stamp", sample.getStamp());
                line(out, json);
            }
        }
        for (MovementRecording.Blocks blocks : recording.getBlocks()) {
            JsonObject json = new JsonObject();
            json.addProperty("type", "blocks");
            json.addProperty("tick", blocks.getTick());
            JsonArray section = new JsonArray();
            section.add(blocks.getSectionX());
            section.add(blocks.getSectionY());
            section.add(blocks.getSectionZ());
            json.add("section", section);
            JsonArray boxes = new JsonArray();
            for (Box box : blocks.getBoxes()) {
                boxes.add(array(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ()));
            }
            json.add("boxes", boxes);
            line(out, json);
        }
        out.flush();
    }

    /**
     * @throws IOException on anything but a {@value #FORMAT} stream this version
     *         or older can read
     */
    public static MovementRecording read(Reader in) throws IOException {
        Validate.notNull(in, "in");
        BufferedReader lines = in instanceof BufferedReader ? (BufferedReader) in : new BufferedReader(in);
        String first = lines.readLine();
        if (first == null) {
            throw new IOException("empty stream: expected a " + FORMAT + " header");
        }
        JsonObject header = parse(first);
        if (!header.has("format") || !FORMAT.equals(header.get("format").getAsString())) {
            throw new IOException("not a " + FORMAT + " stream");
        }
        int version = header.get("version").getAsInt();
        if (version > VERSION) {
            throw new IOException(FORMAT + " version " + version + " is newer than this reader (" + VERSION + ")");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : header.getAsJsonObject("metadata").entrySet()) {
            metadata.put(entry.getKey(), entry.getValue().getAsString());
        }
        PhysicsProfile base = profile(header.getAsJsonObject("base"));
        Map<Integer, MovementRecording.Track> tracks = new LinkedHashMap<>();
        List<MovementRecording.Blocks> blocks = new ArrayList<>();
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject json = parse(line);
            String type = json.get("type").getAsString();
            if ("track".equals(type)) {
                int id = json.get("id").getAsInt();
                tracks.put(id, new MovementRecording.Track(id, json.has("name") ? json.get("name").getAsString() : null,
                        json.get("history").getAsInt(), json.get("updateGap").getAsInt()));
            } else if ("sample".equals(type)) {
                JsonArray at = json.getAsJsonArray("at");
                JsonArray size = json.getAsJsonArray("size");
                track(tracks, json).samples.add(new MovementRecording.Sample(json.get("tick").getAsLong(),
                        at.get(0).getAsDouble(), at.get(1).getAsDouble(), at.get(2).getAsDouble(),
                        json.get("yaw").getAsFloat(), size.get(0).getAsDouble(), size.get(1).getAsDouble(),
                        json.get("stamp").getAsLong()));
            } else if ("rules".equals(type)) {
                track(tracks, json).rules.add(new MovementRecording.Rules(json.get("tick").getAsLong(),
                        json.has("profile") ? profile(json.getAsJsonObject("profile")) : null));
            } else if ("blocks".equals(type)) {
                JsonArray section = json.getAsJsonArray("section");
                List<Box> boxes = new ArrayList<>();
                for (JsonElement element : json.getAsJsonArray("boxes")) {
                    JsonArray box = element.getAsJsonArray();
                    boxes.add(Box.of(box.get(0).getAsDouble(), box.get(1).getAsDouble(), box.get(2).getAsDouble(),
                            box.get(3).getAsDouble(), box.get(4).getAsDouble(), box.get(5).getAsDouble()));
                }
                blocks.add(new MovementRecording.Blocks(json.get("tick").getAsLong(), section.get(0).getAsInt(),
                        section.get(1).getAsInt(), section.get(2).getAsInt(), boxes));
            }
        }
        return new MovementRecording(header.get("label").getAsString(), metadata, header.get("ticks").getAsLong(),
                header.get("sectionSize").getAsInt(), base, new ArrayList<>(tracks.values()), blocks);
    }

    private static MovementRecording.Track track(Map<Integer, MovementRecording.Track> tracks, JsonObject json)
            throws IOException {
        MovementRecording.Track track = tracks.get(json.get("id").getAsInt());
        if (track == null) {
            throw new IOException("a line for entity " + json.get("id") + " comes before its track line");
        }
        return track;
    }

    private static JsonObject profile(PhysicsProfile profile) {
        JsonObject json = new JsonObject();
        for (Map.Entry<String, Double> entry : profile.toMap().entrySet()) {
            json.addProperty(entry.getKey(), entry.getValue());
        }
        return json;
    }

    private static PhysicsProfile profile(JsonObject json) {
        Map<String, Double> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsDouble());
        }
        return PhysicsProfile.vanilla().with(values);
    }

    private static JsonArray array(double... values) {
        JsonArray array = new JsonArray();
        for (double value : values) {
            array.add(value);
        }
        return array;
    }

    private static void line(Writer out, JsonObject json) throws IOException {
        out.write(GSON.toJson(json));
        out.write('\n');
    }

    private static JsonObject parse(String line) throws IOException {
        try {
            return new JsonParser().parse(line).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("not a JSON object: " + line, e);
        }
    }
}
