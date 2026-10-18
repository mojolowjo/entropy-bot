package io.github.mojolowjo.entropybot.ai.live;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.commands.VerbTable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * 0.35.2: commands typed in the bot's own game window: {@code /ai <request>} and {@code /bot <any bot command>}. They run
 * exactly as a PM from the owner ({@code Commands.localLine}: same gate, same owner rights) and never reach the server.
 * Tab completes the verbs.
 *
 * <p>Loader API: NeoForge's {@code RegisterClientCommandsEvent} (registered in EntropyBot); the tree is vanilla
 * Brigadier. Fabric: {@code ClientCommandRegistrationCallback} with {@code ClientCommandManager.literal/argument}
 * (assumed from memory, not checked). Forge 1.21.1: {@code RegisterClientCommandsEvent} too.
 */
public final class LocalCommands {
    private LocalCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(net.minecraft.commands.Commands.literal("ai")
                .executes(c -> send(c, "ai status"))
                .then(net.minecraft.commands.Commands.argument("request", StringArgumentType.greedyString())
                        .executes(c -> send(c, "ai " + StringArgumentType.getString(c, "request")))));
        d.register(net.minecraft.commands.Commands.literal("bot")
                .executes(c -> send(c, "help"))
                .then(net.minecraft.commands.Commands.argument("command", StringArgumentType.greedyString())
                        .suggests(LocalCommands::verbs)
                        .executes(c -> send(c, StringArgumentType.getString(c, "command")))));
    }

    private static CompletableFuture<Suggestions> verbs(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        String typed = b.getRemaining().toLowerCase(Locale.ROOT);
        if (typed.contains(" ")) return b.buildFuture();          // only the first word
        for (VerbTable.Verb v : VerbTable.all()) if (v.name().startsWith(typed)) b.suggest(v.name());
        return b.buildFuture();
    }

    private static int send(CommandContext<CommandSourceStack> c, String line) {
        try {
            Core.INSTANCE.commands.localLine(line);
            return 1;
        } catch (RuntimeException e) {
            c.getSource().sendFailure(Component.literal("entropybot: " + e));
            return 0;
        }
    }
}
