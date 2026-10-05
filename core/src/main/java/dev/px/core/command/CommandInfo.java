package dev.px.core.command;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a command's identity.
 *
 * <pre>{@code
 * @CommandInfo(name = "config", aliases = {"cfg"}, usage = "config <save|load> <name>")
 * public final class ConfigCommand extends Command { ... }
 * }</pre>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CommandInfo {

    String name();

    /** Alternative names. Registered alongside the primary name. */
    String[] aliases() default {};

    String description() default "";

    /**
     * How to invoke it, without the prefix. Printed on a usage error and by help,
     * so write it for the user rather than as a formal grammar.
     */
    String usage() default "";
}
