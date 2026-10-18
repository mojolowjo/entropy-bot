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
    public EntropyBot(IEventBus modBus, net.neoforged.fml.ModContainer container) {
        NeoForge.EVENT_BUS.addListener(EntropyBot::onClientTick);
        // 0.26.0 blind mode (NeoForge: ClientTickEvent.Pre for the self-check, Post at LOWEST so the noRender
        // re-assert runs after every other listener; FMLClientSetupEvent reads blind.json)
        NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.LOWEST, ClientTickEvent.Post.class,
                e -> io.github.mojolowjo.entropybot.blind.BlindRuntime.INSTANCE.postTick());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Pre.class, e -> io.github.mojolowjo.entropybot.blind.BlindRuntime.INSTANCE.preTick());
        modBus.addListener(net.neoforged.fml.event.lifecycle.FMLClientSetupEvent.class, e -> {
            try {
                io.github.mojolowjo.entropybot.blind.BlindRuntime.INSTANCE.load(
                        net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().resolve(Core.MODID));
            } catch (Throwable t) {
                com.mojang.logging.LogUtils.getLogger().warn("[entropybot] blind: client setup: {}", t.toString());
            }
        });
        io.github.mojolowjo.entropybot.blind.BlindRuntime.INSTANCE.hooks(() -> {
            var j = Core.INSTANCE.commands.jobs.job;
            return j != null && Core.INSTANCE.commands.jobs.running() ? j.type : null;
        }, s -> Core.INSTANCE.commands.whisper(Core.INSTANCE.commands.owner(), s));
        // 0.26.0 the config screen (NeoForge 21.1: IConfigScreenFactory registered as the mod's extension point;
        // loader-specific, the screen itself is a vanilla Screen)
        container.registerExtensionPoint(net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                (net.neoforged.neoforge.client.gui.IConfigScreenFactory) (mod, parent) -> new io.github.mojolowjo.entropybot.blind.BotConfigScreen(parent));
        NeoForge.EVENT_BUS.addListener(EntropyBot::onChat);
        // 0.35.2: /ai and /bot in the bot's own window (client commands: NeoForge RegisterClientCommandsEvent, never sent to the server)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.client.event.RegisterClientCommandsEvent.class,
                e -> io.github.mojolowjo.entropybot.ai.live.LocalCommands.register(e.getDispatcher()));
        io.github.mojolowjo.entropybot.engine.WatchEvents.register();     // watch camera v1/v2 + the render-stage probe
        io.github.mojolowjo.entropybot.engine.WatchSteer.register(modBus);      // watch steer: camera-relative keys in the watch views (+ watch turn's keys)
        io.github.mojolowjo.entropybot.motor.Motor.register();      // 0.27.0 motor: short moves by raw movement input
        io.github.mojolowjo.entropybot.watchview.TunnelView.INSTANCE.init();  // camera v2: known air from every clear
        // 0.17.1: the tunnel view's surface scan hears chunk loads/unloads (NeoForge) and block changes (the mod's ClientLevel hook)
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Load.class, EntropyBot::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(net.neoforged.neoforge.event.level.ChunkEvent.Unload.class, EntropyBot::onChunkUnload);
        io.github.mojolowjo.entropybot.recorder.FlightRecorder.watchListener = (level, pos, from, to) -> {
            if (level == net.minecraft.client.Minecraft.getInstance().level)
                io.github.mojolowjo.entropybot.watchview.SkyScanner.INSTANCE.blockChanged(pos.getX(), pos.getY(), pos.getZ());
            // 0.28.0: the surface export's column changes come through the chunk pass (chunkPassListener below)
            io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.blockChanged(level, pos, to);   // 0.23.2: patch the reach grid (never throws)
        };
        // 0.28.0: the one chunk pass hears every block change first (before the recorder's filters); no new mixin
        io.github.mojolowjo.entropybot.recorder.FlightRecorder.chunkPassListener = (level, pos, from, to) -> {
            io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.blockChanged(level, pos, from, to);
            // 0.33.0 review: a station of mine that loses its block is not mine when a same-kind block comes back
            if (from != null && to != null && from.getBlock() != to.getBlock())
                io.github.mojolowjo.entropybot.commands.RestoreLive.INSTANCE.blockChanged(io.github.mojolowjo.entropybot.guard.Guard.dimOf(level), pos.getX(), pos.getY(), pos.getZ(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(from.getBlock()).getPath(), net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(to.getBlock()).getPath());
        };
        // 0.16.1: the tunnel view's cutaway shaders (mod bus; the listener catches every failure, the view then draws without the cut)
        modBus.addListener(net.neoforged.neoforge.client.event.RegisterShadersEvent.class,
                io.github.mojolowjo.entropybot.watchview.CutShaders::onRegister);
    }

    private static void onChat(ClientChatReceivedEvent event) {
        Core.INSTANCE.commands.onChat(event);
    }

    private static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        // 0.28.0: the chunk pass (first loads and resends; never throws)
        if (event.getLevel().isClientSide()) io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.chunkLoaded(event.getLevel(), event.getChunk());
        try {
            if (event.getLevel().isClientSide() && event.getLevel() == net.minecraft.client.Minecraft.getInstance().level)
                io.github.mojolowjo.entropybot.watchview.SkyScanner.INSTANCE.chunkLoaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
            if (event.getLevel().isClientSide() && event.getLevel() == net.minecraft.client.Minecraft.getInstance().level)
                io.github.mojolowjo.entropybot.surface.SurfaceExport.INSTANCE.chunkLoaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
            if (event.getLevel().isClientSide())
                io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.chunkChanged(event.getChunk().getPos().x, event.getChunk().getPos().z);   // 0.23.2
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel surface scan, chunk load: {}", t.toString());
        }
    }

    private static void onChunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) io.github.mojolowjo.entropybot.sense.ChunkPass.INSTANCE.chunkUnloaded(event.getLevel(), event.getChunk());     // 0.28.0
        try {
            if (event.getLevel().isClientSide() && event.getLevel() == net.minecraft.client.Minecraft.getInstance().level)
                io.github.mojolowjo.entropybot.watchview.SkyScanner.INSTANCE.chunkUnloaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
            if (event.getLevel().isClientSide() && event.getLevel() == net.minecraft.client.Minecraft.getInstance().level)
                io.github.mojolowjo.entropybot.surface.SurfaceExport.INSTANCE.chunkUnloaded(event.getChunk().getPos().x, event.getChunk().getPos().z);
            if (event.getLevel().isClientSide())
                io.github.mojolowjo.entropybot.threat.ThreatRuntime.INSTANCE.chunkChanged(event.getChunk().getPos().x, event.getChunk().getPos().z);   // 0.23.2
        } catch (Throwable t) {
            com.mojang.logging.LogUtils.getLogger().warn("[entropybot] watch tunnel surface scan, chunk unload: {}", t.toString());
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Core.INSTANCE.onClientTick();
    }
}
