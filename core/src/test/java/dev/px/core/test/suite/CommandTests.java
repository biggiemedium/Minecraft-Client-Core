package dev.px.core.test.suite;

import dev.px.core.event.impl.ChatSendEvent;
import dev.px.core.test.harness.Checks;
import dev.px.core.test.harness.TestClient;

import java.util.List;

/**
 * Chat command dispatch and argument handling.
 *
 * <p>Worth noting what this suite would have caught: the old client's command
 * manager registered a chat listener whose body was empty, so no command it
 * registered ever ran.
 */
public final class CommandTests {

    private CommandTests() {
    }

    public static void run(TestClient client) {
        Checks.section("Commands");
        client.reset();
        client.getKillAura().disable();

        // ---- dispatch ---------------------------------------------------------
        ChatSendEvent command = client.getCore().getBus().post(new ChatSendEvent(".toggle Kill Aura"));
        Checks.check("a prefixed line is consumed before reaching the server", command.isCancelled());
        Checks.check("the command ran", client.getKillAura().isEnabled());
        Checks.checkEquals("it replied to the user",
                "Kill Aura enabled", client.getPlatform().lastMessage());

        client.getCore().getBus().post(new ChatSendEvent(".t Kill Aura"));
        Checks.check("an alias dispatches to the same command", !client.getKillAura().isEnabled());

        ChatSendEvent chat = client.getCore().getBus().post(new ChatSendEvent("hello everyone"));
        Checks.check("ordinary chat passes through untouched", !chat.isCancelled());

        ChatSendEvent looksLike = client.getCore().getBus().post(new ChatSendEvent("...hmm"));
        Checks.check("a prefixed line that matches nothing is still consumed", looksLike.isCancelled());
        Checks.check("and says so",
                client.getPlatform().lastMessage().startsWith("Unknown command"));

        // ---- arguments ----------------------------------------------------------
        client.getPlatform().clearMessages();
        client.getCore().getBus().post(new ChatSendEvent(".echo 2 repeat me please"));
        Checks.checkEquals("a typed argument is parsed", 2f, client.getEchoCommand().getLastCount());
        Checks.checkEquals("trailing text is taken whole",
                "repeat me please", client.getEchoCommand().getLastText());
        Checks.checkEquals("the body ran the right number of times",
                2f, client.getPlatform().getMessages().size());

        client.getPlatform().clearMessages();
        client.getCore().getBus().post(new ChatSendEvent(".echo notanumber text"));
        Checks.checkEquals("a bad argument becomes a readable message, not a crash",
                "notanumber is not a whole number", client.getPlatform().lastMessage());

        client.getPlatform().clearMessages();
        client.getCore().getBus().post(new ChatSendEvent(".echo"));
        Checks.checkEquals("a missing argument is reported by position",
                "Missing argument 1", client.getPlatform().lastMessage());

        client.getPlatform().clearMessages();
        client.getCore().getBus().post(new ChatSendEvent(".toggle"));
        Checks.check("failUsage prints the declared usage",
                client.getPlatform().lastMessage().startsWith("Usage: toggle"));

        client.getPlatform().clearMessages();
        client.getCore().getBus().post(new ChatSendEvent(".toggle No Such Module"));
        Checks.checkEquals("a CommandException reaches the user verbatim",
                "No module called No Such Module", client.getPlatform().lastMessage());

        // ---- completion -----------------------------------------------------------
        List<String> commands = client.getCore().getCommandRegistry().complete("t");
        Checks.check("completing a partial name suggests commands", commands.contains("toggle"));

        List<String> modules = client.getCore().getCommandRegistry().complete("toggle ");
        Checks.check("a command supplies its own argument suggestions",
                modules.contains("Kill Aura"));

        // ---- the prefix -------------------------------------------------------------
        client.getCore().getCommandRegistry().getPrefix().set("!");
        ChatSendEvent oldPrefix = client.getCore().getBus().post(new ChatSendEvent(".toggle Sprint"));
        Checks.check("the old prefix stops being special", !oldPrefix.isCancelled());

        ChatSendEvent newPrefix = client.getCore().getBus().post(new ChatSendEvent("!toggle Sprint"));
        Checks.check("the new prefix dispatches", newPrefix.isCancelled());
        client.getCore().getCommandRegistry().getPrefix().set(".");

        // ---- direct invocation ---------------------------------------------------------
        client.getPlatform().clearMessages();
        Checks.check("dispatch can be called without chat at all",
                client.getCore().getCommandRegistry().dispatch("echo 1 direct"));
        Checks.checkEquals("and behaves identically", "direct", client.getPlatform().lastMessage());

        Checks.check("dispatching an unknown command reports false",
                !client.getCore().getCommandRegistry().dispatch("nope"));
    }
}
