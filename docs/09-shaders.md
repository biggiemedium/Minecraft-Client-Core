## 9. Shaders

Core does not link OpenGL and never will. **One class does** — the
`ShaderBackend` you write — and it is about a hundred lines of ordinary GL.
Everything around it is the half that is the same in every client and tedious in
all of them.

| Core | You |
|---|---|
| Reads the source, inlines `#include`, hoists `#version`, injects `#define` | Write the GLSL |
| Compiles once, on first draw, and keeps the handle | `glCreateProgram` / `glCompileShader` / `glLinkProgram` |
| Records uniform values by name and type | `glUniform*`, and cache the locations |
| Binds, unbinds, and restores the outer pass even when a draw throws | `glUseProgram` |
| Disposes every program at shutdown and on reload | `glDeleteProgram` |
| Turns a GLSL error into one log line with the source numbered | — |

### Wiring it up

```java
ShaderService shaders = Core.shaders();
shaders.setBackend(new GlShaderBackend());                       // yours, below
shaders.setLoader(ShaderLoader.classpath("assets/leapfrog/shaders"));

shaders.register(ShaderSource.named("glow")
        .vertex("glow.vsh")
        .fragment("glow.fsh")
        .define("SAMPLES", 12)
        .build());
```

Registration is pure data — nothing is read and no GL call is made — so it
belongs in the same block as modules and HUD elements, before a window exists.
The first draw reads, assembles and compiles it, on the thread that is rendering,
which is the only thread that may. A shader you register and never draw with
costs a map entry.

A stage is either a path the loader reads or literal text given inline
(`fragmentSource(...)`), and there is no requirement to have a vertex stage —
fragment-only programs are how most legacy post-processing is written.

### Drawing with one

```java
Core.shaders().use("glow",
        u -> u.set("uRadius", 6f).set("uColour", Core.themes().getPrimary()),
        () -> Render.rect(x, y, width, height, Color.WHITE));
```

The body is passed in for the same reason `Render.clipped` takes one: an
unbalanced bind corrupts every later draw in the frame, and a body that throws
would leave it unbalanced. Here that is impossible. Nesting works too — an inner
pass restores the outer program on the way out rather than dropping to the fixed
pipeline.

A client that owns its own draw loop and cannot express it as a body uses the
long form instead:

```java
if (shaders.bind("glow", myUniforms)) {
    try { drawMyQuad(); } finally { shaders.unbind(); }
}
```

`bind` applies exactly what you hand it; call `shaders.applyGlobals(myUniforms)`
first if you want the built-ins as well.

### The backend

The one class that sees GL. It extends `UniformSink`, because uniforms in GL go
to whichever program is bound and the calls need exactly the state a backend
already holds — the program, its cached locations, its texture units.

```java
public final class GlShaderBackend implements ShaderBackend {

    public Shader compile(ShaderSource source, Map<ShaderStage, String> glsl) {
        int program = glCreateProgram();
        for (Map.Entry<ShaderStage, String> stage : glsl.entrySet()) {
            int id = glCreateShader(typeOf(stage.getKey()));
            glShaderSource(id, stage.getValue());               // already assembled
            glCompileShader(id);
            if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
                throw new ShaderException(stage.getKey() + ": " + glGetShaderInfoLog(id));
            }
            glAttachShader(program, id);
            glDeleteShader(id);
        }
        glLinkProgram(program);
        return new GlShader(source.getName(), program);         // caches locations
    }

    public void bind(Shader shader) { glUseProgram(((GlShader) shader).id); }
    public void unbind()            { glUseProgram(0); }

    public void floats(String name, float[] v, int count) {
        switch (count) {
            case 1: glUniform1f(location(name), v[0]); break;
            case 2: glUniform2f(location(name), v[0], v[1]); break;
            // ...
        }
    }
    public void ints(String name, int[] v, int count)           { /* glUniform1i ... */ }
    public void matrix(String name, float[] v, int order)       { /* glUniformMatrix4fv */ }
    public void sampler(String name, Texture texture, int unit) { /* activate, bind, 1i */ }
}
```

