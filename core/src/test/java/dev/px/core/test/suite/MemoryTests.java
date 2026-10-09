package dev.px.core.test.suite;

import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.flow.Span;
import dev.px.core.math.Vec3i;
import dev.px.core.memory.Fact;
import dev.px.core.memory.Memory;
import dev.px.core.memory.Recollection;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.RecordingLogger;

import java.util.List;

/**
 * Shared memory: typed facts, one value or one per subject, with ages and
 * expiry in ticks or in wall-clock time.
 *
 * <p>Runs against a memory with a clock the test moves by hand, so an expiry in
 * seconds is checked exactly rather than by sleeping.
 */
public final class MemoryTests {

    private static final Fact<Boolean> UNMINABLE = Fact.of("unminable");
    private static final Fact<String> STASH = Fact.of("stash");

    private MemoryTests() {
    }

    public static void run() {
        Checks.section("Memory");

        final long[] clock = {0L};
        RecordingLogger logger = new RecordingLogger();
        Memory memory = new Memory(new CoreEventBus(logger), () -> clock[0]);

        memory.remember(STASH, "chest at 10 64 3");
        Checks.checkEquals("a fact with one value is recalled", "chest at 10 64 3", memory.recall(STASH));
        memory.remember(STASH, "barrel");
        Checks.checkEquals("and replaced by remembering it again", "barrel", memory.recall(STASH));

        Vec3i stone = Vec3i.of(1, 2, 3);
        memory.remember(UNMINABLE, stone, true, Span.ticks(5));
        Checks.check("a fact about a subject is known for that subject",
                memory.knows(UNMINABLE, stone) && memory.knows(UNMINABLE, Vec3i.of(1, 2, 3)));
        Checks.check("compared by equals, and not for another", !memory.knows(UNMINABLE, Vec3i.of(1, 2, 4)));
        Checks.check("a subject's fact is not the one-value fact", !memory.knows(UNMINABLE));

        for (int i = 0; i < 4; i++) {
            memory.tick();
        }
        Recollection<Boolean> recalled = memory.recollect(UNMINABLE, stone);
        Checks.check("its age is counted in ticks (" + recalled + ")", recalled != null && recalled.getAgeTicks() == 4
                && !recalled.isPermanent());
        memory.tick();
        Checks.check("and it is gone once its expiry has passed", !memory.knows(UNMINABLE, stone)
                && memory.recall(UNMINABLE, stone) == null);

        memory.remember(UNMINABLE, Vec3i.of(9, 9, 9), true, Span.seconds(2));
        clock[0] += 1_999_000_000L;
        Checks.check("an expiry in seconds is measured by the clock", memory.knows(UNMINABLE, Vec3i.of(9, 9, 9)));
        clock[0] += 1_000_000L;
        Checks.check("however few ticks have passed", !memory.knows(UNMINABLE, Vec3i.of(9, 9, 9)));

        memory.remember(UNMINABLE, Vec3i.of(1, 0, 0), true);
        memory.remember(UNMINABLE, Vec3i.of(2, 0, 0), false);
        memory.remember(UNMINABLE, Vec3i.of(3, 0, 0), true, Span.ticks(1));
        memory.tick();
        List<Recollection<Boolean>> all = memory.all(UNMINABLE);
        Checks.check("all lists what is known of a fact, oldest first, leaving out what has expired (" + all + ")",
                all.size() == 2 && all.get(0).getSubject().equals(Vec3i.of(1, 0, 0)) && all.get(1).getValue() == Boolean.FALSE);
        Checks.check("forgetting a subject reports it", memory.forget(UNMINABLE, Vec3i.of(1, 0, 0))
                && !memory.forget(UNMINABLE, Vec3i.of(1, 0, 0)));
        Checks.checkEquals("forgetting a fact forgets every subject", 2, memory.forget(UNMINABLE) + memory.forget(STASH));

        memory.remember(UNMINABLE, Vec3i.of(5, 0, 0), true, Span.ticks(2));
        memory.remember(STASH, "kept");
        for (int i = 0; i < 40; i++) {
            memory.tick();
        }
        Checks.checkEquals("expired facts are cleared out as time passes, not only when asked", 1, memory.size());
        Checks.checkThrows("nothing is remembered as null", IllegalArgumentException.class,
                () -> memory.remember(STASH, null));
        memory.clear();
        Checks.checkEquals("and clear forgets everything", 0, memory.size());
    }
}
