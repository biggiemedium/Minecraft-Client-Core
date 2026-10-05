package dev.px.core.module;

import dev.px.core.registry.Registry;

import java.util.Comparator;

/** The client's module categories, in display order. */
public final class CategoryRegistry extends Registry<Category> {

    /** Registers an enum of categories in one call, in declaration order. */
    public <E extends Enum<E> & Category> void registerAll(Class<E> enumType) {
        for (E category : enumType.getEnumConstants()) {
            register(category);
        }
        sort(Comparator.comparingInt(Category::getOrder));
    }

    @Override
    protected String describe() {
        return "category";
    }
}
