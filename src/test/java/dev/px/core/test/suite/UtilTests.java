package dev.px.core.test.suite;

import dev.px.core.math.Vec2;
import dev.px.core.math.Vec3;
import dev.px.core.render.Color;
import dev.px.core.render.animation.Easing;
import dev.px.core.test.harness.Checks;
import dev.px.core.util.collect.CircularDeque;
import dev.px.core.util.collect.CircularQueue;
import dev.px.core.util.collect.ExpiringCache;
import dev.px.core.util.collect.LruCache;
import dev.px.core.util.collect.Pair;
import dev.px.core.util.collect.RollingAverage;
import dev.px.core.util.collect.Trie;
import dev.px.core.util.collect.Triplet;
import dev.px.core.util.collect.WeightedList;
import dev.px.core.util.math.MovementMath;
import dev.px.core.util.math.RotationMath;
import dev.px.core.util.net.Http;
import dev.px.core.util.render.ColorUtil;
import dev.px.core.util.render.Gradient;
import dev.px.core.util.text.ChatColor;
import dev.px.core.util.text.TextUtil;
import dev.px.core.util.time.Profiler;
import dev.px.core.util.time.TickTimer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * The {@code util} subpackages, tested as pure logic.
 *
 * <p>No client, no screen, no render backend, and deliberately no network: the
 * one HTTP check here is the argument check that runs before a socket is opened,
 * so the suite still passes on a machine with no connection.
 */
public final class UtilTests {

    private UtilTests() {
    }

    public static void run() {
        collections();
        caches();
        completion();
        weighting();
        movement();
        rotation();
        timing();
        colour();
        text();
    }

    // --------------------------------------------------------- util.collect

    private static void collections() {
        Checks.section("Util: data structures");

        Pair<String, Integer> pair = Pair.of("reach", 3);
        Checks.checkEquals("a pair keeps both values", "reach", pair.getFirst());
        Checks.checkEquals("and is equal by value", Pair.of("reach", 3), pair);
        Checks.checkEquals("swapping reverses it", Pair.of(3, "reach"), pair.swap());
        Checks.checkEquals("withSecond leaves the original alone", (Object) 3, pair.getSecond());
        Checks.checkEquals("mapping the second value", Pair.of("reach", "3"), pair.mapSecond(String::valueOf));

        Triplet<String, Integer, Boolean> triplet = Triplet.of("a", 1, true);
        Checks.checkEquals("a triplet keeps three", (Object) true, triplet.getThird());
        Checks.checkEquals("and drops to a pair", Pair.of("a", 1), triplet.toPair());

        // ---- circular queue ---------------------------------------------------
        CircularQueue<Integer> queue = CircularQueue.of(3);
        queue.addAll(1, 2, 3);
        Checks.check("a queue fills to capacity", queue.isFull());
        Checks.checkEquals("oldest first", (Object) 1, queue.get(0));
        Checks.checkEquals("newest last", (Object) 3, queue.peekLast());

        Integer evicted = queue.add(4);
        Checks.checkEquals("adding past capacity evicts the oldest", (Object) 1, evicted);
        Checks.checkEquals("and never grows", (Object) 3, (Object) queue.size());
        Checks.checkEquals("the window slid", Arrays.asList(2, 3, 4), queue.toList());
        Checks.checkEquals("iteration matches the window", Arrays.asList(2, 3, 4),
                queue.stream().collect(Collectors.toList()));

        Checks.checkEquals("polling takes from the front", (Object) 2, queue.poll());
        Checks.checkEquals("leaving the rest in order", Arrays.asList(3, 4), queue.toList());
        Checks.check("a partial queue is not full", !queue.isFull());
        Checks.checkThrows("reading past the end throws", IndexOutOfBoundsException.class,
                () -> queue.get(5));
        Checks.checkThrows("a zero capacity is rejected", IllegalArgumentException.class,
                () -> CircularQueue.of(0));

        queue.clear();
        Checks.check("clearing empties it", queue.isEmpty() && queue.peek() == null);

        // ---- circular deque ---------------------------------------------------
        CircularDeque<String> deque = CircularDeque.of(3);
        deque.addLast("b");
        deque.addLast("c");
        deque.addFirst("a");
        Checks.checkEquals("a deque writes both ends", Arrays.asList("a", "b", "c"), deque.toList());
        Checks.checkEquals("prepending to a full deque drops the newest", "c", deque.addFirst("z"));
        Checks.checkEquals("and the rest shifted along", Arrays.asList("z", "a", "b"), deque.toList());
        Checks.checkEquals("polling the back", "b", deque.pollLast());
        Checks.checkEquals("polling the front", "z", deque.pollFirst());
        Checks.checkEquals("leaving one", (Object) 1, (Object) deque.size());

        // ---- rolling average --------------------------------------------------
        RollingAverage average = RollingAverage.of(3);
        average.push(10d);
        average.push(20d);
        Checks.checkEquals("a partial window averages what it has", 15f, (float) average.average());
        Checks.check("and knows it is partial", !average.isFull());
        average.push(30d);
        average.push(60d);
        Checks.check("a full window stays full", average.isFull());
        Checks.checkEquals("the oldest sample left the window", 36.67f, (float) average.average());
        Checks.checkEquals("min ignores it too", 20f, (float) average.min());
        Checks.checkEquals("as does max", 60f, (float) average.max());
        Checks.checkEquals("last is the newest", 60f, (float) average.last());
        Checks.checkEquals("an empty average is zero, not NaN", 0f,
                (float) RollingAverage.of(2).average());
    }

