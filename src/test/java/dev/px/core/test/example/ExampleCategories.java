package dev.px.core.test.example;

import dev.px.core.module.Category;

/**
 * How a client declares its module categories.
 *
 * <p>{@link Category} is an interface rather than an enum precisely so this can
 * live in your code instead of Core's. Implementing it with an enum keeps enum
 * ergonomics; registering the whole enum is one call:
 *
 * <pre>{@code
 * core.getCategories().registerAll(ExampleCategories.class);
 * }</pre>
 */
public enum ExampleCategories implements Category {

    COMBAT("Combat", 0, "sword"),
    MOVEMENT("Movement", 1, "boots"),
    RENDER("Render", 2, "eye");

    private final String name;
    private final int order;
    private final String icon;

    ExampleCategories(String name, int order, String icon) {
        this.name = name;
        this.order = order;
        this.icon = icon;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getOrder() {
        return order;
    }

    /** Core never interprets this; it is whatever key your GUI resolves icons by. */
    @Override
    public String getIcon() {
        return icon;
    }
}
