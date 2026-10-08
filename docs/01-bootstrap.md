## 1. Bootstrap

```java
Core core = Core.builder("LeapFrog", "2.0")
        .platform(new ForgePlatform())      // the only required piece
        .logger(new Log4jLogger(LOGGER))    // optional, defaults to console
        .build();

core.getCategories().registerAll(Categories.class);
core.getThemeService().register(Theme.of("Froggy", Color.rgb(0xADF773), Color.rgb(0x80F393)));
core.getModuleRegistry().registerAll(new KillAura(), new Sprint(), new Fullbright());
core.getCommandRegistry().registerAll(new ToggleCommand());
core.getHudService().registerAll(new WatermarkElement(), new ClockElement());
core.getEntityService().registerAll(new LivingTracker(), new CrystalTracker());   // §12

Render.install(new NanoVGRender2D());       // your 2D library
Render.install(new LegacyRender3D());       // world-space drawing

core.start();
core.installShutdownHook();                 // saves config on exit
```

Register **between** `build()` and `start()` — categories must exist before
modules resolve against them. `start()` orders service startup itself, applies
module defaults, then loads the saved config over the top.

Afterwards everything is reachable statically:

```java
Core.modules().get(KillAura.class);
Core.notifications().success("Config", "Saved");
Core.themes().getPrimary();
Core.bus().post(new PlayerMoveEvent(x, y, z));
```

Categories are an interface so Core does not dictate yours:

```java
public enum Categories implements Category {
    COMBAT("Combat"), MOVEMENT("Movement"), RENDER("Render");

    private final String name;
    Categories(String name) { this.name = name; }
    public String getName() { return name; }
}
```

### Config: where everything is saved

`start()` loads the saved config and `stop()` saves it. Each **section** —
modules, HUD, theme, friends, accounts, and any of yours — is its
own JSON file, in every profile or shared by all of them:

```
configs/                          setDirectory(...), under Platform.getDataDirectory()
  profiles/                       setProfilesFolder(...)
    default/
      modules.json
      hud.json
      myclient/waypoints.json     a section of yours, in a folder of its own
    hypixel/...
  shared/                         setSharedFolder(...): the same for every profile
    accounts.enc                  encrypted, because you asked
    friends.json
    profile.json                  which profile was active last
```

The layout is yours. Name the folders before `start()`, put your own sections
anywhere, and move Core's:

```java
ConfigService config = core.getConfigService();
config.setDirectory("leapfrog");                       // instead of configs/
config.register(new SettingsSection("waypoints", waypoints), ConfigLocation.profile("myclient/waypoints"));
config.register(new MySection(), ConfigLocation.shared("stats"));
config.place("hud", ConfigLocation.profile("visual/hud"));        // move one of Core's
config.place("accounts", ConfigLocation.shared("private/accounts"));
```

A path is folder and file names separated by `/` — letters, digits, `.`, `_`,
`-` — so it can make folders but never leave the config folder. `place` wins
over a location given at registration, and two sections can never share a file.
Friends and accounts are shared by default: switching profile changes how you
play, not who your alts are.

Profiles: `load(name)` switches profile and loads its sections, `saveAs(name)`
copies the current state into a new one, `save()` writes everything,
`listProfiles()` and `delete(name)` do what they say. The last active profile is
remembered across restarts. `getProfileDirectory(name)` and
`getSharedDirectory()` are there for files of your own.

**Encryption is optional, and yours.** Nothing is encrypted unless you give a
section a `ConfigCipher`; it is then written as `.enc`, and the `.json` it
replaces is deleted once the encrypted file is written:

```java
byte[] salt = loadOrCreateSalt();                                   // yours; a salt need not be secret
SecretKey key = AesGcmCipher.deriveKey(passphrase, salt);           // PBKDF2-HMAC-SHA256
config.encrypt("accounts", AesGcmCipher.of(key));                   // before start(), to read it back
```

`AesGcmCipher` is AES-GCM from the JDK, with nothing to add to your build. Each
write gets a fresh nonce, and the file's location is bound in, so an altered file
— or one copied into another section's place — fails to decrypt instead of
loading. Implement `ConfigCipher` yourself for anything else, such as the
operating system's credential store.

**Where the key comes from decides what the encryption is worth**, which is why
Core does not choose. A passphrase the player types protects the file from
anyone without it. A key from the OS credential store protects it from other
users of the machine. A key file beside the configs only stops it being read by
accident — pasted into a support channel, synced somewhere — since anything that
can read one can read the other.

**Nothing is lost quietly.**

- Every file is written to a temporary file and renamed over the old one, so a
  crash mid-save leaves the last good file, never half of one.
- A file that cannot be read — broken by a hand edit, or encrypted with another
  key — is copied aside as `<name>.unreadable-<time>` before anything can
  overwrite it. Its section keeps its defaults, and every other section loads.
- An encrypted file found with no cipher installed is left alone, with an error
  saying which `encrypt(...)` call is missing.
- Nothing is saved on shutdown unless the config was loaded first.

Profiles saved by the old single-file format are converted on the first load,
and the originals are moved to `configs/legacy/`.
