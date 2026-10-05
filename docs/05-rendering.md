## 5. Rendering

One facade, two swappable backends. `Render2D` is the pluggable one (NanoVG,
Skija, or legacy GL). `Render3D` is separate because a 2D vector library has no
camera or world space — it comes from your version adapter.

```java
Render.roundRect(x, y, w, h, 4, Core.themes().getSurface());
Render.text("Kill Aura", x + 4, y + 3, Color.WHITE);
Render.progressBar(x, y, w, 2, 1, health, track, fill);

Render.boxOutline(hitbox, 1.5f, Color.RED);
Render.sphere(center, 3f, Color.CYAN.withAlpha(80));
Vec2 screen = Render.worldToScreen(pos);        // null when behind the camera

// Paired push/pop also come in body-taking forms that cannot be unbalanced:
Render.clipped(x, y, w, h, () -> drawScrollingContent());
```

Before a backend is installed, every call is a silent no-op — a module or HUD
element that draws during early startup is harmless. Text is the same: with no
font installed, draws are dropped and `Render.textWidth(...)` returns 0, so an
element can size itself from text before fonts load. `Render.hasFont()` reports
it, and `FontService` warns once if no provider was installed at all.

Shaders are a separate seam again, for the same reason: `Render2D` is a drawing
vocabulary and a shader program is not one. See §9.

Animations are time-based, so a fade takes the same wall-clock time at 30 and
240 FPS:

```java
private final Animation hover = Animation.fade(150, Easing.QUAD_OUT);

hover.target(isHovered);                        // call every frame; cheap
Render.rect(x, y, w, h, base.lerp(accent, hover.get()));
```
