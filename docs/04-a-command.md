## 4. A command

```java
@CommandInfo(name = "toggle", aliases = {"t"}, usage = "toggle <module>")
public final class ToggleCommand extends Command {

    @Override
    public void execute(CommandContext ctx) {
        Module module = Core.modules().find(ctx.rest(0))
                .orElseThrow(() -> new CommandException("No such module"));
        module.toggle();
        ctx.reply(module.getName() + (module.isEnabled() ? " enabled" : " disabled"));
    }
}
```

`ctx.get(i)` / `getInt(i)` / `getBoolean(i)` / `rest(i)` validate for you and
throw `CommandException`, which the registry turns into a chat message — so the
happy path needs no error plumbing. The prefix (default `.`) is a persisted
setting; matching chat lines are cancelled before reaching the server.