    private static void caches() {
        Checks.section("Util: caches");

        // ---- least recently used ----------------------------------------------
        List<String> evicted = new ArrayList<>();
        LruCache<String, Integer> cache = LruCache.<String, Integer>of(3)
                .onEvict((key, value) -> evicted.add(key));
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        Checks.checkEquals("a cache fills to capacity", 3, cache.size());

        // Touching "a" must save it: eviction is by use, not by age.
        Checks.checkEquals("reading an entry returns it", (Object) 1, cache.get("a"));
        cache.put("d", 4);
        Checks.checkEquals("the least recently used entry is evicted", Arrays.asList("b"), evicted);
        Checks.check("the entry that was read survives", cache.contains("a"));
        Checks.check("and the cache stays at its bound", cache.size() == 3);
        Checks.checkEquals("a miss is null, not an error", null, cache.get("b"));

        int[] computed = { 0 };
        cache.computeIfAbsent("e", key -> {
            computed[0]++;
            return 5;
        });
        cache.computeIfAbsent("e", key -> {
            computed[0]++;
            return 5;
        });
        Checks.checkEquals("computing an absent value happens once", 1, computed[0]);
        Checks.check("removing does not fire the eviction hook",
                !evicted.contains("e") && cache.remove("e") != null);
        Checks.checkThrows("a zero capacity is rejected", IllegalArgumentException.class,
                () -> LruCache.of(0));

        // ---- expiring ----------------------------------------------------------
        // A hand-cranked clock, so expiry is tested exactly rather than by sleeping.
        AtomicLong now = new AtomicLong();
        ExpiringCache<String, String> fresh = ExpiringCache.of(1_000L, now::get);
        fresh.put("track", "something");
        Checks.checkEquals("a fresh entry reads back", "something", fresh.get("track"));
        Checks.checkEquals("and reports its remaining life", 1000f, (float) fresh.remainingMillis("track"));

        now.set(999_000_000L);
        Checks.checkEquals("it survives right up to its lifetime", "something", fresh.get("track"));
        now.set(1_000_000_000L);
        Checks.checkEquals("and is gone the moment it expires", null, fresh.get("track"));
        Checks.checkEquals("expired entries are dropped, not just hidden", 0, fresh.size());
        Checks.checkEquals("a dead entry has no life left", 0f, (float) fresh.remainingMillis("track"));

        // Lazily: an entry nobody reads is still there until something sweeps.
        now.set(0L);
        fresh.put("a", "1");
        fresh.put("b", "2");
        now.set(2_000_000_000L);
        Checks.checkEquals("stale entries linger until looked at", 2, fresh.size());
        Checks.checkEquals("purging sweeps them", 2, fresh.purge());
        Checks.check("leaving the cache empty", fresh.isEmpty());

        int[] fetches = { 0 };
        now.set(0L);
        ExpiringCache<String, String> lookups = ExpiringCache.of(500L, now::get);
        lookups.computeIfAbsent("id", key -> {
            fetches[0]++;
            return "value";
        });
        lookups.computeIfAbsent("id", key -> {
            fetches[0]++;
            return "value";
        });
        Checks.checkEquals("a live entry is not recomputed", 1, fetches[0]);
        now.set(600_000_000L);
        lookups.computeIfAbsent("id", key -> {
            fetches[0]++;
            return "value";
        });
        Checks.checkEquals("a stale one is", 2, fetches[0]);
    }

