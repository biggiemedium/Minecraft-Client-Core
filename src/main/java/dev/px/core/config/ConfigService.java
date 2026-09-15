package dev.px.core.config;

import com.google.gson.JsonObject;
import dev.px.core.event.EventBus;
import dev.px.core.platform.Platform;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Saves and loads named config profiles.
 *
 * <p>One JSON file per profile, holding every registered {@link ConfigSection}.
 * The old scheme wrote five text files per profile in a directory and parsed
 * them by splitting on colons, which is why {@code loadModules} carried the
 * comment about not working; a module whose name contained a colon, or a value
 * that failed to parse, corrupted the rest of the line silently.
 *
 * <p>Sections are loaded independently and a failure in one is logged and
 * skipped, so a partially unreadable config still restores everything else.
 */
@Getter
@RequiredArgsConstructor
public final class ConfigService implements Service {

    private static final String DEFAULT_PROFILE = "default";
    private static final String EXTENSION = ".json";

    private final CoreLogger logger;
    private final EventBus bus;
    private final Platform platform;

    private final Map<String, ConfigSection> sections = new LinkedHashMap<>();

    private Path directory;
    private String activeProfile = DEFAULT_PROFILE;

    @Override
    public String getName() {
        return "Config";
    }

    @Override
    public void start() throws IOException {
        directory = platform.getDataDirectory().toPath().resolve("configs");
        Files.createDirectories(directory);
    }

    @Override
    public void stop() {
        // Persist on the way out so a crash-free exit never loses changes.
        save();
    }

    /**
     * Registers a section. Must happen before the first {@link #load()}.
     *
     * @return the section, for chaining
     */
    public ConfigSection register(ConfigSection section) {
        sections.put(section.getId(), section);
        return section;
    }

    // ------------------------------------------------------------- profiles

    /** @return the profile names available on disk. */
    public List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*" + EXTENSION)) {
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                names.add(fileName.substring(0, fileName.length() - EXTENSION.length()));
            }
        } catch (IOException e) {
            logger.error("Could not list config profiles", e);
        }
        return names;
    }

    public boolean exists(String profile) {
        return Files.exists(pathFor(profile));
    }

    /** Saves the current state into the active profile. */
    public void save() {
        save(activeProfile);
    }

    public void save(String profile) {
        JsonObject root = new JsonObject();
        for (ConfigSection section : sections.values()) {
            try {
                root.add(section.getId(), section.save());
            } catch (RuntimeException e) {
                logger.error("Failed to serialise config section " + section.getId(), e);
            }
        }
        try {
            Json.write(pathFor(profile), root);
            logger.debug("Saved config profile " + profile);
        } catch (IOException e) {
            logger.error("Failed to write config profile " + profile, e);
        }
    }

    /** Loads the active profile. Missing file means everything keeps its defaults. */
    public void load() {
        load(activeProfile);
    }

    public void load(String profile) {
        JsonObject root;
        try {
            root = Json.read(pathFor(profile));
        } catch (IOException e) {
            logger.error("Failed to read config profile " + profile, e);
            return;
        }
        for (ConfigSection section : sections.values()) {
            if (!root.has(section.getId()) || !root.get(section.getId()).isJsonObject()) {
                continue;
            }
            try {
                section.load(root.getAsJsonObject(section.getId()));
            } catch (RuntimeException e) {
                // One bad section must not abort the rest of the load.
                logger.error("Failed to load config section " + section.getId(), e);
            }
        }
        this.activeProfile = profile;
        logger.info("Loaded config profile " + profile);
        // Settings were applied silently, so anything holding derived state
        // rebuilds here rather than going stale.
        bus.post(new ConfigLoadEvent(profile));
    }

    /** Saves the current state as a new profile and switches to it. */
    public void saveAs(String profile) {
        save(profile);
        this.activeProfile = profile;
    }

    public boolean delete(String profile) {
        try {
            return Files.deleteIfExists(pathFor(profile));
        } catch (IOException e) {
            logger.error("Failed to delete config profile " + profile, e);
            return false;
        }
    }

    private Path pathFor(String profile) {
        // Keep a profile name from escaping the config directory.
        String safe = profile.replaceAll("[^A-Za-z0-9._-]", "_");
        return directory.resolve(safe + EXTENSION);
    }
}
