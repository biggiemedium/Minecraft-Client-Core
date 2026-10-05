package dev.px.core.test.suite;

import com.google.gson.JsonObject;
import dev.px.core.config.crypto.AesGcmCipher;
import dev.px.core.config.crypto.ConfigCipher;
import dev.px.core.config.ConfigLocation;
import dev.px.core.config.ConfigSection;
import dev.px.core.config.ConfigService;
import dev.px.core.event.bus.CoreEventBus;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.FakePlatform;
import dev.px.core.test.harness.RecordingLogger;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where config is written, and how it survives: the folder layout, placing
 * sections, profiles, atomic saves, unreadable files, optional encryption, and
 * converting the old single-file profiles.
 *
 * <p>Each check runs against a throwaway folder and a fresh {@link ConfigService}
 * with plain test sections, so what is on disk is exactly what the check put
 * there. "Restarting" is a second service over the same folder.
 */
public final class ConfigStorageTests {

    private ConfigStorageTests() {
    }

    public static void run() {
        Checks.section("Config storage");

        layout();
        placement();
        profiles();
        lateRegistration();
        safety();
        encryption();
        cipher();
        migration();
    }

    // --------------------------------------------------------------- layout

    private static void layout() {
        Rig rig = new Rig("layout");
        Box alpha = rig.config.register(new Box("alpha"));
        rig.config.register(new Box("beta"), ConfigLocation.profile("deep/er/beta"));
        rig.config.register(new Box("gamma"), ConfigLocation.shared("gamma"));
        rig.start();
        alpha.value = "a";
        rig.config.load();
        rig.config.save();

        Checks.check("a section lives in each profile's folder, under its id",
                Files.exists(rig.file("profiles/default/alpha.json")));
        Checks.check("or in folders of its own", Files.exists(rig.file("profiles/default/deep/er/beta.json")));
        Checks.check("a shared one lives once, outside every profile", Files.exists(rig.file("shared/gamma.json")));
        Checks.check("and the active profile is remembered there", rig.text("shared/profile.json").contains("default"));

        Rig custom = new Rig("custom-layout");
        custom.config.setDirectory("client/settings");
        custom.config.setProfilesFolder("modes");
        custom.config.setSharedFolder("common/data");
        custom.config.register(new Box("alpha"));
        custom.config.register(new Box("gamma"), ConfigLocation.shared("gamma"));
        custom.start();
        custom.config.load();
        custom.config.save();
        Checks.check("the folders are yours to name",
                Files.exists(custom.data.resolve("client/settings/modes/default/alpha.json"))
                        && Files.exists(custom.data.resolve("client/settings/common/data/gamma.json")));
        Checks.checkThrows("and fixed once started", IllegalStateException.class,
                () -> custom.config.setProfilesFolder("other"));

        for (String bad : Arrays.asList("../escape", "/absolute", "a//b", "a b", "a/", "..", "")) {
            Checks.checkThrows("\"" + bad + "\" is not a config path", IllegalArgumentException.class,
                    () -> ConfigLocation.profile(bad));
        }
        Checks.checkThrows("\"legacy\" is reserved for converted profiles", IllegalArgumentException.class,
                () -> new Rig("reserved").config.setSharedFolder("legacy"));
        Rig nested = new Rig("nested");
        nested.config.setProfilesFolder("data");
        nested.config.setSharedFolder("data/shared");
        Checks.checkThrows("the profiles and shared folders cannot contain one another", IllegalArgumentException.class,
                nested::start);
    }

    // ------------------------------------------------------------ placement

    private static void placement() {
        Rig rig = new Rig("placement");
        rig.config.place("alpha", ConfigLocation.shared("moved/alpha"));
        rig.config.register(new Box("alpha"), ConfigLocation.profile("ignored"));
        Box beta = rig.config.register(new Box("beta"));
        rig.start();
        rig.config.load();
        rig.config.save();
        Checks.check("place() wins over the location given at registration",
                Files.exists(rig.file("shared/moved/alpha.json")) && !Files.exists(rig.file("profiles/default/ignored.json")));

        rig.config.place("beta", ConfigLocation.profile("visual/beta"));
        beta.value = "b";
        rig.config.save();
        Checks.check("and moves a section already registered, like one of Core's, from the next save",
                rig.text("profiles/default/visual/beta.json").contains("\"b\""));

        Checks.checkThrows("two sections cannot share a file", IllegalArgumentException.class,
                () -> rig.config.register(new Box("gamma"), ConfigLocation.profile("visual/beta")));
        Checks.checkThrows("nor be placed onto one", IllegalArgumentException.class,
                () -> rig.config.place("delta", ConfigLocation.shared("moved/alpha")));
        Checks.checkThrows("paths differing only in case are one file", IllegalArgumentException.class,
                () -> rig.config.place("delta", ConfigLocation.shared("MOVED/Alpha")));
        Checks.checkThrows("a second section with an id already registered is refused", IllegalArgumentException.class,
                () -> rig.config.register(new Box("beta")));
        Checks.checkThrows("an id that is no file name needs a location", IllegalArgumentException.class,
                () -> rig.config.register(new Box("has spaces")));
        rig.config.register(new Box("has spaces"), ConfigLocation.profile("spaces"));
        Checks.check("and with one it is fine", rig.config.getSections().containsKey("has spaces"));
    }