    private static void completion() {
        Checks.section("Util: prefix tree");

        Trie trie = Trie.of(Arrays.asList("KillAura", "Killer", "Fly", "Friend", "Friends", "Sprint"));
        Checks.checkEquals("every word is stored", 6, trie.size());
        Checks.check("words are found regardless of case", trie.contains("killaura"));
        Checks.check("but only whole words", !trie.contains("kill"));
        Checks.checkEquals("the stored spelling comes back", "KillAura", trie.get("KILLAURA"));

        Checks.checkEquals("a prefix finds its words", Arrays.asList("Friend", "Friends"),
                trie.startingWith("fri"));
        Checks.checkEquals("results are alphabetical", Arrays.asList("KillAura", "Killer"),
                trie.startingWith("Kill"));
        Checks.checkEquals("an empty prefix offers everything", 6, trie.startingWith("").size());
        Checks.checkEquals("a prefix matching nothing offers nothing", 0,
                trie.startingWith("zzz").size());
        Checks.checkEquals("a limit caps the suggestions", 1, trie.startingWith("", 1).size());

        // Completing to the first match would be a guess; completing to the shared
        // prefix is not, and leaves the user one keystroke from either.
        Checks.checkEquals("tab fills in as far as the matches agree", "Friend",
                trie.longestCommonPrefix("fri"));
        Checks.checkEquals("and stops where they diverge", "Kill",
                trie.longestCommonPrefix("k"));
        Checks.checkEquals("an unmatched prefix is returned unchanged", "zz",
                trie.longestCommonPrefix("zz"));

        Checks.check("a word can be removed", trie.remove("Friends"));
        Checks.checkEquals("leaving the rest of its branch", Arrays.asList("Friend"),
                trie.startingWith("fri"));
        Checks.check("removing it twice does nothing", !trie.remove("Friends"));
        Checks.checkEquals("and the count follows", 5, trie.size());
        // The branch for a removed word must be unlinked, not left as dead shape.
        trie.remove("Friend");
        Checks.checkEquals("an emptied branch leaves the rest of the tree intact",
                Arrays.asList("Fly"), trie.startingWith("f"));
        trie.clear();
        Checks.check("clearing empties it", trie.isEmpty());
    }

    private static void weighting() {
        Checks.section("Util: weighted selection");

        WeightedList<String> delays = WeightedList.<String>of()
                .add("fast", 1d)
                .add("usual", 8d)
                .add("slow", 1d);

        Checks.checkEquals("weights total", 10f, (float) delays.totalWeight());
        Checks.checkEquals("a weight is a share of that total", 0.8f, (float) delays.chanceOf("usual"));
        Checks.checkEquals("something never added has no chance", 0f, (float) delays.chanceOf("absent"));
        Checks.checkEquals("the distribution can be sampled by position", "fast", delays.at(0d));
        Checks.checkEquals("at either end", "slow", delays.at(1d));
        Checks.checkEquals("an empty list picks nothing", null, WeightedList.of().pick());
        Checks.checkThrows("a zero weight is a bug, not a request", IllegalArgumentException.class,
                () -> WeightedList.of().add("never", 0d));

        // Seeded, so the check is on the distribution rather than on luck.
        Random random = new Random(20260916L);
        int usual = 0;
        for (int i = 0; i < 10_000; i++) {
            if ("usual".equals(delays.pick(random))) {
                usual++;
            }
        }
        Checks.check("draws follow the weights over 10,000 picks (" + usual + ")",
                usual > 7600 && usual < 8400);
    }

    // ------------------------------------------------------------ util.math

