package dev.px.core.test.suite;

import dev.px.core.Core;
import dev.px.core.event.Priority;
import dev.px.core.event.Stage;
import dev.px.core.event.Subscription;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.event.impl.PacketEvent;
import dev.px.core.event.impl.TickEvent;
import dev.px.core.event.impl.WorldEvent;
import dev.px.core.movement.timeline.Timeline;
import dev.px.core.network.NetworkService;
import dev.px.core.network.PacketListener;
import dev.px.core.network.anticheat.AntiCheatChangeEvent;
import dev.px.core.network.anticheat.AntiCheatService;
import dev.px.core.network.anticheat.AntiCheatSignature;
import dev.px.core.network.anticheat.AntiCheatSignatures;
import dev.px.core.network.anticheat.Confidence;
import dev.px.core.network.anticheat.Detection;
import dev.px.core.network.anticheat.TransactionPattern;
import dev.px.core.network.anticheat.TransactionTracker;
import dev.px.core.network.lag.LagService;
import dev.px.core.network.lag.LagSpikeEvent;
import dev.px.core.network.packet.ClassPacketDescriber;
import dev.px.core.network.packet.PacketDescriber;
import dev.px.core.network.packet.PacketDescription;
import dev.px.core.network.packet.PacketFields;
import dev.px.core.network.packet.PacketKind;
import dev.px.core.network.server.ServerBrand;
import dev.px.core.network.server.ServerChangeEvent;
import dev.px.core.network.server.ServerInfo;
import dev.px.core.network.server.ServerService;
import dev.px.core.network.server.ServerSoftware;
import dev.px.core.network.tps.TpsService;
import dev.px.core.network.tps.TpsTracker;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.FakePlatform;
import dev.px.core.test.harness.RecordingLogger;
import dev.px.core.test.harness.TestClient;
import dev.px.core.util.time.RateMeter;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The network services: TPS, lag, server and anticheat detection, and the hub
 * they read packets through.
 *
 * <p>Every check but the last runs on a bare bus with a hand-driven clock and
 * fake packets that are plain Java objects, which is the proof that none of this
 * needs a game: the adapter's describer is the only thing that knows what a
 * packet is, and here that describer is twenty lines of test code.
 */
public final class NetworkTests {

    private NetworkTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Network");

