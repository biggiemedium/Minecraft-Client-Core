## 3. An event

```java
@Getter @AllArgsConstructor
public final class PlayerMoveEvent extends CancellableEvent {
    private double x, y, z;
}
```

| Base | Use when |
|---|---|
| `Event` | pure notification |
| `CancellableEvent` | handlers may suppress the action |
| `StagedEvent` | the same call site fires before *and* after the action |

Posting from a mixin — `post` returns the event, so you can check it inline:

```java
@Inject(method = "jump", at = @At("HEAD"), cancellable = true)
private void onJump(CallbackInfo ci) {
    if (Core.bus().post(new PlayerJumpEvent(motionY)).isCancelled()) {
        ci.cancel();
    }
}
```

A handler may declare a supertype and receive every subclass, so one
`@Subscribe(PacketEvent)` covers both send and receive.

Core already ships version-agnostic events in `event.impl`: `TickEvent`,
`Render2DEvent`, `Render3DEvent`, `KeyEvent`, `MouseEvent`, `ScrollEvent`,
`CharTypedEvent`, `ChatSendEvent`, `ChatReceiveEvent`, `WorldEvent`,
`ScreenEvent`, `ClientLifecycleEvent`, and `PacketEvent` / `MotionUpdateEvent`,
which carry the game's objects opaquely (see §10 and §11). Game-specific ones
(entities, attacks) belong in your adapter.
