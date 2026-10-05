package dev.px.core.network.anticheat;

import dev.px.core.network.server.ServerInfo;
import dev.px.core.util.Validate;
import lombok.Getter;

/**
 * Everything a {@link AntiCheatSignature} gets to look at: the server as
 * {@code Core.server()} knows it, and the pattern of its transactions.
 *
 * <p>Immutable, and a snapshot: the same object is handed to every signature in
 * one evaluation, so they all judge the same moment.
 */
@Getter
public final class ServerEvidence {

    private final ServerInfo server;
    private final TransactionPattern transactions;

    public ServerEvidence(ServerInfo server, TransactionPattern transactions) {
        this.server = Validate.notNull(server, "server");
        this.transactions = Validate.notNull(transactions, "transactions");
    }

    /** @return the brand as sent, or an empty string before it arrives */
    public String getRawBrand() {
        return server.getBrand() != null ? server.getBrand().getRaw() : "";
    }
}
