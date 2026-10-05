package dev.px.core.render.animation;

/**
 * Easing curves, as one enum.
 *
 * <p>Replaces three parallel animation packages in the old client, where each
 * curve was its own class and picking a different one meant changing a field
 * type. Every curve maps a 0..1 progress to a 0..1 eased value, so switching is
 * now a one-word change.
 *
 * <p>The BACK and ELASTIC families overshoot outside 0..1 on purpose; that
 * overshoot is the effect. Do not clamp their output.
 */
public enum Easing {

    LINEAR {
        @Override public float apply(float t) {
            return t;
        }
    },

    // ------------------------------------------------------------ quadratic

    QUAD_IN {
        @Override public float apply(float t) {
            return t * t;
        }
    },
    QUAD_OUT {
        @Override public float apply(float t) {
            return 1f - (1f - t) * (1f - t);
        }
    },
    QUAD_IN_OUT {
        @Override public float apply(float t) {
            return t < 0.5f ? 2f * t * t : 1f - (float) Math.pow(-2f * t + 2f, 2d) / 2f;
        }
    },

    // ----------------------------------------------------------------- cubic

    CUBIC_IN {
        @Override public float apply(float t) {
            return t * t * t;
        }
    },
    CUBIC_OUT {
        @Override public float apply(float t) {
            return 1f - (float) Math.pow(1f - t, 3d);
        }
    },
    CUBIC_IN_OUT {
        @Override public float apply(float t) {
            return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3d) / 2f;
        }
    },

    // ----------------------------------------------------------------- quart

    QUART_IN {
        @Override public float apply(float t) {
            return t * t * t * t;
        }
    },
    QUART_OUT {
        @Override public float apply(float t) {
            return 1f - (float) Math.pow(1f - t, 4d);
        }
    },
    QUART_IN_OUT {
        @Override public float apply(float t) {
            return t < 0.5f ? 8f * t * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 4d) / 2f;
        }
    },

    // ------------------------------------------------------------------ sine

    SINE_IN {
        @Override public float apply(float t) {
            return 1f - (float) Math.cos(t * Math.PI / 2d);
        }
    },
    SINE_OUT {
        @Override public float apply(float t) {
            return (float) Math.sin(t * Math.PI / 2d);
        }
    },
    SINE_IN_OUT {
        @Override public float apply(float t) {
            return -(float) (Math.cos(Math.PI * t) - 1d) / 2f;
        }
    },

    // ------------------------------------------------------------------- expo

    EXPO_IN {
        @Override public float apply(float t) {
            return t == 0f ? 0f : (float) Math.pow(2d, 10d * t - 10d);
        }
    },
    EXPO_OUT {
        @Override public float apply(float t) {
            return t == 1f ? 1f : 1f - (float) Math.pow(2d, -10d * t);
        }
    },
    EXPO_IN_OUT {
        @Override public float apply(float t) {
            if (t == 0f || t == 1f) {
                return t;
            }
            return t < 0.5f
                    ? (float) Math.pow(2d, 20d * t - 10d) / 2f
                    : (2f - (float) Math.pow(2d, -20d * t + 10d)) / 2f;
        }
    },

    // ------------------------------------------------------------------- circ

    CIRC_IN {
        @Override public float apply(float t) {
            return 1f - (float) Math.sqrt(1d - t * t);
        }
    },
    CIRC_OUT {
        @Override public float apply(float t) {
            return (float) Math.sqrt(1d - Math.pow(t - 1d, 2d));
        }
    },

    // ------------------------------------------------------------------- back

    /** Dips below zero before moving. Gives a UI element a wind-up. */
    BACK_IN {
        @Override public float apply(float t) {
            return OVERSHOOT_PLUS * t * t * t - OVERSHOOT * t * t;
        }
    },
    /** Overshoots past one and settles back. The usual choice for a panel opening. */
    BACK_OUT {
        @Override public float apply(float t) {
            float shifted = t - 1f;
            return 1f + OVERSHOOT_PLUS * shifted * shifted * shifted + OVERSHOOT * shifted * shifted;
        }
    },

    // ---------------------------------------------------------------- elastic

    ELASTIC_OUT {
        @Override public float apply(float t) {
            if (t == 0f || t == 1f) {
                return t;
            }
            return (float) (Math.pow(2d, -10d * t) * Math.sin((t * 10d - 0.75d) * ELASTIC_PERIOD) + 1d);
        }
    },

    // ---------------------------------------------------------------- bounce

    BOUNCE_OUT {
        @Override public float apply(float t) {
            float n = 7.5625f;
            float d = 2.75f;
            if (t < 1f / d) {
                return n * t * t;
            }
            if (t < 2f / d) {
                float shifted = t - 1.5f / d;
                return n * shifted * shifted + 0.75f;
            }
            if (t < 2.5f / d) {
                float shifted = t - 2.25f / d;
                return n * shifted * shifted + 0.9375f;
            }
            float shifted = t - 2.625f / d;
            return n * shifted * shifted + 0.984375f;
        }
    };

    private static final float OVERSHOOT = 1.70158f;
    private static final float OVERSHOOT_PLUS = OVERSHOOT + 1f;
    private static final double ELASTIC_PERIOD = 2d * Math.PI / 3d;

    /**
     * @param progress linear progress, 0..1
     * @return the eased value, which may leave 0..1 for the overshooting curves
     */
    public abstract float apply(float progress);

    /** Applies the curve between two concrete values. */
    public float between(float from, float to, float progress) {
        return from + (to - from) * apply(progress);
    }
}
