package dev.px.core.test.harness;

import dev.px.core.render.Texture;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A texture handle with no image behind it.
 *
 * <p>Enough for the shader suite, which only needs to prove that a sampler
 * reaches the backend with the right unit and that an invalid handle is dropped
 * before it gets there.
 */
@Getter
@RequiredArgsConstructor
public final class FakeTexture implements Texture {

    private final String name;
    private final int width;
    private final int height;

    private boolean valid = true;

    public FakeTexture(String name) {
        this(name, 16, 16);
    }

    @Override
    public boolean isValid() {
        return valid;
    }

    @Override
    public void dispose() {
        valid = false;
    }

    @Override
    public String toString() {
        return name;
    }
}
