## 14. What your adapter must supply

1. **`Platform`** — data dir, screen metrics, cursor, chat, username, in-game flag
2. **`Render2D`** — your 2D library
3. **`Render3D`** — world drawing and projection
4. **`FontProvider`** — font loading for that backend
5. **Game hooks** — call `Core.hooks()` from your mixins. It is the whole
   list of moments Core needs to hear about; see *Game hooks* below.
6. **A per-frame `Core.hud().drawAll(...)`** from your render hook, and, if you
   want edit mode, a screen that routes input into `HudEditor` and draws
   `HudEditorView`. Elements describe themselves, so there is nothing else to
   write per element; see §6.
7. **A game screen, if you use the GUI** — one that calls `Core.gui().renderFrame()`
   and hands it mouse, key, scroll and character input, then `Core.gui().close()`
   when dismissed. Core neither draws it nor listens for it. Skip this entirely if
   you are writing your own interface; see *Not using any of this* in §7.

Nothing extra is needed for threading: Core drains the game-thread queue on
every tick, so calling the tick hooks (step 5) covers it. An adapter that does
not calls `Core.threads().runPendingSync()` from its game loop instead; see §8.

Optional: `RotationSink` if you want Core arbitrating rotations — two methods,
and modules stop fighting over the head; `CollisionSpace` if you want movement
simulation and prediction — one method; `ShaderBackend` if you use `shader` —
one class of ordinary GL, and nothing else in Core notices whether it exists; `AuthProvider` (alt manager),
`PresenceProvider` / `MediaProvider`, a `PacketDescriber` on `Core.network()` with your own packet kinds plus `PacketEvent`
posts if you want TPS, ping, server and anticheat detection — and your server software and anticheat signatures, since
none ship — see §11 — an `EntitySource` plus trackers of the game's own
types if you want `Core.entities()` and targeting — see §12 — reporting the positions the server sent, with a
`positionStamp`, and an `EntityPhysics` with each entity's effects and attributes, if you want `Core.prediction()` to
predict other players well — see §10 — and `MotionUpdateEvent` posts on top if you want
timeline recordings — see §10, `PathSpace` if you use `util.spatial` — one lambda saying which cells your agent
can occupy is enough to run `AStar` against your world — and a `BlockView` (one method: the shape in a cell) plus
whatever `CellTest`s a library asks for if you use `world` or anything built on it, such as the combat library.

Core runs headless without any of these. The test suite boots it with none
installed, which is how the seam stays honest.

### Game hooks

Core never hooks the game. Your mixins call one method on `Core.hooks()` at
each moment below, and it posts the event Core's services and your modules
listen for. If one is never called, whatever listens for it goes idle — so
Core tells you instead of failing silently.

```java
@Inject(method = "runTick", at = @At("HEAD"))   private void head(CallbackInfo ci) { Core.hooks().tickStart(); }
@Inject(method = "runTick", at = @At("RETURN")) private void tail(CallbackInfo ci) { Core.hooks().tickEnd(); }

@Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
private void send(Packet<?> packet, CallbackInfo ci) {
    if (Core.hooks().packetSent(packet)) ci.cancel();          // true when a handler cancelled it
}
```

| Call | When | Idle without it, in Core |
|---|---|---|
| `tickStart()` / `tickEnd()` | head and tail of every game tick | entities, rotations, simulation, lag spikes, server and anticheat events, timeline ticks, `threads().sync(...)` |
| `worldLoaded(address)` / `worldUnloaded()` | joining and leaving a world | server, TPS, lag, entities |
| `packetReceived(p)` / `packetSent(p)` | each inbound packet decoded / outbound packet written | TPS, lag, server, anticheat, timeline |
| `packetApplied(p)` | optional: as an inbound packet's handler runs, same instance | timeline queue times |
| `motionPre(...)` / `motionPost(...)` | around the client's movement report | timeline motion entries |
| `key(...)` / `mouse(...)` | every press and release | module keybinds |
| `chatSend(message)` | before the player's chat is sent | commands |
| `scroll`, `charTyped`, `chatReceived`, `screen`, `render2D`, `render3D` | as named | nothing in Core — your modules |

The cancellable ones return whether a handler cancelled them; the chat ones
return the event, since a handler may rewrite the message. `render2D` is for
your modules' drawing and does **not** draw the HUD — that is still
`Core.hud().drawAll()`, step 6. Posting the events yourself still works; Core
counts them however they arrive.

**It warns.** Ticks, the world, packets, motion and rendering fire the whole
time the player is in a world, so their absence can be seen: once
`Platform.isInGame()` has been true for five seconds (`setGracePeriodMillis`),
Core logs one warning per hook that something is listening for and that has
never fired, naming the call to make and everything idle without it:

```
No TickEvent after 5s in a world, so these are idle: Core, ServerService, EntityService, RotationService,
LagService, AntiCheatService, KillAura. Call Core.hooks().tickStart() and tickEnd(),
around every game tick from your adapter (or post TickEvent yourself).
```

"Needed" means something is listening right now — Core's services, or your
modules while enabled — so a hook nothing uses never warns, and the motion hook
only matters while a timeline is recording.

**It verifies.** Keys, mouse and chat fire only when the player acts, so their
absence proves nothing at runtime. In your development build, play for a few
seconds, press a key, click, send a chat message, then:

```java
Core.hooks().verify();                       // throws, listing every needed hook that never fired
Core.hooks().verify(Hook.KEY, Hook.MOUSE);   // or exactly these, needed or not
Core.hooks().report().forEach(System.out::println);
//  TICK         4211x    Core, ServerService, EntityService, ...
//  KEY          MISSING  InputService, KillAura
//  SCREEN       unused   -
```

Core never throws on its own: a hook can only be judged missing over time, and
a client that skips one should lose the features that need it, not crash.
