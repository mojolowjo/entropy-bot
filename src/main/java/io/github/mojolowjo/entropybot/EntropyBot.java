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
        io.github.mojolowjo.entropybot.engine.WatchSteer.register();      // watch steer: camera-relative keys in the watch views
        io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.init();  // camera v2: known air from every clear
        // 0.17.1: the tunnel view's surface scan hears chunk loads/unloads (NeoForge) and block changes (the mod's ClientLevel hook)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Load.class, EntropyBot::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Unload.class, EntropyBot::onChunkUnload);
        io.github.mojolowjo.entropybot.recorder.FlightRecorder.watchListener = (level, pos, from, to) -> {
            if (level == net.minecraft.client.Minecraft.getInstance().level)
                io.github.mojolowjo.entropybot.watchview.SkyScanner.INSTANCE.blockChanged(pos.getX(), pos.getY(), pos.getZ());
        };
        // 0.16.1: the tunnel view's cutaway shaders (mod bus; the listener catches every failure, the view then draws without the cut)
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterShadersEvent.class,
                io.github.mojolowjo.entropybot.watchview.CutShaders::onRegister);
    }

    private static void onChat(ClientChatReceivedEvent event) {
        Core.INSTANCE.commands.onChat(event);
    }

    private static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        try {
            if (event.getLevel().isClientSide() && event.getLevel() == net.minecraft.client.Minecraft.getInstance().level)
                io.github.mojolowjo.entropybot.watchview.SkyScanner.INSTANCE.chunkLoaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel surface scan, chunk load: {}", t.toString());
        }
    }

    private static void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        try {
            if (event.getLevel().isClientSide() && event.getLevel() == net.minecraft.client.Minecraft.getInstance().level)
                io.github.mojolowjo.entropybot.watchview.SkyScanner.INSTANCE.chunkUnloaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel surface scan, chunk unload: {}", t.toString());
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Core.INSTANCE.onClientTick();
    }
}