        descriptions();
        classDescriber();
        rateMeter();
        tpsTracker();
        hub();
        tpsService();
        lag();
        serverInfo();
        brands();
        serverService();
        transactions();
        anticheat();
        bootedClient(client);
    }

    // -------------------------------------------------------- descriptions

    private static void descriptions() {
        PacketDescription time = PacketDescription.timeUpdate("S03", 1200L);
        Checks.checkEquals("a time update is classified", PacketKind.TIME_UPDATE, time.getKind());
        Checks.checkEquals("and carries the world age", Long.valueOf(1200L), time.getLong(PacketFields.WORLD_AGE));

        PacketDescription transaction = PacketDescription.transaction("S32", -7);
        Checks.checkEquals("a transaction carries its id", Long.valueOf(-7L), transaction.getLong(PacketFields.ID));
        Checks.checkEquals("and a correlation key for the timeline", "transaction:-7", transaction.getCorrelationKey());
        Checks.checkEquals("a keep-alive is its own kind", PacketKind.KEEP_ALIVE,
                PacketDescription.keepAlive("S00", 5).getKind());

        PacketDescription brand = PacketDescription.brand("S3F", "Paper");
        Checks.check("a brand is a payload on the brand channel", brand.isPayloadOn(PacketFields.BRAND_CHANNEL));
        Checks.checkEquals("with the brand in it", "Paper", brand.getString(PacketFields.BRAND));

        PacketDescription channels = PacketDescription.channels("S3F", Arrays.asList("a:one", "", "b:two"));
        Checks.checkEquals("channels are joined, empties dropped", "a:one,b:two",
                channels.getString(PacketFields.CHANNELS));
        Checks.checkEquals("latency is read back as a long", Long.valueOf(48L),
                PacketDescription.of("S38", PacketKind.OTHER).withLatency(48).getLong(PacketFields.LATENCY));
        Checks.checkEquals("a missing field reads null", null, time.getLong(PacketFields.ID));
        Checks.checkEquals("a field of the wrong type reads null", null, time.getString(PacketFields.WORLD_AGE));
    }

    private static void classDescriber() {
        ClassPacketDescriber describer = PacketDescriber.byClass()
                .on(TimeUpdate.class, p -> PacketDescription.timeUpdate("TimeUpdate", p.age))
                .on(Marker.class, "Marker", PacketKind.ACTION)
                .on(Base.class, p -> PacketDescription.of("Base", PacketKind.MOVEMENT))
                .on(Chunk.class, p -> null)
                .build();

        Checks.checkEquals("an exact class uses its rule", Long.valueOf(40L),
                describer.describe(new TimeUpdate(40L)).getLong(PacketFields.WORLD_AGE));
        Checks.checkEquals("a subclass uses its parent's", "Base", describer.describe(new Derived()).getType());
        Checks.checkEquals("an interface rule applies to implementers", "Marker",
                describer.describe(new Marked()).getType());
        Checks.checkEquals("a rule returning null falls back", "Chunk", describer.describe(new Chunk()).getType());
        Checks.checkEquals("no rule at all falls back to the class name", PacketKind.OTHER,
                describer.describe(new Brand("x")).getKind());
        Checks.checkEquals("and the second lookup of a class agrees with the first", "Base",
                describer.describe(new Derived()).getType());
        Checks.checkThrows("a class cannot have two rules", IllegalArgumentException.class, () ->
                PacketDescriber.byClass().on(Chunk.class, "a", PacketKind.OTHER).on(Chunk.class, "b", PacketKind.OTHER));
        Checks.checkEquals("the fallback is replaceable", "fallback", PacketDescriber.byClass()
                .otherwise(packet -> PacketDescription.of("fallback", PacketKind.OTHER))
                .build().describe(new Chunk()).getType());
    }

    private static void rateMeter() {
        RateMeter meter = RateMeter.perSecond();
        for (int i = 0; i < 100; i++) {
            meter.record(ms(i * 10L));
        }
        Checks.checkEquals("a hundred events in a second is a hundred a second", 100f, (float) meter.rate(ms(999L)), 1f);
        Checks.checkEquals("half a second later, half have left the window", 50f, (float) meter.rate(ms(1499L)), 6f);
        Checks.checkEquals("a second of silence empties it", 0f, (float) meter.rate(ms(2100L)));
        meter.record(ms(10_000L), 5);
        Checks.checkEquals("a jump far ahead starts clean", 5L, meter.count(ms(10_000L)));

        RateMeter wide = RateMeter.over(5000L, 10);
        for (int i = 0; i < 50; i++) {
            wide.record(ms(i * 100L));
        }
        Checks.checkEquals("a five-second window averages over five seconds", 10f, (float) wide.rate(ms(4999L)), 0.5f);
    }

    private static void tpsTracker() {
        TpsTracker steady = new TpsTracker();
        Checks.check("no estimate before two updates", !steady.hasEstimate());
        Checks.checkEquals("and a healthy server is assumed", 20f, (float) steady.getTps(0L));
        feed(steady, 0L, 1000L, 20L, 12);
        Checks.checkEquals("an update a second is 20 TPS", 20f, (float) steady.getTps(ms(11_000L)));

        TpsTracker slow = new TpsTracker();
        feed(slow, 0L, 2000L, 20L, 12);
        Checks.checkEquals("an update every two seconds is 10 TPS", 10f, (float) slow.getTps(ms(22_000L)));

        TpsTracker bunched = new TpsTracker();
        bunched.update(0L, 0L);
        bunched.update(ms(1900L), 20L);
        bunched.update(ms(2000L), 40L);
        Checks.checkEquals("updates the network bunched still add up to 20", 20f, (float) bunched.getTps(ms(2000L)));
        Checks.checkEquals("though the last one alone looks fast, it is capped", 20f, (float) bunched.getLastSample());

        TpsTracker stalled = new TpsTracker();
        feed(stalled, 0L, 1000L, 20L, 10);
        Checks.checkEquals("an overdue update pulls the estimate down before it arrives", 20f / 3f,
                (float) stalled.getTps(ms(9000L + 3000L)), 0.01f);
        Checks.checkEquals("time since the last update is reported", 3000L, stalled.getMillisSinceUpdate(ms(12_000L)));

        TpsTracker world = new TpsTracker();
        feed(world, 0L, 1000L, 20L, 5);
        world.update(ms(5000L), 5_000_000L);
        Checks.check("a leap in world age re-baselines instead of reporting a spike", !world.hasEstimate());
        world.update(ms(6000L), 5_000_020L);
        Checks.checkEquals("and measures again from there", 20f, (float) world.getTps(ms(6000L)));
        world.update(ms(7000L), 10L);
        Checks.check("an age that goes backwards also re-baselines", !world.hasEstimate());

        TpsTracker unknown = new TpsTracker();
        feed(unknown, 0L, 1250L, -1L, 5);
        Checks.checkEquals("with no world age, twenty ticks an update is assumed", 16f, (float) unknown.getTps(ms(5000L)));

        TpsTracker fast = new TpsTracker();
        fast.setTargetTps(40d);
        feed(fast, 0L, 1000L, 40L, 5);
        Checks.checkEquals("a server set to 40 TPS can report 40", 40f, (float) fast.getTps(ms(4000L)));
        Checks.checkThrows("a target of zero is refused", IllegalArgumentException.class, () -> fast.setTargetTps(0d));
    }

    // ----------------------------------------------------------------- hub

    private static void hub() {
        Rig rig = new Rig();
        List<String> heard = new ArrayList<>();
        rig.network.addListener(new PacketListener() {
            @Override
            public void onInbound(PacketDescription packet, long nanos) {
                heard.add("in:" + packet.getType());
            }

            @Override
            public void onOutbound(PacketDescription packet, long nanos) {
                heard.add("out:" + packet.getType());
            }
        });

        Object teleport = new Chunk();
        rig.bus.post(PacketEvent.received(teleport));
        rig.bus.post(PacketEvent.applied(teleport));
        Checks.checkEquals("an inbound packet posted twice is heard once", Arrays.asList("in:Chunk"), heard);

        heard.clear();
        rig.bus.on(PacketEvent.class, Priority.HIGHEST, event -> {
            if (event.getPacket() instanceof Marked) {
                event.setCancelled(true);
            }
        });
        rig.bus.post(PacketEvent.sent(new Marked()));
        rig.bus.post(PacketEvent.sent(new Chunk()));
        rig.bus.post(PacketEvent.received(new Marked()));
        Checks.checkEquals("a cancelled send was never sent; a cancelled receive still arrived",
                Arrays.asList("out:Chunk", "in:Marker"), heard);

        Rig appliedOnly = new Rig();
        AtomicInteger arrivals = new AtomicInteger();
        appliedOnly.network.addListener(new PacketListener() {
            @Override
            public void onInbound(PacketDescription packet, long nanos) {
                arrivals.incrementAndGet();
            }
        });
        appliedOnly.bus.post(PacketEvent.applied(new Chunk()));
        appliedOnly.bus.post(PacketEvent.applied(new Chunk()));
        Checks.checkEquals("an adapter that only posts APPLIED is still heard", 2, arrivals.get());

        Rig broken = new Rig();
        broken.network.setDescriber(packet -> {
            throw new IllegalStateException("describer bug");
        });
        Checks.checkEquals("a throwing describer falls back to the class name", "Chunk",
                broken.network.describe(new Chunk()).getType());
        broken.network.describe(new Chunk());
        Checks.checkEquals("and is logged once, not per packet", 1, broken.logger.errorCount());
        broken.network.setDescriber(null);
        Checks.check("removing the describer is allowed", !broken.network.hasDescriber());
        Checks.check("and undescribed packets reuse one description per class",
                broken.network.describe(new Chunk()) == broken.network.describe(new Chunk()));

        Rig throwing = new Rig();
        AtomicInteger after = new AtomicInteger();
        throwing.network.addListener(new PacketListener() {
            @Override
            public void onInbound(PacketDescription packet, long nanos) {
                throw new IllegalStateException("listener bug");
            }
        });
        Subscription counting = throwing.network.addListener(new PacketListener() {
            @Override
            public void onInbound(PacketDescription packet, long nanos) {
                after.incrementAndGet();
            }
        });
        throwing.receive(new Chunk());
        throwing.receive(new Chunk());
        Checks.checkEquals("a throwing listener does not stop the next one", 2, after.get());
        Checks.checkEquals("and is logged once", 1, throwing.logger.errorCount());
        counting.close();
        throwing.receive(new Chunk());
        Checks.check("a closed listener hears nothing more", after.get() == 2 && !counting.isActive());
    }

    private static void tpsService() {
        Rig rig = new Rig();
        rig.join("play.example.net");
        for (int i = 0; i <= 10; i++) {
            rig.at(i * 2000L);
            rig.receive(new TimeUpdate(i * 20L));
        }
        Checks.check("time packets described as time updates reach the estimate", rig.tps.hasEstimate());
        Checks.checkEquals("at the rate they arrived", 10f, (float) rig.tps.getTps());
        Checks.checkEquals("a tick is then 100ms", 100f, (float) rig.tps.getTickMillis());

        rig.join("play.example.net");
        Checks.check("a dimension change on the same server keeps it", rig.tps.hasEstimate());
        rig.join("other.example.net");
        Checks.check("a different server starts over", !rig.tps.hasEstimate());
        rig.at(30_000L);
        rig.receive(new TimeUpdate(500L));
        rig.leave();
        Checks.check("so does leaving", !rig.tps.hasEstimate() && rig.tps.getMillisSinceUpdate() == -1L);
    }

    // ----------------------------------------------------------------- lag

    private static void lag() {
        Rig rig = new Rig();
        List<LagSpikeEvent> spikes = new ArrayList<>();
        rig.bus.on(LagSpikeEvent.class, spikes::add);
        rig.join("play.example.net");

        for (int i = 0; i < 50; i++) {
            rig.at(i * 20L);
            rig.receive(new Chunk());
            if (i % 5 == 0) {
                rig.bus.post(PacketEvent.sent(new Chunk()));
            }
        }
        Checks.checkEquals("inbound rate counts arrivals", 50f, (float) rig.lag.getInboundRate(), 3f);
        Checks.checkEquals("outbound rate counts sends", 10f, (float) rig.lag.getOutboundRate(), 2f);
        Checks.checkEquals("the ping is unknown until reported", -1, rig.lag.getPing());

        rig.receive(new PlayerList(48));
        Checks.checkEquals("a described latency is the ping", 48, rig.lag.getPing());
        rig.lag.reportPing(52);
        rig.lag.reportPing(52);
        rig.lag.reportPing(50);
        Checks.checkEquals("and so is a reported one", 50, rig.lag.getPing());
        Checks.checkEquals("the average ignores a repeated report", 50f, (float) rig.lag.getAveragePing());
        Checks.check("jitter is measured", rig.lag.getPingJitter() > 1d);

        rig.at(1000L);
        rig.tick();
        Checks.check("an ordinary gap is not a spike", !rig.lag.isLagging() && spikes.isEmpty());
        rig.at(3000L);
        rig.tick();
        Checks.check("two seconds of silence is", rig.lag.isLagging());
        Checks.check("and is announced once", spikes.size() == 1 && spikes.get(0).isStarted());
        Checks.checkEquals("with how long it has lasted", 2020L, spikes.get(0).getMillis());
        rig.at(3500L);
        rig.tick();
        Checks.checkEquals("the lag keeps growing while silent", 2520L, rig.lag.getLagMillis());
        Checks.checkEquals("without another event", 1, spikes.size());

        rig.at(4000L);
        rig.receive(new Chunk());
        Checks.check("a packet ends it at once", !rig.lag.isLagging());
        rig.at(4050L);
        rig.tick();
        Checks.check("and the end is announced on the next tick", spikes.size() == 2 && spikes.get(1).isEnded());
        Checks.checkEquals("with the whole silence", 3020L, spikes.get(1).getMillis());

        rig.at(10_000L);
        rig.tick();
        rig.leave();
        Checks.check("leaving mid-spike ends it", spikes.size() == 4 && spikes.get(3).isEnded());
        Checks.check("and forgets the connection", rig.lag.getMillisSinceLastPacket() == -1L && rig.lag.getPing() == -1);

        Rig menu = new Rig();
        List<LagSpikeEvent> quiet = new ArrayList<>();
        menu.bus.on(LagSpikeEvent.class, quiet::add);
        menu.receive(new Chunk());
        menu.platform.setInGame(false);
        menu.at(10_000L);
        menu.tick();
        Checks.check("nothing is a spike outside a world", quiet.isEmpty());
        Checks.checkThrows("a threshold of zero is refused", IllegalArgumentException.class,
                () -> menu.lag.setSpikeThresholdMillis(0L));
    }

    // -------------------------------------------------------------- server

    private static void serverInfo() {
        ServerInfo plain = ServerInfo.connected("MC.Hypixel.NET.");
        Checks.checkEquals("hosts are lower-cased, trailing dot dropped", "mc.hypixel.net", plain.getHost());
        Checks.checkEquals("with the default port", 25565, plain.getPort());
        Checks.checkEquals("an explicit port is read", 25577, ServerInfo.connected("play.example.net:25577").getPort());
        Checks.checkEquals("a bad port falls back", 25565, ServerInfo.connected("play.example.net:banana").getPort());
        ServerInfo v6 = ServerInfo.connected("[2001:db8::1]:25570");
        Checks.check("bracketed IPv6 has its port", "2001:db8::1".equals(v6.getHost()) && v6.getPort() == 25570);
        Checks.checkEquals("bare IPv6 is all host", "2001:db8::1", ServerInfo.connected("2001:db8::1").getHost());

        Checks.check("a subdomain is on its domain", plain.isOn("hypixel.net"));
        Checks.check("the domain itself is on it", ServerInfo.connected("hypixel.net").isOn("HYPIXEL.net"));
        Checks.check("a lookalike is not", !ServerInfo.connected("nothypixel.net").isOn("hypixel.net"));
        Checks.check("singleplayer is connected with no host",
                ServerInfo.connected("").isSingleplayer() && !ServerInfo.connected("").isMultiplayer());
        Checks.check("disconnected is neither", !ServerInfo.DISCONNECTED.isConnected()
                && !ServerInfo.DISCONNECTED.isSingleplayer());
    }

    private static void brands() {
        brand("Paper", ServerSoftware.PAPER, null);
        brand("vanilla", ServerSoftware.VANILLA, null);
        brand("Purpur", ServerSoftware.PURPUR, null);
        brand("fml,forge", ServerSoftware.FORGE, null);
        brand("neoforge", ServerSoftware.NEOFORGE, null);
        brand("fabric", ServerSoftware.FABRIC, null);
        brand("BungeeCord (git:BungeeCord-Bootstrap:1.20-R0.1-SNAPSHOT:abc:1) <- Paper", ServerSoftware.PAPER,
                ServerSoftware.BUNGEECORD);
        brand("Waterfall (git:Waterfall-Bootstrap:1.20:abc:500) <- Spigot", ServerSoftware.SPIGOT,
                ServerSoftware.WATERFALL);
        brand("Paper (Velocity)", ServerSoftware.PAPER, ServerSoftware.VELOCITY);
        brand("Velocity", ServerSoftware.UNKNOWN, ServerSoftware.VELOCITY);
        brand("MyNetwork <- Spigot", ServerSoftware.SPIGOT, ServerSoftware.BUNGEECORD);
        brand("Newspaper 2.0", ServerSoftware.UNKNOWN, null);
        brand("Custom (beta)", ServerSoftware.UNKNOWN, null);
        Checks.checkEquals("the raw brand is kept as sent", "Paper (Velocity)",
                ServerBrand.parse("Paper (Velocity)").getRaw());
    }

    private static void brand(String raw, ServerSoftware software, ServerSoftware proxy) {
        ServerBrand parsed = ServerBrand.parse(raw);
        Checks.check("\"" + raw + "\" is " + software + (proxy != null ? " behind " + proxy : ""),
                parsed.getSoftware() == software && parsed.getProxy() == proxy);
    }

    private static void serverService() {
        Rig rig = new Rig();
        List<ServerChangeEvent> changes = new ArrayList<>();
        rig.bus.on(ServerChangeEvent.class, changes::add);

        // 1.20.2+: the brand arrives in the configuration phase, before the world.
        rig.receive(new Brand("Paper (Velocity)"));
        rig.receive(new Register("bungeecord:main", "floodgate:skin"));
        Checks.check("before joining, nothing is connected", !rig.server.isConnected());
        rig.join("Play.Example.net:25565");
        Checks.checkEquals("the brand that arrived first is applied on join", ServerSoftware.PAPER,
                rig.server.getSoftware());
        Checks.check("and so are the channels", rig.server.hasChannel("FLOODGATE:skin"));
        Checks.checkEquals("nothing is announced until the tick", 0, changes.size());
        rig.tick();
        Checks.checkEquals("then it is, once", 1, changes.size());
        Checks.check("as a join with the brand", changes.get(0).isJoin() && changes.get(0).isBrandChanged());

        rig.tick();
        Checks.checkEquals("a quiet tick announces nothing", 1, changes.size());

        rig.join("Play.Example.net:25565");
        rig.tick();
        Checks.checkEquals("a dimension change is not a change of server", 1, changes.size());

        // A proxy moving the player to another backend: same address, new brand.
        rig.server.reportBrand("Purpur (Velocity)");
        rig.server.reportBrand("Purpur (Velocity)");
        rig.tick();
        Checks.checkEquals("a new brand is announced once", 2, changes.size());
        Checks.check("as a brand change, not a join",
                changes.get(1).isBrandChanged() && !changes.get(1).isJoin());
        Checks.checkEquals("previous is what was last announced", ServerSoftware.PAPER,
                changes.get(1).getPrevious().getSoftware());

        rig.leave();
        Checks.check("leaving forgets the server at once", !rig.server.isConnected() && rig.server.getBrand() == null);
        rig.tick();
        Checks.check("and is announced as a leave", changes.size() == 3 && changes.get(2).isLeave());

        rig.join("");
        rig.tick();
        Checks.check("singleplayer is a join too", rig.server.isSingleplayer() && changes.get(3).isJoin());
        Checks.check("with no brand from the last server", rig.server.getBrand() == null);
    }

    // ----------------------------------------------------------- anticheat

    private static void transactions() {
        TransactionTracker countdown = new TransactionTracker();
        for (int i = 0; i < 40; i++) {
            countdown.record(ms(i * 50L), (long) -(i + 1));
        }
        TransactionPattern down = countdown.pattern();
        Checks.checkEquals("a countdown is decrementing", TransactionPattern.Order.DECREMENTING, down.getOrder());
        Checks.checkEquals("below zero", TransactionPattern.Sign.NEGATIVE, down.getSign());
        Checks.checkEquals("at twenty a second", 20f, (float) down.getRatePerSecond());
        Checks.check("spanning its ids", down.getLowestId() == -40L && down.getHighestId() == -1L);

        TransactionTracker wrapping = new TransactionTracker();
        long id = -32_760L;
        for (int i = 0; i < 64; i++) {
            wrapping.record(ms(i * 50L), id);
            id = id == Short.MIN_VALUE ? 0L : id - 1L;
        }
        Checks.checkEquals("one wrap in sixty-four steps is still a countdown", TransactionPattern.Order.DECREMENTING,
                wrapping.pattern().getOrder());

        TransactionTracker clicks = new TransactionTracker();
        long[] ids = {1, 2, 5, 3, 9, 4, 12, 6};
        for (int i = 0; i < ids.length; i++) {
            clicks.record(ms(i * 700L), ids[i]);
        }
        Checks.checkEquals("inventory confirms are irregular", TransactionPattern.Order.IRREGULAR,
                clicks.pattern().getOrder());
        Checks.checkEquals("and positive", TransactionPattern.Sign.POSITIVE, clicks.pattern().getSign());

        TransactionTracker unnumbered = new TransactionTracker();
        unnumbered.record(0L, null);
        unnumbered.record(ms(100L), null);
        Checks.checkEquals("with no ids, order is unknown", TransactionPattern.Order.UNKNOWN,
                unnumbered.pattern().getOrder());
        Checks.checkEquals("but the rate is still measured", 10f, (float) unnumbered.pattern().getRatePerSecond());
        unnumbered.reset();
        Checks.checkEquals("a reset tracker has nothing", TransactionPattern.NONE, unnumbered.pattern());
    }

    private static void anticheat() {
        Rig rig = new Rig();
        List<AntiCheatChangeEvent> changes = new ArrayList<>();
        rig.bus.on(AntiCheatChangeEvent.class, changes::add);

        rig.join("mc.hypixel.net");
        rig.tick();
        Checks.checkEquals("a known server is recognised on join", "Watchdog",
                rig.anticheat.getPrimary().map(Detection::getName).orElse("none"));
        Checks.checkEquals("as known", Confidence.KNOWN, rig.anticheat.getPrimary().get().getConfidence());
        Checks.check("and announced", changes.size() == 1 && changes.get(0).getPrimary().isPresent());

        rig.leave();
        rig.tick();
        Checks.check("leaving forgets it", rig.anticheat.getDetections().isEmpty());
        Checks.checkEquals("and says so", 2, changes.size());

        rig.join("play.example.net");
        rig.tick();
        for (int i = 0; i < 40; i++) {
            rig.at(1000L + i * 50L);
            rig.receive(new Transaction(-(i + 1)));
            rig.receive(new KeepAlive(i));
        }
        rig.anticheat.evaluate();
        Detection generic = rig.anticheat.getPrimary().orElse(null);
        Checks.check("a transaction every tick is a transaction-based anticheat",
                generic != null && generic.is(AntiCheatSignatures.TRANSACTION_BASED));
        Checks.checkEquals("likely, not known", Confidence.LIKELY, generic.getConfidence());
        Checks.check("with the evidence in the reason", generic.getReason().contains("decrementing negative"));
        Checks.checkEquals("keep-alives are not counted as transactions", 40L, rig.anticheat.getTransactions().getCount());

        rig.anticheat.register(evidence -> evidence.getTransactions().getOrder() == TransactionPattern.Order.DECREMENTING
                ? Detection.of("CountdownAC", Confidence.LIKELY, "counts down")
                : null);
        rig.anticheat.evaluate();
        Checks.checkEquals("a named signature outranks the generic one at equal confidence", "CountdownAC",
                rig.anticheat.getPrimary().get().getName());
        Checks.check("both are listed", rig.anticheat.getDetections().size() == 2
                && rig.anticheat.isDetected("countdownac") && rig.anticheat.isDetected(AntiCheatSignatures.TRANSACTION_BASED));

        int before = changes.size();
        rig.anticheat.evaluate();
        Checks.checkEquals("an unchanged result is not announced again", before, changes.size());

        AntiCheatSignature broken = evidence -> {
            throw new IllegalStateException("signature bug");
        };
        rig.anticheat.register(broken);
        rig.anticheat.evaluate();
        rig.anticheat.evaluate();
        Checks.checkEquals("a throwing signature is skipped and logged once", 1, rig.logger.errorCount());
        Checks.check("without costing the others", rig.anticheat.isDetected("CountdownAC"));
        Checks.check("and can be removed", rig.anticheat.unregister(broken));

        Rig slow = new Rig();
        slow.join("play.example.net");
        slow.tick();
        for (int i = 0; i < 12; i++) {
            slow.at(i * 1000L);
            slow.receive(new Transaction(i + 1));
        }
        slow.anticheat.evaluate();
        Checks.check("a transaction a second is nothing", slow.anticheat.getDetections().isEmpty());

        slow.anticheat.clearSignatures();
        slow.anticheat.register(AntiCheatSignature.brandContains("grim", "Grim", Confidence.KNOWN));
        slow.anticheat.register(AntiCheatSignature.channel("vulcan:main", "Vulcan", Confidence.LIKELY));
        slow.server.reportBrand("Paper + GrimAC");
        slow.server.reportChannels(Arrays.asList("vulcan:main"));
        slow.tick();
        Checks.check("brand and channel signatures match",
                slow.anticheat.isDetected("Grim") && slow.anticheat.isDetected("Vulcan"));
        Checks.checkEquals("ranked by confidence", "Grim", slow.anticheat.getPrimary().get().getName());
        Checks.check("clearing signatures removes the defaults", slow.anticheat.getSignatures().size() == 2);
    }

    // -------------------------------------------------------------- client

    private static void bootedClient(TestClient client) {
        Checks.check("the booted client has every network service", Core.network() != null && Core.server() != null
                && Core.tps() != null && Core.lag() != null && Core.anticheat() != null);
        Checks.check("with no describer until the adapter installs one", !Core.network().hasDescriber());

        Core.network().setDescriber(PacketDescriber.byClass()
                .on(Chunk.class, "S21PacketChunkData", PacketKind.WORLD_STATE)
                .build());
        Core.timeline().begin("network");
        Core.bus().post(PacketEvent.received(new Chunk()));
        Timeline timeline = Core.timeline().end();
        Checks.checkEquals("the timeline reads through the describer installed on the network",
                "S21PacketChunkData", timeline.getEntries().get(0).getPacketType());
        Core.network().setDescriber(null);
    }

    // ------------------------------------------------------------- helpers

    private static long ms(long millis) {
        return millis * 1_000_000L;
    }

    private static void feed(TpsTracker tracker, long startMillis, long everyMillis, long ticksEach, int updates) {
        for (int i = 0; i < updates; i++) {
            tracker.update(ms(startMillis + i * everyMillis), ticksEach < 0 ? -1L : i * ticksEach);
        }
    }

    /** The adapter's describer, for the fake packets below. */
    private static final PacketDescriber DESCRIBER = PacketDescriber.byClass()
            .on(TimeUpdate.class, p -> PacketDescription.timeUpdate("TimeUpdate", p.age))
            .on(Transaction.class, p -> PacketDescription.transaction("Transaction", p.id))
            .on(KeepAlive.class, p -> PacketDescription.keepAlive("KeepAlive", p.id))
            .on(Brand.class, p -> PacketDescription.brand("CustomPayload", p.brand))
            .on(Register.class, p -> PacketDescription.channels("CustomPayload", Arrays.asList(p.channels)))
            .on(PlayerList.class, p -> PacketDescription.of("PlayerList", PacketKind.OTHER).withLatency(p.latency))
            .on(Marker.class, "Marker", PacketKind.ACTION)
            .build();

    /** A bare bus, a hand-driven clock, and every network service on it. */
    private static final class Rig {

        final RecordingLogger logger = new RecordingLogger();
        final CoreEventBus bus = new CoreEventBus(logger);
        final AtomicLong clock = new AtomicLong();
        final FakePlatform platform = new FakePlatform(new File("build/tmp/network-tests"));
        final NetworkService network = new NetworkService(logger, bus, clock::get);
        final ServerService server = new ServerService(bus, network);
        final TpsService tps = new TpsService(bus, network);
        final LagService lag = new LagService(bus, network, platform);
        final AntiCheatService anticheat = new AntiCheatService(logger, bus, network, server);

        Rig() {
            network.start();
            server.start();
            tps.start();
            lag.start();
            anticheat.start();
            network.setDescriber(DESCRIBER);
        }

        void at(long millis) {
            clock.set(ms(millis));
        }

        void receive(Object packet) {
            bus.post(PacketEvent.received(packet));
        }

        void tick() {
            bus.post(new TickEvent(Stage.PRE));
            bus.post(new TickEvent(Stage.POST));
        }

        void join(String address) {
            bus.post(new WorldEvent(true, address));
        }

        void leave() {
            bus.post(new WorldEvent(false, ""));
        }
    }

    private static final class TimeUpdate {
        private final long age;

        private TimeUpdate(long age) {
            this.age = age;
        }
    }

    private static final class Transaction {
        private final long id;

        private Transaction(long id) {
            this.id = id;
        }
    }

    private static final class KeepAlive {
        private final long id;

        private KeepAlive(long id) {
            this.id = id;
        }
    }

    private static final class Brand {
        private final String brand;

        private Brand(String brand) {
            this.brand = brand;
        }
    }

    private static final class Register {
        private final String[] channels;

        private Register(String... channels) {
            this.channels = channels;
        }
    }

    private static final class PlayerList {
        private final int latency;

        private PlayerList(int latency) {
            this.latency = latency;
        }
    }

    private static final class Chunk {
    }

    private static class Base {
    }

    private static final class Derived extends Base {
    }

    private interface Marker {
    }

    private static final class Marked implements Marker {
    }
}
