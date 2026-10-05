package dev.px.core.test.example;

import dev.px.core.command.Command;
import dev.px.core.command.CommandContext;
import dev.px.core.command.CommandInfo;
import lombok.Getter;

/** Exercises the typed argument accessors and their failure messages. */
@CommandInfo(name = "echo", aliases = {"say"}, usage = "echo <count> <text>")
public final class ExampleEchoCommand extends Command {

    @Getter
    private String lastText;

    @Getter
    private int lastCount;

    @Override
    public void execute(CommandContext context) {
        // Throws CommandException with a readable message if this is not a number.
        lastCount = context.getInt(0);
        lastText = context.rest(1);
        for (int i = 0; i < lastCount; i++) {
            context.reply(lastText);
        }
    }
}