    private static void movement() {
        Checks.section("Util: movement maths");

        double speed = MovementMath.WALK_SPEED;

        Vec3 forward = MovementMath.velocity(0f, 1d, 0d, speed);
        Checks.checkEquals("walking forward at yaw 0 moves along +Z", (float) speed,
                (float) forward.getZ());
        Checks.checkEquals("and not sideways", 0f, (float) forward.getX());

        Vec3 diagonal = MovementMath.velocity(0f, 1d, 1d, speed);
        Checks.checkEquals("a diagonal is normalised, not 41% faster", (float) speed,
                (float) MovementMath.speed(diagonal));
        Checks.check("and it really is diagonal",
                diagonal.getX() > 0d && diagonal.getZ() > 0d);
        Checks.checkEquals("no input means no motion", Vec3.ZERO,
                MovementMath.velocity(90f, 0d, 0d, speed));

        // The travel direction and the motion vector must agree, or a speed module
        // accelerates along one heading while the player walks along another.
        for (int[] input : new int[][] { { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 }, { -1, 0 }, { -1, -1 } }) {
            Vec3 motion = MovementMath.velocity(30f, input[0], input[1], speed);
            float travelYaw = MovementMath.movementYaw(30f, input[0], input[1]);
            Vec3 heading = MovementMath.forward(travelYaw, speed);
            Checks.check("travel yaw matches the motion for input "
                            + Arrays.toString(input),
                    Checks.eq((float) motion.getX(), (float) heading.getX())
                            && Checks.eq((float) motion.getZ(), (float) heading.getZ()));
        }

        Checks.checkEquals("standing still reports the facing yaw", 42f,
                MovementMath.movementYaw(42f, 0d, 0d));
        Checks.check("a held key counts as moving", MovementMath.isMoving(0d, -1d));

        Checks.checkEquals("speed converts to blocks per second", (float) (speed * 20d),
                (float) MovementMath.blocksPerSecond(0d, speed));
        Checks.checkEquals("Speed II is 40% faster", (float) (speed * 1.4d),
                (float) MovementMath.walkSpeed(2, 0));
        Checks.checkEquals("Slowness I is 15% slower", (float) (speed * 0.85d),
                (float) MovementMath.walkSpeed(0, 1));
        Checks.check("effects never drive speed negative", MovementMath.walkSpeed(0, 99) >= 0d);
        Checks.checkEquals("Jump Boost I raises the jump", 0.52f,
                (float) MovementMath.jumpVelocity(1));

        Checks.checkEquals("the first tick of a fall", -0.0784f, (float) MovementMath.fall(0d));
        Checks.check("falling accelerates", MovementMath.fallDistance(0d, 10) < MovementMath.fallDistance(0d, 5));
        Checks.check("terminal-ish speed takes a while", MovementMath.ticksToFallSpeed(1d) > 10);
        Vec3 predicted = MovementMath.predict(Vec3.ZERO, Vec3.of(0.2d, 0d, 0d), 5);
        Checks.checkEquals("prediction carries horizontal motion", 1f, (float) predicted.getX());
        Checks.check("and drops vertically", predicted.getY() < 0d);
    }

