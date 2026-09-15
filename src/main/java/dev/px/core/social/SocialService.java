package dev.px.core.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.px.core.config.ConfigSection;
import dev.px.core.registry.Registry;
import dev.px.core.service.Service;
import lombok.Getter;

import java.util.Locale;
import java.util.Optional;

/**
 * The friend list.
 *
 * <p>Lives in Core because friendship is a client concept, not a version one:
 * targeting, nametags, chat highlighting and the alt manager all consult it, and
 * every one of those is version-specific code that should not own the list.
 *
 * <p>It is its own {@link ConfigSection}, so friends persist with everything else
 * rather than through a separate file format.
 */
@Getter
public final class SocialService implements Service, ConfigSection {

    private final Registry<Friend> friends = new Registry<>();

    @Override
    public String getName() {
        return "Social";
    }

    @Override
    public String getId() {
        return "friends";
    }

    @Override
    public void start() {
    }

    /**
     * @return whether the player is a friend. Called on every entity in a
     *         targeting loop, so it stays a plain map lookup
     */
    public boolean isFriend(String playerName) {
        return playerName != null && friends.contains(playerName);
    }

    public Optional<Friend> find(String playerName) {
        return friends.find(playerName);
    }

    /** @return the new friend, or the existing one if already added. */
    public Friend add(String playerName) {
        return friends.find(playerName).orElseGet(() -> friends.register(new Friend(playerName)));
    }

    public boolean remove(String playerName) {
        return friends.find(playerName).map(friends::unregister).orElse(false);
    }

    /** @return true if added, false if removed. Backs a single toggle command. */
    public boolean toggle(String playerName) {
        if (isFriend(playerName)) {
            remove(playerName);
            return false;
        }
        add(playerName);
        return true;
    }

    /** @return the alias to display for a player, or their real name. */
    public String displayNameFor(String playerName) {
        return find(playerName).map(Friend::getDisplayName).orElse(playerName);
    }

    @Override
    public JsonObject save() {
        JsonArray array = new JsonArray();
        for (Friend friend : friends) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", friend.getName());
            if (friend.getAlias() != null && !friend.getAlias().isEmpty()) {
                entry.addProperty("alias", friend.getAlias());
            }
            array.add(entry);
        }
        JsonObject json = new JsonObject();
        json.add("entries", array);
        return json;
    }

    @Override
    public void load(JsonObject json) {
        if (!json.has("entries") || !json.get("entries").isJsonArray()) {
            return;
        }
        // Replace wholesale: a loaded profile defines the list rather than adding to it.
        for (Friend existing : friends.all()) {
            friends.unregister(existing);
        }
        for (JsonElement element : json.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("name")) {
                continue;
            }
            String playerName = entry.get("name").getAsString();
            if (playerName.trim().isEmpty() || friends.contains(playerName)) {
                continue;
            }
            Friend friend = add(playerName);
            if (entry.has("alias")) {
                friend.setAlias(entry.get("alias").getAsString());
            }
        }
    }

    /** @return the name lowercased, the form used for lookups. */
    public static String key(String playerName) {
        return playerName == null ? "" : playerName.toLowerCase(Locale.ROOT);
    }
}
