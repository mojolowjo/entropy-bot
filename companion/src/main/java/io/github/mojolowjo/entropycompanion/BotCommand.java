package io.github.mojolowjo.entropycompanion;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /bot <text>} and its alias (config {@code commandAlias}, default {@code /b}) as client commands: NeoForge
 * runs them in the owner's client (ClientCommandHandler) before anything is sent, so the text never reaches the
 * server or other players. A client command shadows a server command of the same name in this client only.
 */
final class BotCommand {
    private BotCommand() {}

    static void register(RegisterClientCommandsEvent e, Companion c, String alias) {
        CommandDispatcher<CommandSourceStack> d = e.getDispatcher();
        d.register(build("bot", c));
        if (alias != null && !alias.isEmpty() && !alias.equals("bot")) d.register(build(alias, c));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name, Companion c) {
        return Commands.literal(name)
                .executes(ctx -> {
                    c.handle("");
                    return 1;
                })
                .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                    c.handle(StringArgumentType.getString(ctx, "text"));
                    return 1;
                }));
    }
}