    private static void rotation() {
        Checks.section("Util: rotation maths");

        Checks.checkEquals("pitch clamps to straight down", 90f, RotationMath.clampPitch(140f));
        Vec2 normalized = RotationMath.normalize(Vec2.rotation(370f, -120f));
        Checks.checkEquals("yaw wraps", 10f, normalized.getYaw());
        Checks.checkEquals("pitch clamps", -90f, normalized.getPitch());

        Checks.checkEquals("the difference across the wrap is small", 2f,
                RotationMath.difference(Vec2.rotation(179f, 0f), Vec2.rotation(-179f, 0f)));
        Checks.checkEquals("and combines both axes", 5f,
                RotationMath.difference(Vec2.rotation(0f, 0f), Vec2.rotation(3f, 4f)));

        Vec3 look = RotationMath.direction(0f, 0f);
        Checks.checkEquals("yaw 0 looks along +Z", 1f, (float) look.getZ());
        Checks.checkEquals("looking down is -Y", -1f,
                (float) RotationMath.direction(0f, 90f).getY());
        Checks.checkEquals("a look vector is a unit vector", 1f,
                (float) RotationMath.direction(37f, -22f).length());

        // The pairing that has to hold: point at something, then look at it.
        Vec3 eye = Vec3.of(0d, 0d, 0d);
        Vec3 target = Vec3.of(5d, 2d, -3d);
        Vec2 toTarget = eye.rotationTo(target);
        Checks.checkEquals("aiming at a point leaves no angle to it", 0f,
                RotationMath.angleTo(toTarget, eye, target));
        Checks.check("and it is inside any sane FOV",
                RotationMath.isWithinFov(toTarget, eye, target, 30f));
        Checks.check("something behind is not",
                !RotationMath.isWithinFov(toTarget, eye, target.scale(-1d), 90f));

        Vec2 stepped = RotationMath.step(Vec2.rotation(0f, 0f), Vec2.rotation(90f, 45f), 30f);
        Checks.checkEquals("a step is limited on yaw", 30f, stepped.getYaw());
        Checks.checkEquals("and on pitch", 30f, stepped.getPitch());
        Checks.checkEquals("turning takes the short way round", 175f,
                RotationMath.step(Vec2.rotation(170f, 0f), Vec2.rotation(-170f, 0f), 5f).getYaw());

        float step = RotationMath.sensitivityStep(0.5f);
        Vec2 snapped = RotationMath.snapToSensitivity(
                Vec2.rotation(0f, 0f), Vec2.rotation(12.3456f, -4.321f), 0.5f);
        Checks.check("a snapped rotation is a whole number of mouse steps",
                Checks.eq(0f, (float) (Math.abs(snapped.getYaw() / step)
                        - Math.round(Math.abs(snapped.getYaw() / step)))));
        Checks.check("and lands within one step of the target",
                RotationMath.difference(snapped, Vec2.rotation(12.3456f, -4.321f)) <= step * 1.5f);
    }

    // ------------------------------------------------------------ util.time

    private static void timing() {
        Checks.section("Util: tick timing and profiling");

        TickTimer timer = TickTimer.every(3);
        Checks.check("a fresh timer has not fired", !timer.tick() && !timer.tick());
        Checks.check("it fires on the third tick", timer.tick());
        Checks.checkEquals("and resets itself", (Object) 0, (Object) timer.getTicks());
        Checks.check("it does not fire again immediately", !timer.tick());
        Checks.checkEquals("progress reflects the count", 0.333f, timer.progress());
        timer.expire();
        Checks.check("expiring makes the next tick fire", timer.tick());

        TickTimer counter = TickTimer.counting();
        for (int i = 0; i < 5; i++) {
            Checks.check("a counting timer never fires on its own", !counter.tick());
        }
        Checks.check("but reports what it counted", counter.elapsed(5));
        Checks.check("and consumes on demand", counter.tryConsume(5));
        Checks.check("leaving nothing behind", !counter.elapsed(1));
        Checks.checkThrows("a zero period is rejected", IllegalArgumentException.class,
                () -> TickTimer.every(0));

        Profiler profiler = Profiler.of(4);
        profiler.measure("work", () -> {
            long spin = 0L;
            for (int i = 0; i < 200_000; i++) {
                spin += i;
            }
            if (spin < 0L) {
                throw new IllegalStateException("unreachable, keeps the loop alive");
            }
        });
        Checks.check("a measured section records a duration", profiler.averageMillis("work") > 0d);
        Checks.check("and appears in the report", profiler.report().contains("work"));
        Checks.checkEquals("an unmeasured section is zero, not an error", 0f,
                (float) profiler.averageMillis("nothing"));
        Checks.checkEquals("ending a section that never began is harmless", 0f,
                (float) profiler.end("nothing"));
        Checks.checkSurvives("a throwing body still records its time", () -> {
            try {
                profiler.measure("boom", () -> {
                    throw new IllegalStateException("expected");
                });
            } catch (IllegalStateException expected) {
                // The point is that the section closed rather than leaking.
            }
        });
        Checks.check("the throwing section was still measured", profiler.sections().contains("boom"));
        profiler.clear();
        Checks.check("clearing forgets everything", profiler.sections().isEmpty());
    }

    // ---------------------------------------------------------- util.render

