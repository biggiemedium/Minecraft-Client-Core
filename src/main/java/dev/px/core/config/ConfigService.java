package dev.px.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.px.core.config.crypto.ConfigCipher;
import dev.px.core.config.io.Json;
import dev.px.core.event.EventBus;
import dev.px.core.platform.Platform;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import dev.px.core.util.Validate;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Saves and loads config profiles: one folder per profile, one JSON file per
 * {@link ConfigSection}, laid out the way you choose.
 *
 * <pre>
 * configs/                        {@link #setDirectory}, under Platform.getDataDirectory()
 *   profiles/                     {@link #setProfilesFolder}
 *     default/
 *       modules.json              a section in every profile
 *       hud.json
 *       myclient/waypoints.json   one you placed in a folder of its own
 *     hypixel/...
 *   shared/                       {@link #setSharedFolder}: the same for every profile
 *     accounts.enc                one you chose to encrypt
 *     friends.json
 *     profile.json                which profile was active last
 * </pre>
 *
 * <h2>Placing a section</h2>
 *
 * <p>A section lives in every profile, under its id, unless told otherwise.
 * {@link #register(ConfigSection, ConfigLocation)} says where your own sections
 * go, and {@link #place} moves any section, Core's included:
 *
 * <pre>{@code
 * config.register(new SettingsSection("waypoints", waypoints), ConfigLocation.profile("myclient/waypoints"));
 * config.place("hud", ConfigLocation.profile("visual/hud"));
 * config.place("accounts", ConfigLocation.shared("private/accounts"));
 * }</pre>
 *
 * <p>A location given with {@link #place} wins over one given at registration.
 * Two sections can never share a file: that is refused when the second is placed.
 *
 * <h2>Encryption, if you want it</h2>
 *
 * <p>Nothing is encrypted unless you say so. {@link #encrypt} gives one section a
 * {@link ConfigCipher}, and from then on it is written as {@code .enc} instead of
 * {@code .json} &mdash; a plaintext file it replaces is deleted once the
 * encrypted one is written. The key, and so what the encryption is worth, is
 * yours: see {@link ConfigCipher}.
 *
 * <h2>Nothing is lost quietly</h2>
 *
 * <ul>
 *   <li>Every file is written to a temporary file and renamed over the old one,
 *       so a crash mid-save leaves the last good file, never half of one.
 *   <li>A file that cannot be read &mdash; malformed, edited badly, encrypted
 *       with another key &mdash; is copied aside as
 *       {@code <name>.unreadable-<time>} before anything can overwrite it, and
 *       its section keeps its defaults. The other sections load as normal.
 *   <li>An encrypted file found with no cipher installed is left alone entirely.
 *   <li>Nothing is saved on shutdown unless the config was loaded first, so a
 *       client that fails before loading cannot write its defaults over the
 *       player's settings.
 * </ul>
 *
 * <p>Profiles written by the old single-file format are converted on the first
 * load, and the old files moved to {@code configs/legacy/}.
 *
 * <p>Thread-safe: every method is synchronized, since a shutdown hook saves from
 * its own thread.
 */
public final class ConfigService implements Service {

    public static final String DEFAULT_PROFILE = "default";

    /** The id of the shared section that remembers the active profile. */
    public static final String STATE_SECTION = "profile";

    private static final String PLAIN = ".json";
    private static final String SEALED = ".enc";
    private static final String LEGACY_FOLDER = "legacy";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final CoreLogger logger;
    private final EventBus bus;
    private final Platform platform;

    private final Map<String, ConfigSection> sections = new LinkedHashMap<>();
    private final Map<String, ConfigLocation> placed = new HashMap<>();
    private final Map<String, ConfigLocation> given = new HashMap<>();
    private final Map<String, ConfigCipher> ciphers = new HashMap<>();
    private final Set<String> warnedSealed = new HashSet<>();

    private String directoryName = "configs";
    private String profilesFolder = "profiles";
    private String sharedFolder = "shared";
    private Path root;
    private Path profilesRoot;
    private Path sharedRoot;

    private String activeProfile = DEFAULT_PROFILE;
    private boolean loaded;
    private boolean migrated;

    public ConfigService(CoreLogger logger, EventBus bus, Platform platform) {
        this.logger = Validate.notNull(logger, "logger");
        this.bus = Validate.notNull(bus, "bus");
        this.platform = Validate.notNull(platform, "platform");
        register(new StateSection(), ConfigLocation.shared(STATE_SECTION));
    }

    @Override
    public String getName() {
        return "Config";
    }

    @Override
    public synchronized void start() throws IOException {
        root = platform.getDataDirectory().toPath().resolve(directoryName).normalize();
        profilesRoot = root.resolve(profilesFolder).normalize();
        sharedRoot = root.resolve(sharedFolder).normalize();
        Validate.check(!profilesRoot.startsWith(sharedRoot) && !sharedRoot.startsWith(profilesRoot),
                "the profiles folder and the shared folder must not contain one another");
        Files.createDirectories(profilesRoot);
        Files.createDirectories(sharedRoot);
    }

    @Override
    public synchronized void stop() {
        // Persist on the way out, but only what was loaded: saving a config that
        // was never read would write defaults over the player's settings.
        if (loaded) {
            save();
        }
    }

    // --------------------------------------------------------------- layout

    /** @param path the config folder, relative to {@code Platform.getDataDirectory()}; {@code "configs"} unless set */
    public synchronized void setDirectory(String path) {
        requireNotStarted();
        directoryName = ConfigLocation.validate(path);
    }

    /** @param path the folder profiles live in, relative to the config folder; {@code "profiles"} unless set */
    public synchronized void setProfilesFolder(String path) {
        requireNotStarted();
        profilesFolder = folder(path);
    }

    /** @param path the folder shared sections live in, relative to the config folder; {@code "shared"} unless set */
    public synchronized void setSharedFolder(String path) {
        requireNotStarted();
        sharedFolder = folder(path);
    }

    /** @return the config folder, for files of your own. Available once started */
    public synchronized Path getDirectory() {
        requireStarted();
        return root;
    }

    public synchronized Path getProfilesDirectory() {
        requireStarted();
        return profilesRoot;
    }

    public synchronized Path getSharedDirectory() {
        requireStarted();
        return sharedRoot;
    }

    /** @return the folder a profile's sections are saved in, whether or not it exists yet */
    public synchronized Path getProfileDirectory(String profile) {
        requireStarted();
        return profileDir(profile);
    }

    // ------------------------------------------------------------- sections

    /**
     * Registers a section in every profile, saved under its id.
     *
     * @throws IllegalArgumentException if a section with its id is registered
     *         already, or its id is not a valid file name
     */
    public synchronized <S extends ConfigSection> S register(S section) {
        return register(section, null);
    }

    /**
     * Registers a section at {@code location}, unless {@link #place} has already
     * put its id somewhere else. Registering after the config has loaded loads the
     * section straight away.
     *
     * @param location null for the default, in every profile under the section's id
     */
    public synchronized <S extends ConfigSection> S register(S section, ConfigLocation location) {
        Validate.notNull(section, "section");
        String id = Validate.notBlank(section.getId(), "section id");
        Validate.check(!sections.containsKey(id), "a config section with id \"" + id + "\" is already registered");
        ConfigLocation resolved = placed.get(id);
        if (resolved == null) {
            resolved = location != null ? location : defaultLocation(id);
        }
        checkFree(id, resolved);
        if (location != null) {
            given.put(id, location);
        }
        sections.put(id, section);
        if (loaded) {
            loadSection(id, section, resolved, activeProfile);
        }
        return section;
    }

    /**
     * Saves the section with this id at {@code location}, whether it is
     * registered yet or not, and whoever registers it. How you move Core's own
     * sections. Takes effect from the next save or load.
     *
     * @throws IllegalArgumentException if another section is saved there
     */
    public synchronized void place(String id, ConfigLocation location) {
        Validate.notBlank(id, "id");
        Validate.notNull(location, "location");
        checkFree(id, location);
        placed.put(id, location);
    }

    /**
     * Encrypts the section with this id from its next save on, wherever it is
     * placed. Optional; nothing is encrypted unless you call this.
     *
     * <p>Call it before the config loads to read an existing encrypted file. A
     * plaintext file the section had is still read once and is deleted when the
     * encrypted one is first written.
     *
     * @param cipher null to stop encrypting it: it is then saved as {@code .json},
     *        and the old {@code .enc} is left where it is
     */
    public synchronized void encrypt(String id, ConfigCipher cipher) {
        Validate.notBlank(id, "id");
        if (cipher == null) {
            ciphers.remove(id);
        } else {
            ciphers.put(id, cipher);
        }
    }

    public synchronized boolean isEncrypted(String id) {
        return ciphers.containsKey(id);
    }

    /** @return where the section with this id is saved, registered or not */
    public synchronized ConfigLocation getLocation(String id) {
        return locationOf(id);
    }

    /** @return every registered section by id, in registration order */
    public synchronized Map<String, ConfigSection> getSections() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(sections));
    }

    // ------------------------------------------------------------- profiles

    public synchronized String getActiveProfile() {
        return activeProfile;
    }

    /** @return the profiles on disk, by folder name, sorted */
    public synchronized List<String> listProfiles() {
        requireStarted();
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(profilesRoot)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    names.add(entry.getFileName().toString());
                }
            }
        } catch (IOException e) {
            logger.error("Could not list config profiles in " + profilesRoot, e);
        }
        Collections.sort(names);
        return names;
    }

    public synchronized boolean exists(String profile) {
        requireStarted();
        return Files.isDirectory(profileDir(profile));
    }

    /** Saves everything: the shared sections, and the active profile's. */
    public synchronized void save() {
        requireStarted();
        saveScope(true, activeProfile);
        saveScope(false, activeProfile);
    }

    /** Saves the current state into {@code profile}'s sections, without switching to it. */
    public synchronized void save(String profile) {
        requireStarted();
        saveScope(false, sanitize(profile));
    }

    /** Saves the shared sections only. */
    public synchronized void saveShared() {
        requireStarted();
        saveScope(true, activeProfile);
    }

    /** Saves the current state as {@code profile} and switches to it. */
    public synchronized void saveAs(String profile) {
        requireStarted();
        String name = sanitize(profile);
        saveScope(false, name);
        activeProfile = name;
        saveState();
    }

    /**
     * Loads everything: the shared sections, then the profile that was active
     * last, or {@value #DEFAULT_PROFILE}. A section with no file keeps its values.
     */
    public synchronized void load() {
        requireStarted();
        loadShared();
        loadScope(false, activeProfile);
        finishLoad();
    }

    /**
     * Switches to {@code profile} and loads its sections. Shared sections are not
     * reloaded, since they belong to every profile. A profile with no folder yet
     * keeps the current values, and is created on the next save.
     */
    public synchronized void load(String profile) {
        requireStarted();
        if (!loaded) {
            loadShared();
        }
        activeProfile = sanitize(profile);
        loadScope(false, activeProfile);
        saveState();
        finishLoad();
    }

    /** Deletes a profile's folder and everything in it. */
    public synchronized boolean delete(String profile) {
        requireStarted();
        Path dir = profileDir(profile);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>();
            walk.forEach(paths::add);
            paths.sort(Comparator.reverseOrder());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException e) {
            logger.error("Failed to delete config profile " + profile, e);
            return false;
        }
    }

    // ------------------------------------------------------------ internals

    private void loadShared() {
        migrateLegacy();
        loadScope(true, activeProfile);
        if (!DEFAULT_PROFILE.equals(activeProfile) && !Files.isDirectory(profileDir(activeProfile))) {
            logger.warn("The last active config profile \"" + activeProfile + "\" is gone; using "
                    + DEFAULT_PROFILE);
            activeProfile = DEFAULT_PROFILE;
        }
    }

    private void finishLoad() {
        loaded = true;
        logger.info("Loaded config profile " + activeProfile);
        // Settings were applied silently, so anything holding derived state
        // rebuilds here rather than going stale.
        bus.post(new ConfigLoadEvent(activeProfile));
    }

    private void saveState() {
        write(STATE_SECTION, sections.get(STATE_SECTION), locationOf(STATE_SECTION), activeProfile);
    }

    private void saveScope(boolean shared, String profile) {
        if (!shared) {
            try {
                Files.createDirectories(profileDir(profile));
            } catch (IOException e) {
                logger.error("Could not create config profile folder for " + profile, e);
                return;
            }
        }
        for (Map.Entry<String, ConfigSection> entry : sections.entrySet()) {
            ConfigLocation location = locationOf(entry.getKey());
            if (location.isShared() == shared) {
                write(entry.getKey(), entry.getValue(), location, profile);
            }
        }
    }

    private void write(String id, ConfigSection section, ConfigLocation location, String profile) {
        JsonObject json;
        try {
            json = section.save();
        } catch (RuntimeException e) {
            logger.error("Failed to serialise config section " + id, e);
            return;
        }
        writeJson(id, json, location, profile);
    }

    private void writeJson(String id, JsonObject json, ConfigLocation location, String profile) {
        byte[] bytes = Json.toBytes(json);
        ConfigCipher cipher = ciphers.get(id);
        Path target = file(location, profile, cipher != null);
        try {
            if (cipher != null) {
                Json.writeAtomically(target, cipher.encrypt(bytes, location.context()));
                // The plaintext it replaces must not outlive it.
                Files.deleteIfExists(file(location, profile, false));
            } else {
                Json.writeAtomically(target, bytes);
            }
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            logger.error("Failed to save config section " + id + " to " + target, e);
        }
    }

    private void loadScope(boolean shared, String profile) {
        for (Map.Entry<String, ConfigSection> entry : new ArrayList<>(sections.entrySet())) {
            ConfigLocation location = locationOf(entry.getKey());
            if (location.isShared() == shared) {
                loadSection(entry.getKey(), entry.getValue(), location, profile);
            }
        }
    }

    private void loadSection(String id, ConfigSection section, ConfigLocation location, String profile) {
        JsonObject json = read(id, location, profile);
        if (json == null) {
            return;
        }
        try {
            section.load(json);
        } catch (RuntimeException e) {
            // One bad section must not abort the rest of the load.
            logger.error("Failed to load config section " + id, e);
        }
    }

    /** @return the section's saved state, or null when it has none that can be read */
    private JsonObject read(String id, ConfigLocation location, String profile) {
        Path plain = file(location, profile, false);
        Path sealed = file(location, profile, true);
        ConfigCipher cipher = ciphers.get(id);
        Path reading = cipher != null && Files.exists(sealed) ? sealed : plain;
        try {
            if (reading == sealed) {
                return Json.parse(cipher.decrypt(Files.readAllBytes(sealed), location.context()));
            }
            if (Files.exists(plain)) {
                return Json.parse(Files.readAllBytes(plain));
            }
            if (cipher == null && Files.exists(sealed) && warnedSealed.add(id)) {
                logger.error("Config section " + id + " is encrypted at " + sealed + " but has no cipher, so it"
                        + " keeps its defaults and the file is left alone. Call Core.config().encrypt(\"" + id
                        + "\", cipher) before the config loads.");
            }
            return null;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            Path kept = keepAside(reading);
            logger.error("Could not read config section " + id + " from " + reading
                    + "; it keeps its defaults" + (kept != null ? ", and the file was kept as " + kept : ""), e);
            return null;
        }
    }

    /** Copies an unreadable file aside, so the next save cannot destroy what was in it. */
    private Path keepAside(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        String base = file.getFileName() + ".unreadable-" + LocalDateTime.now().format(STAMP);
        Path target = file.resolveSibling(base);
        for (int i = 1; Files.exists(target); i++) {
            target = file.resolveSibling(base + "-" + i);
        }
        try {
            Files.copy(file, target);
            return target;
        } catch (IOException e) {
            logger.error("Could not keep a copy of unreadable config " + file, e);
            return null;
        }
    }

    /**
     * Converts profiles saved by the old format &mdash; one file per profile,
     * directly in the config folder, every section a key in it &mdash; into the
     * current layout, once. A file already in the new layout is never overwritten.
     */
    private void migrateLegacy() {
        if (migrated) {
            return;
        }
        migrated = true;
        List<Path> legacy = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, "*" + PLAIN)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    legacy.add(file);
                }
            }
        } catch (IOException e) {
            logger.error("Could not look for old config profiles in " + root, e);
            return;
        }
        // The profile that would have been active wins any shared section.
        legacy.sort(Comparator.comparing((Path file) -> !profileName(file).equals(activeProfile))
                .thenComparing(file -> file.getFileName().toString()));
        for (Path file : legacy) {
            String profile = sanitize(profileName(file));
            JsonObject json;
            try {
                json = Json.read(file);
            } catch (IOException e) {
                logger.error("Could not convert old config profile " + file + "; it was left where it is", e);
                continue;
            }
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                ConfigLocation location;
                try {
                    location = locationOf(entry.getKey());
                } catch (IllegalArgumentException e) {
                    logger.warn("Old config section \"" + entry.getKey() + "\" has no valid file name; skipped");
                    continue;
                }
                if (!Files.exists(file(location, profile, false)) && !Files.exists(file(location, profile, true))) {
                    writeJson(entry.getKey(), entry.getValue().getAsJsonObject(), location, profile);
                }
            }
            try {
                Path moved = root.resolve(LEGACY_FOLDER).resolve(file.getFileName());
                Files.createDirectories(moved.getParent());
                Files.move(file, moved);
                logger.info("Converted old config profile " + profile + "; the original is in " + moved);
            } catch (IOException e) {
                logger.error("Converted old config profile " + profile + " but could not move " + file, e);
            }
        }
    }

    private ConfigLocation locationOf(String id) {
        ConfigLocation location = placed.get(id);
        if (location == null) {
            location = given.get(id);
        }
        return location != null ? location : defaultLocation(id);
    }

    private static ConfigLocation defaultLocation(String id) {
        try {
            return ConfigLocation.profile(id);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("config section id \"" + id + "\" is not a valid file name;"
                    + " register it with a ConfigLocation", e);
        }
    }

    private void checkFree(String id, ConfigLocation location) {
        Set<String> others = new LinkedHashSet<>(sections.keySet());
        others.addAll(placed.keySet());
        others.remove(id);
        for (String other : others) {
            Validate.check(!locationOf(other).equals(location),
                    "config sections \"" + other + "\" and \"" + id + "\" would both be saved to " + location);
        }
    }

    private Path file(ConfigLocation location, String profile, boolean sealed) {
        Path base = location.isShared() ? sharedRoot : profileDir(profile);
        return base.resolve(location.getPath() + (sealed ? SEALED : PLAIN));
    }

    private Path profileDir(String profile) {
        return profilesRoot.resolve(sanitize(profile));
    }

    /** Keeps a profile name to one folder inside the profiles folder. */
    private static String sanitize(String profile) {
        Validate.notNull(profile, "profile");
        String safe = profile.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) {
            return DEFAULT_PROFILE;
        }
        // "." and ".." are valid characters but not valid profiles.
        return safe.matches("\\.+") ? safe.replace('.', '_') : safe;
    }

    private static String profileName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - PLAIN.length());
    }

    private static String folder(String path) {
        String folder = ConfigLocation.validate(path);
        Validate.check(!folder.equalsIgnoreCase(LEGACY_FOLDER), "\"" + LEGACY_FOLDER + "\" is reserved");
        return folder;
    }

    private void requireStarted() {
        if (root == null) {
            throw new IllegalStateException("the config service has not started");
        }
    }

    private void requireNotStarted() {
        if (root != null) {
            throw new IllegalStateException("the config layout is fixed once Core starts; set it before start()");
        }
    }

    /** Remembers which profile was active, so a restart comes back to it. */
    private final class StateSection implements ConfigSection {

        @Override
        public String getId() {
            return STATE_SECTION;
        }

        @Override
        public JsonObject save() {
            JsonObject json = new JsonObject();
            json.addProperty("active", activeProfile);
            return json;
        }

        @Override
        public void load(JsonObject json) {
            if (json.has("active") && json.get("active").isJsonPrimitive()) {
                activeProfile = sanitize(json.get("active").getAsString());
            }
        }
    }
}