    // ------------------------------------------------------------- profiles

    private static void profiles() {
        Rig rig = new Rig("profiles");
        Box mode = rig.config.register(new Box("mode"));
        Box account = rig.config.register(new Box("account"), ConfigLocation.shared("account"));
        rig.start();
        rig.config.load();
        mode.value = "default-mode";
        account.value = "alt-1";
        rig.config.save();

        mode.value = "pvp-mode";
        rig.config.saveAs("pvp");
        Checks.checkEquals("saveAs switches to the new profile", "pvp", rig.config.getActiveProfile());
        Checks.checkEquals("which is listed", Arrays.asList("default", "pvp"), rig.config.listProfiles());

        account.value = "alt-2";
        rig.config.load("default");
        Checks.checkEquals("loading a profile loads its sections", "default-mode", mode.value);
        Checks.checkEquals("and leaves shared ones alone", "alt-2", account.value);
        rig.config.load("pvp");
        rig.config.save();

        Rig restart = rig.again();
        Box mode2 = restart.config.register(new Box("mode"));
        Box account2 = restart.config.register(new Box("account"), ConfigLocation.shared("account"));
        restart.start();
        restart.config.load();
        Checks.check("a restart comes back to the profile last active",
                "pvp".equals(restart.config.getActiveProfile()) && "pvp-mode".equals(mode2.value)
                        && "alt-2".equals(account2.value));

        Checks.check("deleting a profile removes its folder",
                restart.config.delete("pvp") && !Files.exists(restart.file("profiles/pvp")) && !restart.config.delete("pvp"));
        Rig again = restart.again();
        again.config.register(new Box("mode"));
        again.start();
        again.config.load();
        Checks.checkEquals("a remembered profile that is gone falls back to default", "default",
                again.config.getActiveProfile());
        Checks.check("with a warning", again.logger.loggedWarning("is gone"));

        again.config.save("..");
        again.config.save("../../escape");
        Checks.check("a profile name never leaves the profiles folder",
                !Files.exists(again.data.resolve("escape")) && !Files.exists(again.file("escape"))
                        && again.config.getProfileDirectory("..").getParent().equals(again.config.getProfilesDirectory()));
    }

    private static void lateRegistration() {
        Rig rig = new Rig("late");
        Box first = rig.config.register(new Box("late"));
        rig.start();
        rig.config.load();
        first.value = "saved";
        rig.config.save();

        Rig restart = rig.again();
        restart.start();
        restart.config.load();
        Box late = restart.config.register(new Box("late"));
        Checks.checkEquals("a section registered after the load is loaded at once", "saved", late.value);
    }

    // --------------------------------------------------------------- safety

    private static void safety() {
        Rig rig = new Rig("safety");
        Box good = rig.config.register(new Box("good"));
        Box bad = rig.config.register(new Box("bad"));
        rig.start();
        rig.config.load();
        good.value = "kept";
        bad.value = "precious";
        rig.config.save();
        Checks.check("a save leaves no temporary files", rig.list("profiles/default").stream().noneMatch(n -> n.endsWith(".tmp")));

        rig.write("profiles/default/bad.json", "{ \"value\": \"precious\", oops");
        Rig restart = rig.again();
        Box good2 = restart.config.register(new Box("good"));
        Box bad2 = restart.config.register(new Box("bad"));
        restart.start();
        restart.config.load();
        Checks.check("an unreadable file costs only its own section",
                "kept".equals(good2.value) && "default".equals(bad2.value));
        Checks.check("which is logged", restart.logger.loggedError("Could not read config section bad"));
        String kept = restart.list("profiles/default").stream().filter(n -> n.startsWith("bad.json.unreadable-"))
                .findFirst().orElse(null);
        Checks.check("and copied aside before anything can overwrite it",
                kept != null && restart.text("profiles/default/" + kept).contains("precious"));
        restart.config.stop();
        Checks.check("so the save on the way out loses nothing", restart.text("profiles/default/" + kept).contains("precious"));

        Rig unloaded = rig.again();
        Box never = unloaded.config.register(new Box("good"));
        unloaded.start();
        never.value = "default-never-loaded";
        unloaded.config.stop();
        Checks.check("a config that was never loaded is never saved over",
                unloaded.text("profiles/default/good.json").contains("kept"));
    }

