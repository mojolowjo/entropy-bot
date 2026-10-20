package io.github.mojolowjo.entropybot.engine;

import baritone.api.IBaritone;
import com.mojang.logging.LogUtils;
import net.minecraft.client.player.LocalPlayer;
import org.slf4j.Logger;

/**
 * 0.34.3 the shore watch (AA_CLEANUP 4: out of Reflexes, unchanged): afloat with nothing driving, a short walk to the
 * nearest dry standable cell (ReflexRules.nearestShore). Called from the air reflex every tick. Loader notes: vanilla
 * block/fluid reads and one Baritone custom goal; the pure part is {@link ReflexRules#nearestShore}.
 */
final class ShoreWatch {
    private static final Logger LOG = LogUtils.getLogger();

    private final java.util.function.Consumer<String> whisper;
    private long now;

    ShoreWatch(java.util.function.Consumer<String> whisper) { this.whisper = whisper; }

    /** Not afloat now (a screen, riding): the clock starts again. */
    void reset() { floatSince = -1; }

    void tick(LocalPlayer p, IBaritone b, boolean drives, long tick) {
        now = tick;
        shoreWatch(p, b, drives);
    }

    private void whisper(String s) { whisper.accept(s); }
    /** 0.34.3: since when the bot floats with its head above water and nothing driving (-1: not now); the next look. */
    private long floatSince = -1, shoreNext = -1;
    /** 0.34.4: the shore cell it is walking to (null: none), the cells it couldn't reach, tries, given up (reset out of the water). */
    private int[] shoreTarget;
    private final java.util.Set<Long> shoreFailed = new java.util.HashSet<>();
    private int shoreTries;
    private boolean shoreGaveUp, shoreSaid;
    static final int SHORE_TRIES = 4;

    /**
     * 0.34.3 (after the air reflex surfaced it, the bot trod water there forever): 5 s afloat with no job, no reflex and
     * Baritone idle: a short walk to the nearest dry standable cell (ReflexRules.nearestShore, 32 blocks), said once.
     * None in reach: said once, looked for again in a minute. Never while a job or a reflex drives it.
     */
    private void shoreWatch(LocalPlayer p, IBaritone b, boolean drives) {
        try {
            if (b == null || !p.isInWater()) {
                floatSince = -1;
                shoreTarget = null; shoreFailed.clear(); shoreTries = 0; shoreGaveUp = false; shoreSaid = false;
                return;
            }
            if (drives) { floatSince = -1; shoreTarget = null; return; }
            // 0.34.4 (review): standing on the bottom of shallow water is not afloat
            if (p.onGround()) { floatSince = -1; return; }
            if (p.isUnderWater()) return;            // bobbing at the surface: the clock runs on
            if (floatSince < 0) floatSince = now;
            if (now - floatSince < 100 || now < shoreNext || shoreGaveUp) return;
            if (shoreTarget != null) {
                // 0.34.4 (review): afloat again with Baritone idle: that cell couldn't be reached; the next nearest
                shoreFailed.add(net.minecraft.core.BlockPos.asLong(shoreTarget[0], shoreTarget[1], shoreTarget[2]));
                LOG.info("[entropybot] shore: couldn't reach {} {} {} (try {})", shoreTarget[0], shoreTarget[1], shoreTarget[2], shoreTries + 1);
                shoreTarget = null;
                if (++shoreTries >= SHORE_TRIES) {
                    shoreGaveUp = true;
                    String g = "afloat at " + (int) Math.floor(p.getX()) + " " + (int) Math.floor(p.getY()) + " " + (int) Math.floor(p.getZ())
                            + ": I couldn't reach the " + shoreTries + " nearest shores - next: goto x y z (a shore), or come get me";
                    LOG.info("[entropybot] shore: {}", g);
                    whisper(g);
                    return;
                }
            }
            var lv = p.level();
            int x = (int) Math.floor(p.getX()), y = (int) Math.floor(p.getY()), z = (int) Math.floor(p.getZ());
            int[] s = ReflexRules.nearestShore(x, y, z, ReflexRules.SHORE_RADIUS, (cx, cy, cz) -> {
                net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(cx, cy, cz);
                if (!lv.isLoaded(at)) return false;
                // 0.34.4: not next to a shore cell it couldn't reach (live: 4 tries spent on one wall top)
                for (long f : shoreFailed) {
                    net.minecraft.core.BlockPos fp = net.minecraft.core.BlockPos.of(f);
                    if (Math.abs(fp.getX() - cx) <= 2 && Math.abs(fp.getZ() - cz) <= 2 && Math.abs(fp.getY() - cy) <= 3) return false;
                }
                var below = lv.getBlockState(at.below());
                if (!below.getFluidState().isEmpty() || !below.isFaceSturdy(lv, at.below(), net.minecraft.core.Direction.UP)) return false;
                for (net.minecraft.core.BlockPos c : new net.minecraft.core.BlockPos[]{at, at.above()}) {
                    var st = lv.getBlockState(c);
                    if (!st.getFluidState().isEmpty() || !st.getCollisionShape(lv, c).isEmpty()) return false;
                }
                return true;
            });
            if (s == null) {
                shoreNext = now + 1200;
                if (shoreTries > 0) shoreGaveUp = true;        // the unreachable ones were all there was: said once
                String t = "afloat at " + x + " " + y + " " + z + " with no dry ground within " + ReflexRules.SHORE_RADIUS + " blocks - next: goto x y z (a shore), or come get me";
                LOG.info("[entropybot] shore: {}", t);
                if (!shoreSaid) whisper(t);                    // 0.34.4: once while afloat (the log has every look)
                shoreSaid = true;
                return;
            }
            shoreNext = now + 200;
            shoreTarget = s;
            b.getCustomGoalProcess().setGoalAndPath(new baritone.api.pathing.goals.GoalBlock(s[0], s[1], s[2]));
            String t = "afloat with nothing to do: swimming to the nearest shore at " + s[0] + " " + s[1] + " " + s[2];
            LOG.info("[entropybot] shore: {} (from {} {} {})", t, x, y, z);
            if (!shoreSaid) whisper(t);                        // 0.34.4: the first target only; the next ones are logged
            shoreSaid = true;
        } catch (RuntimeException e) {
            shoreNext = now + 1200;
            LOG.warn("[entropybot] shore: the look for dry ground failed: {}", e.toString());
        }
    }
}