Four uniform methods rather than a dozen, grouped by component count, so `vec2`
and `vec4` arrive through the same call and the backend is written once. Throwing
from `compile` is the correct way to report a driver error: the message ends up
in the log with the shader named.

Location lookup belongs to the backend, because only the backend knows what a
location is. Looking one up by name every frame is a driver round trip per
uniform — cache them on your `Shader`.

### Uniforms

`Uniforms` records what to send; the backend decides how.

```java
u.set("uStrength", 0.5f)                 // float
 .set("uCentre", x, y)                   // vec2       (also Vec2, Vec3)
 .set("uColour", theme.getPrimary())     // vec4, already normalised to 0..1
 .set("uSamples", 12)                    // int        (also two ints, boolean)
 .matrix("uProjection", projection)      // mat2/3/4, column-major
 .sampler("uScene", sceneTexture, 0);    // binds the unit and points the uniform at it
```

A `Color` is divided by 255 on the way in, because forgetting that divide is the
single most common way a shader comes out white. A `Texture` is the same handle
`Render2D` draws with, so an image loaded for the HUD can be sampled by a shader
without a second copy.

The instance is mutable and reused on purpose: a fresh immutable one per frame
would allocate a map, a boxed float and an array per uniform, sixty times a
second, forever. `use(...)` hands you one per shader, already cleared, so a
steady-state frame allocates nothing.

Three values are set for you on every pass, from `Platform`:

| Name | Value |
|---|---|
| `uTime` | seconds since startup, wrapped hourly so a `float` keeps its precision |
| `uResolution` | scaled screen width and height |
| `uMouse` | cursor, in the same space |

`setBuiltinUniforms(false)` turns them off for a client with its own naming, and
`addGlobalUniforms(u -> ...)` adds values every pass receives — a projection
matrix, a palette, partial ticks.

### `#include`, and the two other things GLSL makes awkward

GLSL has no `#include`, so a helper shared by four shaders normally lives in four
copies. Core inlines them, resolved relative to the including file and applied
**once each** — a header pulled in twice would redeclare everything in it.

`#version` has to be the first line, which means an included header can never
carry one. The first version directive found anywhere is hoisted to the top and
later ones dropped, so a header can declare the version it was written against.

`#define`s come from the `ShaderSource`, injected below the version line, so
sample counts and feature switches are configured in Java instead of by string
concatenation at the call site. `define("NAME")` with no value is a bare symbol
for `#ifdef`, which is not the same as defining it to 0.

None of this needs a GL context, which is why it is Core's and not yours.

### When a shader is broken

A missing file, a bad include or a GLSL error is logged **once**, with the
assembled source listed by line — the line a driver means is a line of text
nobody has on disk, so printing it is the difference between a fixable message
and `0(46) : error C1503`. The shader is then marked failed and not retried every
frame.

`use(...)` still runs its body, unshaded. A missing effect should cost the
effect, not the thing it was decorating — the same reasoning as `Render` dropping
draw calls before a backend is installed. `isReady(name)` lets you branch instead,
and `isFailed(name)` says whether it has already been tried.

The same applies with no backend installed at all, and to a handle whose context
went away under it: an invalid program is rebuilt rather than bound.

### Editing GLSL with the game running

```java
shaders.setLoader(ShaderLoader.directory(devFolder)
        .orElse(ShaderLoader.classpath("assets/leapfrog/shaders")));
```

Save the file, call `Core.shaders().reload()`, see it. `reload()` disposes every
program and clears every failure, so the next draw builds them again; it is also
what to call after a resource reload on versions where that destroys the GL
objects Core is holding.

### Not using any of this

Nothing outside `dev.px.core.shader` references the package. Core registers the
service because every service is registered in one place, and with no backend it
stays silent — no warning, no allocation beyond an empty registry, no GL. It is
the one service that says nothing when it is unconfigured, because a client with
no fonts is misconfigured while a client with no shaders is just a client.
