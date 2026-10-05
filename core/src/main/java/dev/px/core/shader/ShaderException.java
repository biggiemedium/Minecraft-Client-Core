package dev.px.core.shader;

/**
 * Thrown when shader source cannot be assembled or a program cannot be built.
 *
 * <p>Unchecked, matching {@link dev.px.core.service.ServiceException}: a broken
 * shader is a programming error, not a condition every call site should handle.
 * {@link ShaderService} catches it, logs it once with the shader named and the
 * assembled source listed by line, and leaves that shader marked failed &mdash;
 * so a typo in GLSL costs a log line and an unshaded draw, not a crashed client.
 */
public class ShaderException extends RuntimeException {

    public ShaderException(String message) {
        super(message);
    }

    public ShaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
