package dev.px.core.shader;

/**
 * One programmable stage of a shader program.
 *
 * <p>Core neither knows nor cares which of these the host's GL context actually
 * supports &mdash; a client on 1.8.9 has no compute stage and one on 1.21 has
 * all four. Declaring a stage a backend cannot compile is reported as a compile
 * failure like any other, with the shader named, rather than being rejected here
 * on a guess about the driver.
 *
 * <p>Nothing requires a vertex stage. A fragment-only program is legal on a
 * compatibility profile and is how most legacy post-processing shaders are
 * written, so Core only asks that a {@link ShaderSource} declare at least one
 * stage and leaves the rest to the backend.
 */
public enum ShaderStage {

    VERTEX("vertex"),
    FRAGMENT("fragment"),
    GEOMETRY("geometry"),
    COMPUTE("compute");

    private final String displayName;

    ShaderStage(String displayName) {
        this.displayName = displayName;
    }

    /** Lowercase name used in log lines and compile errors. */
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