    private static void colour() {
        Checks.section("Util: colour and gradients");

        Checks.check("white is brighter than black",
                ColorUtil.luminance(Color.WHITE) > ColorUtil.luminance(Color.BLACK));
        Checks.check("green reads brighter than blue at the same value",
                ColorUtil.luminance(Color.rgb(0x00FF00)) > ColorUtil.luminance(Color.rgb(0x0000FF)));
        Checks.checkEquals("dark backgrounds take white text", Color.WHITE,
                ColorUtil.readableOn(Color.rgb(0x101010)));
        Checks.checkEquals("light ones take black", Color.BLACK,
                ColorUtil.readableOn(Color.rgb(0xF0F0F0)));

        Color original = Color.of(64, 160, 210, 200);
        float[] hsb = ColorUtil.toHsb(original);
        Color roundTripped = Color.hsb(hsb[0], hsb[1], hsb[2], original.alphaF());
        Checks.checkEquals("a colour survives an HSB round trip", original, roundTripped);
        Checks.checkEquals("shifting a full turn is a no-op", original,
                ColorUtil.shiftHue(original, 1f));
        Checks.checkEquals("desaturating fully leaves a grey",
                (Object) true, (Object) (ColorUtil.saturation(ColorUtil.withSaturation(original, 0f)) == 0f));
        Color grey = ColorUtil.greyscale(Color.rgb(0x00FF00));
        Checks.checkEquals("greyscale flattens the channels", grey.getRed(), grey.getBlue());

        Checks.check("full health is green-ish",
                ColorUtil.hue(ColorUtil.health(20d, 20d)) > 0.25f);
        Checks.check("empty health is red",
                ColorUtil.hue(ColorUtil.health(0d, 20d)) < 0.05f);
        Checks.checkSurvives("a zero maximum does not divide by zero",
                () -> ColorUtil.health(5d, 0d));

        Checks.checkEquals("RGBA packing round-trips", original, ColorUtil.fromRGBA(ColorUtil.toRGBA(original)));
        Checks.checkEquals("and is not the ARGB layout",
                (Object) false, (Object) (ColorUtil.toRGBA(original) == original.toARGB()));
        Checks.checkEquals("averaging two colours meets in the middle", Color.of(128, 128, 128),
                ColorUtil.average(Color.of(0, 0, 0), Color.of(255, 255, 255)));
        Checks.checkEquals("averaging nothing is transparent", Color.TRANSPARENT, ColorUtil.average());

        // A zero period takes the deterministic branch, so this never reads the clock.
        Checks.checkEquals("a rainbow at offset 0 starts red", Color.rgb(0xFF0000),
                ColorUtil.rainbow(0L, 0f, 1f, 1f));
        Checks.checkEquals("a zero-period pulse holds still", Color.RED,
                ColorUtil.pulse(Color.RED, Color.BLUE, 0L));

        Gradient gradient = Gradient.of(Color.BLACK, Color.WHITE);
        Checks.checkEquals("a gradient starts at its first stop", Color.BLACK, gradient.at(0f));
        Checks.checkEquals("and ends at its last", Color.WHITE, gradient.at(1f));
        Checks.checkEquals("the midpoint is halfway", Color.of(128, 128, 128), gradient.at(0.5f));
        Checks.checkEquals("progress is clamped, not wrapped", Color.WHITE, gradient.at(9f));

        Gradient three = Gradient.of(Color.RED, Color.GREEN, Color.BLUE);
        Checks.checkEquals("a middle stop sits at its own position", Color.GREEN, three.at(0.5f));
        Checks.checkEquals("reversing swaps the ends", Color.BLUE, three.reversed().at(0f));
        Checks.checkEquals("sampling hits both ends", Color.BLUE, three.sample(5)[4]);
        Checks.checkEquals("wrapping returns to the first stop", Color.RED, three.atWrapping(1f));
        Checks.checkEquals("fading applies to every stop", 128, three.withAlpha(0.5f).getStart().getAlpha());
        Checks.checkThrows("one stop is not a gradient", IllegalArgumentException.class,
                () -> Gradient.of(Color.RED));
    }

    // ------------------------------------------------------------ util.text

