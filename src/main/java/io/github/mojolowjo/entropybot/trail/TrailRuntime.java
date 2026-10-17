package io.github.mojolowjo.entropybot.trail;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.io.BotFiles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 0.32.2 (PATHS_PLAN P2): the walked trail of the current world, in its folder ({@code worlds/<key>/trail.txt}):
 * a point every 4 blocks moved (twice a second), written every minute when changed, at a world change and on leaving.
 * Vanilla client reads only (position, canSeeSky). Never throws out.
 */
public final class TrailRuntime {
    private static final Logger LOG = LogUtils.getLogger();
    public static final TrailRuntime INSTANCE = new TrailRuntime();
    public static final String FILE = "trail.txt";

    private TrailRuntime() {}

    private volatile Trail trail = new Trail();
    private Path file;
    private int errors;

    public Trail trail() { return trail; }

    public synchronized String load(Path worldRoot) {
        try {
            file = worldRoot.resolve(FILE);
            if (!Files.exists(file)) {
                trail = new Trail();
                return FILE + ": none yet";
            }
            int[] skipped = new int[1];
            trail = Trail.fromText(Files.readString(file, StandardCharsets.UTF_8), skipped);
            trail.takeDirty();
            return FILE + ": " + trail.size() + " points" + (skipped[0] > 0 ? ", " + skipped[0] + " broken lines skipped" : "");
        } catch (Exception e) {
            trail = new Trail();
            LOG.warn("[entropybot] trail: couldn't read {} ({}): starting empty", file, e.toString());
            return FILE + ": unreadable, starting empty";
        }
    }

    public synchronized void save() {
        if (file == null || !trail.takeDirty()) return;
        try {
            BotFiles.writeAtomicAnywhere(file, trail.toText());
        } catch (Exception e) {
            if (errors++ < 5) LOG.warn("[entropybot] trail: couldn't write {}: {}", file, e.toString());
        }
    }

    public void tick(long tick) {
        try {
            if (tick % 10 != 3) return;
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null || p.isDeadOrDying()) return;
            trail.offer(Guard.dimOf(p.level()), p.getBlockX(), p.getBlockY(), p.getBlockZ(), p.level().canSeeSky(p.blockPosition().above()));
            if (tick % 1200 == 3) save();
        } catch (RuntimeException e) {
            if (errors++ < 5) LOG.warn("[entropybot] trail: {}", e.toString());
        }
    }
}
