package io.github.mojolowjo.entropybot.commands;

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
 * 0.35.2: a command typed in the bot's own game window: {@code /bot <any bot command>}. It runs
 * exactly as a PM from the owner ({@code Commands.localLine}: same gate, same owner rights) and never reach the server.
 * Tab completes the verbs (0.39.z27: and the aa verbs after "aa ", the area sub-verbs after "area "). Its answer is shown
 * in this window's chat in full ({@code Commands.showLocal}); a PM keeps the whisper.
 *
 * <p>Loader API: NeoForge's {@code RegisterClientCommandsEvent} (registered in EntropyBot); the tree is vanilla
 * Brigadier. Fabric: {@code ClientCommandRegistrationCallback} with {@code ClientCommandManager.literal/argument}
 * (assumed from memory, not checked). Forge 1.21.1: {@code RegisterClientCommandsEvent} too.
 */
public final class LocalCommands {
    private LocalCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(net.minecraft.commands.Commands.literal("bot")
                .executes(c -> send(c, "help"))
                .then(net.minecraft.commands.Commands.argument("command", StringArgumentType.greedyString())
                        .suggests(LocalCommands::verbs)
                        .executes(c -> send(c, StringArgumentType.getString(c, "command")))));
    }

    private static CompletableFuture<Suggestions> verbs(CommandContext<CommandSourceStack> c, SuggestionsBuilder b) {
        String typed = b.getRemaining().toLowerCase(Locale.ROOT);
        int sp = typed.lastIndexOf(' ');
        SuggestionsBuilder at = sp < 0 ? b : b.createOffset(b.getStart() + sp + 1);
        for (String w : HelpCommand.completions(typed)) at.suggest(w);
        return at.buildFuture();
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
