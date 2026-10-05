package dev.px.combat.explosion;

import dev.px.core.util.Validate;

/**
 * Something that explodes: a name for logs, and its power in your game.
 *
 * <pre>{@code
 * Explosive crystal = Explosive.of("end crystal", myCrystalPower);
 * Explosive anchor  = Explosive.of("respawn anchor", myAnchorPower);
 * }</pre>
 *
 * <p>Immutable. Equal when the name and power are.
 */
public final class Explosive {

    private final String name;
    private final double power;

    private Explosive(String name, double power) {
        this.name = name;
        this.power = power;
    }

    public static Explosive of(String name, double power) {
        Validate.notBlank(name, "name");
        Validate.check(power > 0d, "power must be positive");
        return new Explosive(name, power);
    }

    public String getName() {
        return name;
    }

    public double getPower() {
        return power;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Explosive)) {
            return false;
        }
        Explosive that = (Explosive) other;
        return name.equals(that.name) && Double.compare(power, that.power) == 0;
    }

    @Override
    public int hashCode() {
        return name.hashCode() * 31 + Double.hashCode(power);
    }

    @Override
    public String toString() {
        return name + " (power " + power + ")";
    }
}
