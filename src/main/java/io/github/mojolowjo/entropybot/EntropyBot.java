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
        io.github.mojolowjo.entropybot.engine.WatchEvents.register();     // watch camera v1/v2 + the render-stage probe
        io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.init();  // camera v2: known air from every clear
        // 0.16.1: the tunnel view's cutaway shaders (mod bus; the listener catches every failure, the view then draws without the cut)
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterShadersEvent.class,
                io.github.mojolowjo.entropybot.watchview.CutShaders::onRegister);
    }

    private static void onChat(ClientChatReceivedEvent event) {
        Core.INSTANCE.commands.onChat(event);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Core.INSTANCE.onClientTick();
    }
}
