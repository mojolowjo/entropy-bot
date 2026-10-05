package io.github.mojolowjo.entropybot;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** The mod entry: client only. Everything happens in {@link Core} once per client tick. */
@Mod(value = Core.MODID, dist = Dist.CLIENT)
public final class EntropyBot {
    public EntropyBot(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(EntropyBot::onClientTick);
        NeoForge.EVENT_BUS.addListener(EntropyBot::onChat);
        NeoForge.EVENT_BUS.addListener(EntropyBot::onRenderStage);     // camera v2 probe: which stages fire with Sodium
    }

    private static void onRenderStage(net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        io.github.mojolowjo.entropybot.engine.WatchProbe.INSTANCE.onStage(event);
    }

    private static void onChat(ClientChatReceivedEvent event) {
        Core.INSTANCE.commands.onChat(event);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Core.INSTANCE.onClientTick();
    }
}
