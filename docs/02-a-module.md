## 2. A module

```java
@ModuleInfo(name = "Kill Aura", description = "Attacks nearby targets", category = "Combat")
public final class KillAura extends Module {

    // Declaring the field registers the setting. There is no create(...) wrapper.
    private final NumberSetting<Float> reach  = number("Reach", 4f, 3f, 6f).step(0.1f);
    private final RangeSetting         cps    = range("CPS", 8, 14, 1, 20);
    private final BooleanSetting       block  = bool("Auto Block", true);
    private final EnumSetting<Mode>    mode   = enumOf("Block Mode", Mode.VANILLA)
                                                    .visibleWhen(block);        // hides when off
    private final MultiEnumSetting<Target> targets =
            multi("Targets", Target.class, Target.PLAYERS, Target.MOBS);        // checkbox list
    private final ColorSetting         hitbox = color("Hitbox", Color.RED);
    private final BindSetting          key    = toggledBy(bind("Keybind", Key.R)); // opt-in, lists first

    // Who counts, in the game's own types. LivingTracker is yours: see §12.
    private final TargetSelector<EntityLivingBase> enemies = TargetSelector.from(LivingTracker.class)
            .range(reach::getDouble)
            .where(e -> targets.has(e instanceof EntityPlayer ? Target.PLAYERS : Target.MOBS))
            .build();
    private final TargetLock<EntityLivingBase> lock = Core.targets().lock(enemies);

    private final Stopwatch attackTimer = Stopwatch.expired();
    private Vec2 aim = Vec2.rotation(0f, 0f);

    @Subscribe(stage = Stage.PRE, priority = Priority.HIGH)
    private void onTick(TickEvent event) {
        Tracked<EntityLivingBase> target = lock.update();       // keeps one target, no flicking
        if (target == null) return;

        Vec3 eye = Core.entities().getSelf().getEyePosition();
        aim = RotationMath.step(aim, eye.rotationTo(target.aimPoint(eye, 0.05)), 30f);
        applyRotation(aim);                                      // yours

        if (attackTimer.tryConsume(1000 / cps.randomInt())) {    // randomised delay
            attack(target.get());                                // yours: the game's own entity
        }
    }

    @Subscribe
    private void onRender3D(Render3DEvent event) {
        if (lock.hasTarget()) {
            Render.boxOutline(lock.get().getBox(), 1.5f, hitbox.resolve());
        }
    }

    @Override protected void onDisable() { lock.release(); }

    /** Extra text shown after the name, wherever your client lists modules. */
    @Override public String getDisplayInfo() { return mode.displayValue(); }

    public enum Mode { VANILLA, WATCHDOG, NCP }
    public enum Target { PLAYERS, MOBS, ANIMALS, INVISIBLES }
}
```

Everything above is Core's API except the two lines marked `// yours` — writing
the rotation and swinging need the game — and the game's own entity types, which
reach Core only as the type parameter of a tracker (§12). The selector picks the
target in your terms, the lock holds it across ticks, `aimPoint` aims just
inside its box rather than at its edge, `RotationMath.step` traces the turn out
over several ticks instead of snapping, and the box comes back ready to outline.
One module owning its own aim like this is fine; the moment a second one wants
the head they fight, which is what `Core.rotations()` in §10 exists to settle.
`ExampleKillAura` in the test suite is the aiming half of this module, running
headless with its positions handed in.

Reading settings at the use site:

```java
if (block.isOn() && mode.is(Mode.WATCHDOG)) { ... }
if (targets.has(Target.PLAYERS)) { ... }
float r = reach.getFloat();
```

Three things are automatic:

- **Settings register by being declared** — discovered after construction, so
  nothing is missed.
- **Handlers are annotated methods** — the parameter type *is* the event, no
  type token to keep in sync.
- **Subscription is permanent** — a module subscribes once at registration and
  its handlers go quiet while disabled. Overriding `onEnable` without calling
  `super` can no longer silently break event delivery.

Leave `category` off `@ModuleInfo` and it is inferred from the package name.

### Keybinds are opt-in

A module has no settings it didn't declare. One that should switch on and off
from a key hands a bind to `toggledBy`, which returns it for the field and lists
it first; one without a bind is never toggled by a key, and has no key row in
the GUI or entry in the config. A module has at most one toggle bind. If every
module in your client should be bindable, say so once in your own base class:

```java
public abstract class ClientModule extends Module {
    private final BindSetting keybind = toggledBy(bind("Keybind"));
}
```

### Press or hold

A bind either acts on the press or lasts while the key is down. `Bind.of(Key.R)`
toggles the module each press; `Bind.hold(Key.R)` switches it on when the key
goes down and off when it comes up, whatever modifiers are still held by then.

```java
private final BindSetting key = toggledBy(bind("Keybind", Bind.hold(Key.R)));
```

The mode is part of the bind's value, so it is saved and reset with it, and
rebinding the key keeps it. Users switch it in the click GUI by middle-clicking
the bind row, and code switches it with `setMode(BindMode.HOLD)`. Actions get
the same choice: `Core.input().register(name, bind, onPress, onRelease)` runs
`onRelease` when a hold bind comes up. Releases are heard even if a screen
cancelled them, so a hold cannot stick; if your game can lose a release
altogether (the window losing focus), call `Core.input().releaseAll()`.

### When onEnable fails

A module counts as on only once `onEnable` has returned. If it throws, the
module is left off, nothing is told it switched on, and the exception reaches
whoever enabled it; `onDisable` is not run, so release anything taken before the
throw. `onEnable` can also refuse quietly by calling `disable()`. A disable
always ends off and is always announced, even if `onDisable` throws.
`ModuleToggleEvent` is therefore posted after the hook, never before it.

Whether a module shows in an ArrayList, and in what order, is your HUD
element's business: Core keeps no "visible" flag. Filter `Core.modules().enabled()`
however your ArrayList wants, adding a `bool("Hidden", false)` to your base
class if users should be able to hide one.

> On Java 9+ (1.17 and newer), import `dev.px.core.module.Module` explicitly —
> a wildcard import collides with `java.lang.Module`. Not an issue on Java 8.

### Setting types

| Factory | Type | Renders as |
|---|---|---|
| `bool(name, def)` | `BooleanSetting` | checkbox |
| `number/integer/decimal(name, def, min, max)` | `NumberSetting<N>` | slider |
| `range(name, lo, hi, min, max)` | `RangeSetting` | two-handled slider |
| `enumOf(name, def)` | `EnumSetting<E>` | dropdown |
| `multi(name, Type.class, defaults...)` | `MultiEnumSetting<E>` | checkbox list |
| `text(name, def)` | `StringSetting` | text field |
| `color(name, def)` | `ColorSetting` | picker (+ rainbow / theme-sync) |
| `bind(name, Key.X)` | `BindSetting` | keybind button |
| `group(name)` | `GroupSetting` | collapsible section |

All chain `.describe(...)`, `.visibleWhen(...)`, `.onChange(...)`.