    // ------------------------------------------------------------ encryption

    private static void encryption() {
        SecretKey key = key("correct horse");
        Rig rig = new Rig("encryption");
        Box plain = rig.config.register(new Box("plain"));
        Box secret = rig.config.register(new Box("secret"), ConfigLocation.shared("secret"));
        rig.start();
        rig.config.load();
        plain.value = "visible";
        secret.value = "hunter2";
        rig.config.save();
        Checks.check("nothing is encrypted unless asked", rig.text("shared/secret.json").contains("hunter2")
                && !rig.config.isEncrypted("secret"));

        rig.config.encrypt("secret", AesGcmCipher.of(key));
        rig.config.save();
        Checks.check("an encrypted section is written as .enc", Files.exists(rig.file("shared/secret.enc")));
        Checks.check("its plaintext file is deleted once that is written", !Files.exists(rig.file("shared/secret.json")));
        Checks.check("and nothing of it is readable on disk", !rig.text("shared/secret.enc").contains("hunter2"));
        Checks.check("other sections are untouched", rig.text("profiles/default/plain.json").contains("visible"));

        Rig restart = rig.again();
        restart.config.encrypt("secret", AesGcmCipher.of(key));
        Box secret2 = restart.config.register(new Box("secret"), ConfigLocation.shared("secret"));
        restart.start();
        restart.config.load();
        Checks.checkEquals("with the key it loads back", "hunter2", secret2.value);

        Rig wrong = rig.again();
        wrong.config.encrypt("secret", AesGcmCipher.of(key("wrong")));
        Box secret3 = wrong.config.register(new Box("secret"), ConfigLocation.shared("secret"));
        wrong.start();
        wrong.config.load();
        Checks.check("with the wrong key it keeps its defaults", "default".equals(secret3.value)
                && wrong.logger.loggedError("Could not read config section secret"));
        Checks.check("and the file is kept aside first", wrong.list("shared").stream()
                .anyMatch(n -> n.startsWith("secret.enc.unreadable-")));

        Rig keyless = rig.again();
        Box secret4 = keyless.config.register(new Box("secret"), ConfigLocation.shared("secret"));
        keyless.start();
        byte[] sealed = keyless.bytes("shared/secret.enc");
        keyless.config.load();
        keyless.config.save();
        Checks.check("with no cipher installed it keeps its defaults, and says how to fix it",
                "default".equals(secret4.value) && keyless.logger.loggedError("Call Core.config().encrypt(\"secret\""));
        Checks.check("and the encrypted file is never touched", Arrays.equals(sealed, keyless.bytes("shared/secret.enc")));

        Rig moved = new Rig("encryption-bound");
        ConfigCipher cipher = AesGcmCipher.of(key);
        moved.config.encrypt("one", cipher);
        moved.config.encrypt("two", cipher);
        Box one = moved.config.register(new Box("one"), ConfigLocation.shared("one"));
        moved.config.register(new Box("two"), ConfigLocation.shared("two"));
        moved.start();
        moved.config.load();
        one.value = "first";
        moved.config.save();
        moved.copy("shared/one.enc", "shared/two.enc");
        Rig bound = moved.again();
        bound.config.encrypt("two", cipher);
        Box two = bound.config.register(new Box("two"), ConfigLocation.shared("two"));
        bound.start();
        bound.config.load();
        Checks.check("a file copied into another section's place does not decrypt there, even with the right key",
                "default".equals(two.value));
    }

    private static void cipher() {
        try {
            byte[] salt = AesGcmCipher.newSalt();
            SecretKey a = AesGcmCipher.deriveKey("pass".toCharArray(), salt, 1000);
            SecretKey b = AesGcmCipher.deriveKey("pass".toCharArray(), salt, 1000);
            Checks.check("the same passphrase and salt give the same key", Arrays.equals(a.getEncoded(), b.getEncoded()));
            Checks.check("a fresh salt gives another",
                    !Arrays.equals(a.getEncoded(), AesGcmCipher.deriveKey("pass".toCharArray(), AesGcmCipher.newSalt(), 1000).getEncoded()));

            AesGcmCipher cipher = AesGcmCipher.of(AesGcmCipher.generateKey());
            byte[] message = "secret".getBytes(StandardCharsets.UTF_8);
            byte[] first = cipher.encrypt(message, "shared/x");
            Checks.check("each encryption differs, so equal files do not look equal",
                    !Arrays.equals(first, cipher.encrypt(message, "shared/x")));
            Checks.check("and decrypts back", Arrays.equals(message, cipher.decrypt(first, "shared/x")));
            first[first.length - 1] ^= 1;
            boolean caught = false;
            try {
                cipher.decrypt(first, "shared/x");
            } catch (java.security.GeneralSecurityException e) {
                caught = true;
            }
            Checks.check("a flipped bit is caught", caught);
            Checks.checkThrows("a key of the wrong size is refused", IllegalArgumentException.class,
                    () -> AesGcmCipher.of(new byte[10]));
        } catch (java.security.GeneralSecurityException e) {
            Checks.check("the JDK provides AES-GCM and PBKDF2 (" + e + ")", false);
        }
    }

