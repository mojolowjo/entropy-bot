package io.github.mojolowjo.entropycompanion;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The mod entry: client only (no side effect on a server, no network channels, so a server without it takes the
 * client as it is). The client tick only compares a clock; when a post is due it copies the player's position and
 * hands it to one background thread, which does the (blocking, 2 s at most) HTTP post.
 */
@Mod(value = EntropyCompanion.MODID, dist = Dist.CLIENT)
public final class EntropyCompanion {
    public static final String MODID = "entropycompanion";
    private static final Logger LOG = LogUtils.getLogger();

    private final Path configFile = FMLPaths.CONFIGDIR.get().resolve(CompanionConfig.FILE_NAME);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "entropy-companion");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final PostLoop loop;

    private volatile CompanionConfig config;
    private volatile long configMtime = Long.MIN_VALUE;
    private long lastConfigCheck;

    public EntropyCompanion(IEventBus modBus) {
        config = CompanionConfig.load(configFile); // writes the defaults on the first run
        configMtime = mtime();
        loop = new PostLoop(this::currentConfig, new HttpSender(), m -> LOG.info("[entropycompanion] {}", m), System::currentTimeMillis);
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        LOG.info("[entropycompanion] {}", config);
    }

    private long mtime() {
        try {
            return Files.getLastModifiedTime(configFile).toMillis();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /** The config, read again when the file has changed (called on the worker thread). */
    private CompanionConfig currentConfig() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck >= 1000) {
            lastConfigCheck = now;
            long m = mtime();
            if (m != configMtime) {
                configMtime = m;
                config = CompanionConfig.load(configFile);
                LOG.info("[entropycompanion] config reloaded: {}", config);
            }
        }
        return config;
    }

    private void onClientTick(ClientTickEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;
            if (!loop.due() || !busy.compareAndSet(false, true)) return;
            OwnerPayload.Snapshot snap = new OwnerPayload.Snapshot(mc.getUser().getName(),
                    mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.level.dimension().location().toString());
            worker.execute(() -> {
                try {
                    loop.attempt(snap);
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            busy.set(false); // never let an exception out of the client tick
        }
    }
}