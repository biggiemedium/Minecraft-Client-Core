package dev.px.core.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Small reflection helpers shared by setting discovery and config binding. */
public final class Reflect {

    private Reflect() {
    }

    /**
     * Returns every declared field from {@code type} up to (but excluding)
     * {@link Object}, base class first.
     *
     * <p>Base-first ordering means settings inherited from a shared module base
     * appear above the subclass's own settings in the GUI, which is what users
     * expect. Within one class the JVM reports fields in declaration order.
     */
    public static List<Field> fieldsOf(Class<?> type) {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            hierarchy.add(0, current);
        }
        List<Field> fields = new ArrayList<>();
        for (Class<?> current : hierarchy) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                fields.add(field);
            }
        }
        return fields;
    }

    /** Reads a field, rethrowing access failures unchecked. */
    public static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read field " + field, e);
        }
    }
}
