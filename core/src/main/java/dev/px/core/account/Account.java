package dev.px.core.account;

import dev.px.core.registry.Named;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A saved account entry in the alt manager.
 *
 * <p>No credentials are stored. Microsoft accounts authenticate through the
 * platform's device-code flow at login time, so there is nothing worth writing
 * to disk and nothing to leak if a config is shared.
 *
 * <p>{@link #getMetadata()} is a free-form string map for whatever a particular
 * client tracks per account &mdash; a ban expiry, a rank, a note. Keeping it
 * open means Core does not need to know about any one server's concepts to
 * store them.
 */
@Getter
public final class Account implements Named {

    private final String name;
    private final AccountType type;

    /** Metadata the client attaches. Persisted verbatim. */
    private final Map<String, String> metadata = new LinkedHashMap<>();

    @Setter
    private String email;

    /** Epoch millis of the last successful login, or 0 if never used. */
    @Setter
    private long lastUsedAt;

    public Account(String name, AccountType type) {
        this.name = name;
        this.type = type;
    }

    public static Account offline(String name) {
        return new Account(name, AccountType.OFFLINE);
    }

    public static Account microsoft(String name, String email) {
        Account account = new Account(name, AccountType.MICROSOFT);
        account.setEmail(email);
        return account;
    }

    public boolean isOffline() {
        return type == AccountType.OFFLINE;
    }

    public String getMeta(String key, String fallback) {
        return metadata.getOrDefault(key, fallback);
    }

    public long getMetaLong(String key, long fallback) {
        String value = metadata.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void setMeta(String key, String value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }

    public void setMeta(String key, long value) {
        metadata.put(key, Long.toString(value));
    }

    @Override
    public String toString() {
        return name + " (" + type + ")";
    }
}