    private static void text() {
        Checks.section("Util: text");

        Checks.checkEquals("constants become titles", "Some Mode", TextUtil.prettify("SOME_MODE"));
        Checks.checkEquals("an enum too", "Quad In Out",
                TextUtil.prettify(Easing.QUAD_IN_OUT));
        Checks.checkEquals("truncation adds an ellipsis", "abcdefg...", TextUtil.truncate("abcdefghijkl", 10));
        Checks.checkEquals("short text is untouched", "abc", TextUtil.truncate("abc", 10));
        Checks.checkEquals("repeat fills", "---", TextUtil.repeat('-', 3));
        Checks.checkEquals("a negative count is empty, not an error", "", TextUtil.repeat('-', -1));

        List<String> wrapped = TextUtil.wrap("one two three four", 9d, String::length);
        Checks.checkEquals("wrapping breaks on words", 3, wrapped.size());
        Checks.checkEquals("keeping them whole", "one two", wrapped.get(0));
        Checks.check("an overlong word is not broken",
                TextUtil.wrap("supercalifragilistic", 5d, String::length).get(0).length() > 5);

        Checks.checkEquals("thousands get commas", "1,234,567", TextUtil.commas(1234567L));
        Checks.checkEquals("decimals are locale-independent", "3.14", TextUtil.decimals(3.14159d, 2));
        Checks.checkEquals("bytes scale", "1.0 KB", TextUtil.bytes(1024L));
        Checks.checkEquals("and stay bytes when small", "512 B", TextUtil.bytes(512L));
        Checks.checkEquals("sub-second durations stay in millis", "250ms", TextUtil.duration(250L));
        Checks.checkEquals("minutes and seconds", "1m 05s", TextUtil.duration(65_000L));
        Checks.checkEquals("hours pad the rest", "2h 00m 01s", TextUtil.duration(7_201_000L));
        Checks.checkEquals("potion levels are roman", "IV", TextUtil.roman(4));
        Checks.checkEquals("level zero has no numeral", "", TextUtil.roman(0));

        Checks.check("prefix matching ignores case", TextUtil.startsWithIgnoreCase("KillAura", "kill"));
        Checks.checkEquals("one edit apart", 1, TextUtil.levenshtein("sprint", "sprnt"));
        Checks.checkEquals("a typo finds the command", "sprint",
                TextUtil.closest("sprnt", Arrays.asList("sprint", "fly", "step")));
        Checks.checkEquals("an unrelated word suggests nothing", null,
                TextUtil.closest("watermelon", Arrays.asList("sprint", "fly", "step")));

        // ---- chat colours ------------------------------------------------------
        String coloured = ChatColor.RED + "Kill" + ChatColor.BOLD + "Aura";
        Checks.checkEquals("codes strip out", "KillAura", ChatColor.strip(coloured));
        Checks.check("and are detected", ChatColor.isFormatted(coloured));
        Checks.check("plain text is not", !ChatColor.isFormatted("KillAura"));
        Checks.checkEquals("a stray section sign is left alone", "100§", ChatColor.strip("100§"));
        Checks.checkEquals("ampersands translate", "§cRed", ChatColor.translate("&cRed"));
        Checks.checkEquals("a doubled ampersand escapes", "Tom & Jerry", ChatColor.translate("Tom && Jerry"));
        Checks.checkEquals("a non-code is left alone", "50% & rising",
                ChatColor.translate("50% & rising"));
        Checks.checkEquals("codes look up by character", ChatColor.GOLD, ChatColor.byCode('6'));
        Checks.checkEquals("and case-insensitively", ChatColor.BOLD, ChatColor.byCode('L'));
        Checks.checkEquals("an unknown code is null", null, ChatColor.byCode('z'));
        Checks.check("colours carry a colour", ChatColor.RED.isColor() && !ChatColor.RED.isFormat());
        Checks.check("styles do not", ChatColor.ITALIC.isFormat() && ChatColor.ITALIC.getColor() == null);
        Checks.checkEquals("an arbitrary colour maps to the nearest code", ChatColor.RED,
                ChatColor.nearest(Color.rgb(0xFF4444)));
        Checks.checkEquals("the last colour wins", ChatColor.GREEN.getColor(),
                ChatColor.lastColor("§cred then §agreen"));
        Checks.checkEquals("a reset clears it", null, ChatColor.lastColor("§cred§r"));

        // The suite never touches the network; this is the check that runs first.
        Checks.checkThrows("a blank URL is rejected before any socket opens",
                IllegalArgumentException.class, () -> {
                    try {
                        Http.get("");
                    } catch (IOException impossible) {
                        throw new IllegalStateException(impossible);
                    }
                });
    }
}
