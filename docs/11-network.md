## 11. Network

Four services read the connection: **`Core.tps()`** (the server's tick rate),
**`Core.lag()`** (ping, packet rates, the server going silent),
**`Core.server()`** (address, brand, software, proxy, channels) and
**`Core.anticheat()`** (which anticheat the server probably runs). They share
one way in, `Core.network()`, which is also where the timeline recorder in §10
gets its packets.

The services do the work — smoothing, spike detection, brand matching, ranking
evidence — and **know nothing about any game**. No packet names, no channel
names, no tick rate, no server software, no anticheat. All of that is plugged
in by your client, so a version that renames, splits or removes a packet costs a
line in your describer and nothing in Core.

```java
Core.tps().getTps();                               // 19.8
Core.lag().getPing();                              // 48
Core.lag().isLagging();                            // true while the server is silent
Core.server().isOn("hypixel.net");                 // true on mc.hypixel.net
Core.server().getSoftware();                       // Paper — if you registered it
Core.anticheat().getPrimary();                     // Optional[MyAC (KNOWN: ...)] — if you registered it
```

### Wiring it up

1. **Hook the traffic.** `Core.hooks().packetSent(p)` before an outbound packet
   is written, `packetReceived(p)` as an inbound one is decoded. `packetApplied`
   is optional here; a packet given only that counts as arriving then.
2. **Declare your packet kinds**, as an enum, the same way you declare module
   categories. Core ships none but `PacketKind.OTHER`. Kinds are labels for
   you — filtering, timeline queries — and no service reads them.
3. **Say what the packets are**, once, with a describer. Each description gets
   your kind, plus a **role** for each thing a service should read from it:

```java
public enum Packets implements PacketKind { TIME, TRANSACTION, KEEP_ALIVE, PAYLOAD, TELEPORT, VELOCITY }

// 1.8.9 (MCP names)
Core.network().setDescriber(PacketDescriber.byClass()
        .on(S03PacketTimeUpdate.class, p -> PacketDescription.of("S03PacketTimeUpdate", Packets.TIME)
                .withWorldAge(p.getTotalWorldTime()))                          // TPS
        .on(S32PacketConfirmTransaction.class, p -> PacketDescription.of("S32PacketConfirmTransaction", Packets.TRANSACTION)
                .withTransaction(p.getActionNumber()))                         // anticheat
        .on(S00PacketKeepAlive.class, p -> PacketDescription.of("S00PacketKeepAlive", Packets.KEEP_ALIVE)
                .withCorrelationKey("keepalive:" + p.func_149134_c()))          // no role: just recorded
        .on(S3FPacketCustomPayload.class, p -> "MC|Brand".equals(p.getChannelName())
                // read a copy: the game reads the same buffer after this returns
                ? PacketDescription.of("S3FPacketCustomPayload", Packets.PAYLOAD).withBrand(
                        new PacketBuffer(p.getBufferData().copy()).readStringFromBuffer(32767))   // server
                : PacketDescription.of("S3FPacketCustomPayload", Packets.PAYLOAD))
        .on(S08PacketPlayerPosLook.class, p -> PacketDescription.of("S08PacketPlayerPosLook", Packets.TELEPORT)
                .asCorrection())                                               // timeline
        .on(S12PacketEntityVelocity.class, p -> PacketDescription.of("S12PacketEntityVelocity", Packets.VELOCITY))
        .build());
```

| Role | Read by | Give it to |
|---|---|---|
| `withWorldAge(ticks)` | `Core.tps()` | the packet carrying the server's clock |
| `withTransaction(id)` | `Core.anticheat()` | packets the server sends for the client to answer, to time it |
| `withLatency(ms)` | `Core.lag()` | whatever reports the server's measured ping for the local player |
| `withBrand(brand)` / `withChannels(list)` | `Core.server()` | whatever carries the brand and registered channels |
| `asCorrection()` | `Core.timeline()` | packets that overrule the client's motion |

A version with no such packet never gives the role; a version where two packets
play it gives it to both. The channel names in the example are the adapter's —
Core has none. `PacketDescriber.byClass()` builds a describer from one rule per
packet class — a hash lookup per packet instead of an `instanceof` chain, with
subclasses falling back to their parent's rule and anything unmatched to the
class name. A plain lambda describer works just as well. Name packets with
string literals: in an obfuscated build the class is called `a`.

Where the version already parses something, reporting it is simpler than
describing the packet it came in:

```java
// from a tick handler, about once a second
Core.server().reportBrand(mc.thePlayer.getClientBrand());          // 1.8.9

NetworkPlayerInfo self = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
if (self != null) Core.lag().reportPing(self.getResponseTime());    // 1.8.9
```

Both are safe to call every tick: an unchanged value changes nothing.

What each service needs, beyond the `PacketEvent` posts and the `WorldEvent`
your adapter already sends:

| Service | Needs a role | Or reported | Also yours | Works without? |
|---|---|---|---|---|
| `Core.lag()` | `withLatency`, for ping | `reportPing(ms)` | the spike threshold, if not 1.5s | Yes: rates and spikes need arrivals only |
| `Core.tps()` | `withWorldAge` | — | optionally `setTargetTps` | NaN, with `hasEstimate()` false |
| `Core.server()` | `withBrand`, `withChannels` | `reportBrand`, `reportChannels` | `registerSoftware(...)` | Yes: address, singleplayer and `isOn` come from `WorldEvent` |
| `Core.anticheat()` | `withTransaction`, for timing | — | `register(signature)` — none ship | No: with no signatures, nothing is detected |

### TPS

A server that tells the client its world age every so often is saying how many
ticks passed (the age moved on by that much) and, by when the update arrives,
how long they took. `getTps()` is the ratio of the sums over the last ten
updates. Summing rather than averaging each update's rate is what keeps network
bunching out of it: an update held back 0.9s and the one after arriving 0.1s
later read as wildly different rates; summed, they are the ticks that passed in
the time that passed.

**No rate is assumed.** Not what the server should run at, nor how often it
sends its clock — both are measured. A frozen server sends no update to report
that it froze, so the estimate is also bounded by the update now overdue:
once it is later than updates have been arriving, the ticks it would have
carried are spread over the time actually waited. Measurements are forgotten on
leaving or changing server, and a world age that goes backwards, or leaps by far
more than updates have been carrying, re-baselines rather than reporting a spike.

`setTargetTps(rate)` is yours to give, and optional: it caps the estimate, so
bunching never reads as a server running fast, and it is what `getTps()` reports
before the first measurement. Without one, that is NaN. Call it again whenever
your server announces a different rate. `TpsTracker` is the whole algorithm with
no bus or service; feed it from anywhere with `update(nanos, worldAge)`.

### Lag

Two different things get called lag. A **slow** server still talks, less
often: that is TPS. A **silent** one sends nothing at all, and everything the
client does meanwhile is judged late. `Core.lag()` watches for the second: once
nothing has arrived for `getSpikeThresholdMillis()` (1.5s unless you set it —
pick something longer than the longest gap your game's server leaves on a
healthy connection), `isLagging()` turns true and a `LagSpikeEvent` is posted,
then another with the whole duration when traffic resumes. Both are posted on
the game thread, and never outside a world.

```java
@Subscribe
private void onLag(LagSpikeEvent event) {
    if (event.isStarted()) Core.notifications().warn("Lag", "Server not responding");
}
```

`getInboundRate()` and `getOutboundRate()` are packets a second over the last
second. `getPing()` is the server's own measurement: a client cannot time a
round trip the server starts. `getAveragePing()` and `getPingJitter()` are over
the last ten reports.

### Server

```java
Core.server().registerSoftware(                    // yours: Core recognises none
        ServerSoftware.of("Purpur"),               // forks before what they forked
        ServerSoftware.of("Paper"),
        ServerSoftware.of("Forge", "forge", "fml"),
        ServerSoftware.proxy("Velocity"),
        ServerSoftware.proxy("BungeeCord", "bungeecord", "waterfall"));

Core.server().getInfo();                   // ServerInfo(play.example.net:25577, Paper (Velocity))
Core.server().getBrand().getSoftware();    // Paper — the server behind any proxy
Core.server().getBrand().getProxy();       // Velocity (proxy), or null
Core.server().getBrand().getRaw();         // always: exactly what the server sent
Core.server().hasChannel("floodgate:skin");
```

Server software comes and goes faster than any library, so a built-in list would
go stale and name the wrong thing with confidence. You register what you care
about, with the words its brand contains. The whole brand is matched against
every registration by **whole word**, ignoring case, so `"Newspaper"` is not
Paper — and a proxy and the server behind it are found independently, which is
why no proxy's brand format needs knowing: `"BungeeCord (git:...) <- Paper"` and
`"Paper (Velocity)"` both name one of each. Where a brand names two, the one
registered first wins. Registering later reads a brand already received again;
with nothing registered, the software is `ServerSoftware.UNKNOWN` and the raw
brand is still there.

The address is normalised (lower case, no trailing dot, port split off, IPv6 in
brackets understood), with **no default port** — `hasPort()` says whether the
address named one — and `isOn("hypixel.net")` matches the domain and its
subdomains, never a lookalike.

A brand that arrives before the world does — some games send it while still
connecting — is held and applied when the world loads, which is why what a
server said is forgotten on leaving rather than on joining. Changes are
announced with one `ServerChangeEvent` per tick on the game thread, with
`isJoin()`, `isLeave()` and `isBrandChanged()` against what was last announced:

```java
@Subscribe
private void onServer(ServerChangeEvent event) {
    if (event.isJoin() && event.getCurrent().isOn("hypixel.net")) {
        Core.config().load("hypixel");
    }
}
```

### Anticheat

Detection is a guess, and says so. Every `Detection` carries a `Confidence`
(`POSSIBLE`, `LIKELY`, `KNOWN`) and a human-readable reason. Nothing a server
sends proves which anticheat it runs, so use this to pick sensible defaults,
not to bet an account on.

Each registered `AntiCheatSignature` looks at `ServerEvidence` — the
`ServerInfo` above, plus the `TransactionPattern` of the packets given
`withTransaction`: how fast they come, and whether their ids count up or down
and on which side of zero — and returns a `Detection` or null. They run every
`setEvaluateEveryTicks(n)` ticks (20 unless set) and whenever the server
changes; a changed result posts an `AntiCheatChangeEvent`.

**Core registers no signatures.** A signature that names an anticheat, or a
server's anticheat, is a fact about one game's ecosystem that goes stale, and a
stale one reports the wrong name with confidence. So every signature is yours,
built from these or written as a lambda:

```java
Core.anticheat().register(AntiCheatSignature.onServer("example.net", "MyAC"));
Core.anticheat().register(AntiCheatSignature.brandContains("myac", "MyAC", Confidence.KNOWN));
Core.anticheat().register(AntiCheatSignature.channel("myac:main", "MyAC", Confidence.KNOWN));
Core.anticheat().register(AntiCheatSignatures.transactionBased(2, 10));   // your rates, per second
Core.anticheat().register(evidence -> {
    TransactionPattern p = evidence.getTransactions();
    return p.getOrder() == TransactionPattern.Order.DECREMENTING && p.getHighestId() < -1000
            ? Detection.of("MyAC", Confidence.LIKELY, "counts down from -1000")
            : null;
});
```

`transactionBased(possibleRate, likelyRate)` reports a generic
`"Transaction-based anticheat"` once answer-me packets arrive faster than your
game's own traffic explains — the two rates are yours, because what is normal
depends on the game. Detections rank by confidence, and at equal confidence a
named one ranks above the generic one, so a signature you add for the same
evidence is the one `getPrimary()` reports. Give `withTransaction` only to
packets the server sends to time the client: one it sends on its own fixed
schedule, such as a keep-alive on most versions, would make every server look
protected.

### Hearing the traffic yourself

`Core.network().addListener(PacketListener)` gets each packet once, already
described: inbound on arrival (including ones a handler cancelled, since the
server still sent them), outbound only if actually sent. It is the same stream
the services read, for a module that cares about `packet.getKind().is(Packets.VELOCITY)`
and not about packet classes.

### Threading

Inbound packets arrive on the network thread, so the services record under
small locks and every getter is safe from any thread. Every event they post —
`ServerChangeEvent`, `LagSpikeEvent`, `AntiCheatChangeEvent` — is posted from
the tick, on the game thread, so a handler may touch the game.

### Not using any of this

Post no `PacketEvent` and all four services sit idle: nothing is described,
nothing is posted, and every getter returns its "unknown" value (`-1` ping,
NaN TPS with `hasEstimate()` false, `ServerInfo.DISCONNECTED`, no
detections). Install no describer and `Core.lag()` still works in full, since
it needs arrivals and not meanings. `TpsTracker`, `TransactionTracker` and
`util.time.RateMeter` are plain classes with no bus or service behind them.
