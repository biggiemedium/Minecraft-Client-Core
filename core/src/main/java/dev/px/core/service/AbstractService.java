package dev.px.core.service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Convenience base for a service whose name is a constant and whose stop is a no-op. */
@Getter
@RequiredArgsConstructor
public abstract class AbstractService implements Service {

    private final String name;
}
