package dev.px.core.module;

import dev.px.core.input.Key;
import dev.px.core.input.Modifier;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a module's identity.
 *
 * <pre>{@code
 * @ModuleInfo(name = "Kill Aura", description = "Attacks nearby targets", category = "Combat")
 * public final class KillAura extends Module { ... }
 * }</pre>
 *
 * <p>{@link #category()} is a name looked up in the {@link CategoryRegistry} at
 * registration, and an unknown name fails immediately with the list of valid
 * ones. Leave it blank to infer the category from the package the module lives
 * in, which is correct whenever modules are foldered by category.
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ModuleInfo {

    String name();

    String description() default "";

    /** Category name. Blank infers it from the last package segment. */
    String category() default "";

    /** Default keybind. Users can rebind; this is only the starting value. */
    Key bind() default Key.NONE;

    Modifier[] modifiers() default {};

    /** Whether the module starts enabled on a fresh install. */
    boolean enabled() default false;

    /** Whether the module appears in the ArrayList HUD element when enabled. */
    boolean visible() default true;
}