    // ------------------------------------------------------------ migration

    private static void migration() {
        Rig rig = new Rig("migration");
        rig.write("default.json", "{\"alpha\":{\"value\":\"a-default\"},\"gamma\":{\"value\":\"g-default\"},"
                + "\"secret\":{\"value\":\"s-default\"}}");
        rig.write("pvp.json", "{\"alpha\":{\"value\":\"a-pvp\"},\"gamma\":{\"value\":\"g-pvp\"}}");
        SecretKey key = key("migrate");
        rig.config.encrypt("secret", AesGcmCipher.of(key));
        Box alpha = rig.config.register(new Box("alpha"));
        Box gamma = rig.config.register(new Box("gamma"), ConfigLocation.shared("gamma"));
        Box secret = rig.config.register(new Box("secret"), ConfigLocation.shared("secret"));
        rig.start();
        rig.config.load();

        Checks.check("old single-file profiles are converted on the first load",
                "a-default".equals(alpha.value) && rig.text("profiles/pvp/alpha.json").contains("a-pvp"));
        Checks.check("a shared section comes from the profile that was active",
                "g-default".equals(gamma.value) && !Files.exists(rig.file("profiles/pvp/gamma.json")));
        Checks.check("an encrypted one is written encrypted", "s-default".equals(secret.value)
                && Files.exists(rig.file("shared/secret.enc")) && !Files.exists(rig.file("shared/secret.json")));
        Checks.check("and the originals are moved to legacy/",
                Files.exists(rig.file("legacy/default.json")) && !Files.exists(rig.file("default.json")));

        alpha.value = "changed";
        rig.config.save();
        rig.write("default.json", "{\"alpha\":{\"value\":\"stale\"}}");
        Rig restart = rig.again();
        restart.config.encrypt("secret", AesGcmCipher.of(key));
        Box alpha2 = restart.config.register(new Box("alpha"));
        restart.start();
        restart.config.load();
        Checks.checkEquals("a converted file is never overwritten by an old one", "changed", alpha2.value);
    }

    // ------------------------------------------------------------- helpers

    private static SecretKey key(String passphrase) {
        try {
            return AesGcmCipher.deriveKey(passphrase.toCharArray(), "fixed-test-salt".getBytes(StandardCharsets.UTF_8), 1000);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A test section holding one string. */
    private static final class Box implements ConfigSection {
        private final String id;
        String value = "default";

        Box(String id) {
            this.id = id;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public JsonObject save() {
            JsonObject json = new JsonObject();
            json.addProperty("value", value);
            return json;
        }

        @Override
        public void load(JsonObject json) {
            if (json.has("value")) {
                value = json.get("value").getAsString();
            }
        }
    }

    /** A config service over a throwaway data folder. */
    private static final class Rig {
        final RecordingLogger logger = new RecordingLogger();
        final Path data;
        final ConfigService config;

        Rig(String name) {
            this(Paths.get("build/tmp/config-storage", name), true);
        }

        private Rig(Path data, boolean wipe) {
            this.data = data;
            if (wipe) {
                wipe(data);
            }
            this.config = new ConfigService(logger, new CoreEventBus(logger), new FakePlatform(data.toFile()));
        }

        /** The same folder, as a client restarted over it sees it. */
        Rig again() {
            return new Rig(data, false);
        }

        void start() {
            try {
                config.start();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        Path file(String relative) {
            return data.resolve("configs").resolve(relative);
        }

        String text(String relative) {
            return new String(bytes(relative), StandardCharsets.UTF_8);
        }

        byte[] bytes(String relative) {
            try {
                return Files.readAllBytes(file(relative));
            } catch (IOException e) {
                return new byte[0];
            }
        }

        void write(String relative, String text) {
            try {
                Files.createDirectories(file(relative).getParent());
                Files.write(file(relative), text.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        void copy(String from, String to) {
            try {
                Files.copy(file(from), file(to), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        List<String> list(String relative) {
            List<String> names = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(file(relative))) {
                for (Path entry : stream) {
                    names.add(entry.getFileName().toString());
                }
            } catch (IOException e) {
                return names;
            }
            return names;
        }

        private static void wipe(Path folder) {
            if (!Files.exists(folder)) {
                return;
            }
            try (Stream<Path> walk = Files.walk(folder)) {
                List<Path> paths = new ArrayList<>();
                walk.forEach(paths::add);
                paths.sort(Comparator.reverseOrder());
                for (Path path : paths) {
                    Files.delete(path);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
