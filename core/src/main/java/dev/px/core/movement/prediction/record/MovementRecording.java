package dev.px.core.movement.prediction.record;

import dev.px.core.math.Box;
import dev.px.core.util.math.PhysicsProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real movement, kept: where each recorded entity was every tick, as the server
 * sent it, what rules your client said it moved by, and the blocks around it.
 *
 * <p>Enough to put a prediction back in the same place and ask it again
 * &mdash; {@link MovementReplay} &mdash; which is how a change to the prediction is
 * measured against real players, legit and otherwise, rather than against the
 * simulation's own idea of them.
 *
 * <p>Built by {@link MovementRecorder}, saved and read by {@link MovementJson}.
 */
public final class MovementRecording {

    private final String label;
    private final Map<String, String> metadata;
    private final long ticks;
    private final int sectionSize;
    private final PhysicsProfile base;
    private final List<Track> tracks;
    private final List<Blocks> blocks;

    MovementRecording(String label, Map<String, String> metadata, long ticks, int sectionSize, PhysicsProfile base,
                      List<Track> tracks, List<Blocks> blocks) {
        this.label = label;
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        this.ticks = ticks;
        this.sectionSize = sectionSize;
        this.base = base;
        this.tracks = Collections.unmodifiableList(new ArrayList<>(tracks));
        this.blocks = Collections.unmodifiableList(new ArrayList<>(blocks));
    }

    public String getLabel() {
        return label;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    /** @return ticks recorded, numbered from 1 */
    public long getTicks() {
        return ticks;
    }

    /** @return the edge, in blocks, of the cubes the world was captured in */
    public int getSectionSize() {
        return sectionSize;
    }

    /** @return the simulation's profile while recording: what each entity's rules were built on */
    public PhysicsProfile getBase() {
        return base;
    }

    /** @return each entity recorded */
    public List<Track> getTracks() {
        return tracks;
    }

    /** @return each capture of a section of the world, in the order taken */
    public List<Blocks> getBlocks() {
        return blocks;
    }

    @Override
    public String toString() {
        return "MovementRecording(" + label + ", " + ticks + " ticks, " + tracks.size() + " entities, "
                + blocks.size() + " block captures)";
    }

    /** One entity: who, how its tracker kept it, and every tick it was seen. */
    public static final class Track {
        private final int id;
        private final String name;
        private final int history;
        private final int updateGap;
        final List<Sample> samples = new ArrayList<>();
        final List<Rules> rules = new ArrayList<>();

        Track(int id, String name, int history, int updateGap) {
            this.id = id;
            this.name = name;
            this.history = history;
            this.updateGap = updateGap;
        }

        public int getId() {
            return id;
        }

        /** @return what your names function called it; null without one */
        public String getName() {
            return name;
        }

        /** @return its tracker's history, in ticks */
        public int getHistory() {
            return history;
        }

        /** @return its tracker's update gap */
        public int getUpdateGap() {
            return updateGap;
        }

        /** @return every tick it was seen, oldest first */
        public List<Sample> getSamples() {
            return Collections.unmodifiableList(samples);
        }

        /** @return each change in the rules your client said it moved by, oldest first */
        public List<Rules> getRules() {
            return Collections.unmodifiableList(rules);
        }

        @Override
        public String toString() {
            return "Track(" + id + (name == null ? "" : " " + name) + ", " + samples.size() + " samples)";
        }
    }

    /** Where an entity was one tick, as the server last sent it. */
    public static final class Sample {
        private final long tick;
        private final double x;
        private final double y;
        private final double z;
        private final float yaw;
        private final double width;
        private final double height;
        private final long stamp;

        Sample(long tick, double x, double y, double z, float yaw, double width, double height, long stamp) {
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.width = width;
            this.height = height;
            this.stamp = stamp;
        }

        public long getTick() {
            return tick;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getZ() {
            return z;
        }

        public float getYaw() {
            return yaw;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }

        /** @return the stamp your source gave; -1 when none */
        public long getStamp() {
            return stamp;
        }
    }

    /** From this tick on, the rules your client said an entity moved by; none when not by the rules at all. */
    public static final class Rules {
        private final long tick;
        private final PhysicsProfile profile;

        Rules(long tick, PhysicsProfile profile) {
            this.tick = tick;
            this.profile = profile;
        }

        public long getTick() {
            return tick;
        }

        /** @return the profile, or null while it was not moving by the rules */
        public PhysicsProfile getProfile() {
            return profile;
        }
    }

    /** One cube of the world's collision boxes, as it was from this tick on. */
    public static final class Blocks {
        private final long tick;
        private final int sectionX;
        private final int sectionY;
        private final int sectionZ;
        private final List<Box> boxes;

        Blocks(long tick, int sectionX, int sectionY, int sectionZ, List<Box> boxes) {
            this.tick = tick;
            this.sectionX = sectionX;
            this.sectionY = sectionY;
            this.sectionZ = sectionZ;
            this.boxes = Collections.unmodifiableList(new ArrayList<>(boxes));
        }

        public long getTick() {
            return tick;
        }

        public int getSectionX() {
            return sectionX;
        }

        public int getSectionY() {
            return sectionY;
        }

        public int getSectionZ() {
            return sectionZ;
        }

        public List<Box> getBoxes() {
            return boxes;
        }
    }
}
