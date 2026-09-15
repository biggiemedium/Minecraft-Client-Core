package dev.px.core.account;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.px.core.concurrent.ThreadService;
import dev.px.core.config.ConfigSection;
import dev.px.core.registry.Registry;
import dev.px.core.service.Service;
import dev.px.core.util.CoreLogger;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.function.Consumer;

/**
 * The saved accounts and the login flow.
 *
 * <p>Logins run on the thread pool, because the OAuth exchange blocks for
 * seconds and doing it on the game thread freezes the client. The callback is
 * handed back for the caller to marshal onto the game thread, since only the
 * caller knows how its UI wants to be updated.
 *
 * <p>Applying the session is the one part that must happen on the game thread,
 * so {@link #login} does not do it: it returns the result and the caller applies
 * it through {@link #applySession} from wherever is safe.
 */
@Getter
@RequiredArgsConstructor
public final class AccountService implements Service, ConfigSection {

    private final CoreLogger logger;
    private final ThreadService threads;

    private final Registry<Account> accounts = new Registry<>();

    /** Installed by the adapter. Without it, only the saved list works. */
    @Setter
    private AuthProvider provider;

    @Override
    public String getName() {
        return "Accounts";
    }

    @Override
    public String getId() {
        return "accounts";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Class<? extends Service>[] dependsOn() {
        return new Class[] { ThreadService.class };
    }

    @Override
    public void start() {
    }

    public Account add(Account account) {
        return accounts.register(account);
    }

    public boolean remove(Account account) {
        return accounts.unregister(account);
    }

    /**
     * Authenticates an account off the game thread.
     *
     * <p>The callback runs on a worker thread. Marshal to the game thread before
     * touching any game state, and call {@link #applySession} from there.
     */
    public void login(Account account, Consumer<AuthResult> callback) {
        if (provider == null) {
            callback.accept(AuthResult.failure("No authentication provider is installed"));
            return;
        }
        threads.submit(() -> {
            AuthResult result;
            try {
                result = account.isOffline()
                        ? provider.authenticateOffline(account.getName())
                        : provider.authenticate(account);
            } catch (Exception e) {
                logger.error("Login failed for " + account.getName(), e);
                result = AuthResult.failure("Login failed: " + e.getMessage());
            }
            if (result.isSuccessful()) {
                account.setLastUsedAt(System.currentTimeMillis());
            }
            callback.accept(result);
        });
    }

    /** Applies a successful login. Must be called on the game thread. */
    public void applySession(Session session) {
        if (provider == null) {
            throw new IllegalStateException("No authentication provider is installed");
        }
        provider.apply(session);
    }

    @Override
    public JsonObject save() {
        JsonArray array = new JsonArray();
        for (Account account : accounts) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", account.getName());
            entry.addProperty("type", account.getType().name());
            entry.addProperty("lastUsedAt", account.getLastUsedAt());
            if (account.getEmail() != null) {
                entry.addProperty("email", account.getEmail());
            }
            if (!account.getMetadata().isEmpty()) {
                JsonObject meta = new JsonObject();
                for (Map.Entry<String, String> pair : account.getMetadata().entrySet()) {
                    meta.addProperty(pair.getKey(), pair.getValue());
                }
                entry.add("metadata", meta);
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
        for (Account existing : accounts.all()) {
            accounts.unregister(existing);
        }
        for (JsonElement element : json.getAsJsonArray("entries")) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            if (!entry.has("name")) {
                continue;
            }
            String name = entry.get("name").getAsString();
            if (name.trim().isEmpty() || accounts.contains(name)) {
                continue;
            }
            AccountType type = entry.has("type")
                    ? parseType(entry.get("type").getAsString())
                    : AccountType.OFFLINE;
            Account account = new Account(name, type);
            if (entry.has("email")) {
                account.setEmail(entry.get("email").getAsString());
            }
            if (entry.has("lastUsedAt")) {
                account.setLastUsedAt(entry.get("lastUsedAt").getAsLong());
            }
            if (entry.has("metadata") && entry.get("metadata").isJsonObject()) {
                for (Map.Entry<String, JsonElement> pair : entry.getAsJsonObject("metadata").entrySet()) {
                    account.setMeta(pair.getKey(), pair.getValue().getAsString());
                }
            }
            accounts.register(account);
        }
    }

    private static AccountType parseType(String name) {
        try {
            return AccountType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return AccountType.OFFLINE;
        }
    }
}
