package io.github.mojolowjo.entropybot.clear;

import io.github.mojolowjo.entropybot.clear.ClearWorld.Face;
import io.github.mojolowjo.entropybot.clear.ClearWorld.Hit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static io.github.mojolowjo.entropybot.clear.ClearRules.CLEAR_REACH;
import static io.github.mojolowjo.entropybot.clear.ClearRules.floor;

/**
 * The clear engine's decisions, ported from the bridge (claude_bridge.js, "Clearing the zone"): what may be
 * broken, which block to break next from where the bot stands (highest first, then nearest, its own footing
 * last), whether it can see a block, what makes a break unsafe, and the final report. Walking (planWalk,
 * bestSpotFor) is in {@link ClearGrid}, veins in {@link Veins}, tools in {@link Tools}. Pure: no game, no jobs.
 */
public final class ClearEngine {
    private ClearEngine() {}

    static final int[][] SIDES6 = { { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };

    // ---- what may be broken ----

    /**
     * clearableState: not air, liquid or waterlogged, no block entity (chests, beds, furnaces...), not bedrock,
     * not on the protected list (crafting tables, doors, torches, glass, crops...) unless forced, and not on
     * Baritone's avoid list.
     */
    public static boolean clearableState(ClearWorld w, int x, int y, int z, boolean force) {
        if (w.air(x, y, z) || w.blockEntity(x, y, z) || w.fluid(x, y, z)) return false;
        if (w.unbreakable(x, y, z)) return false;
        if (w.builtBlock(x, y, z) && !force) return false;
        return !w.avoided(x, y, z);
    }

    /** clearable(x, y, z) during a clear job (its "force" counts, and its exact id / armed gate: {@link ClearJob#allows}). */
    public static boolean clearable(ClearWorld w, ClearJob job, int x, int y, int z) {
        return clearableState(w, x, y, z, job != null && job.force) && (job == null || job.allows(w, x, y, z));
    }

    /**
     * noteProtected: a solid built block in the box that the clear leaves alone, for the report. Torches, rails and
     * crops (no collision) are left alone too, but not listed.
     */
    static void noteProtected(ClearJob job, ClearWorld w, int x, int y, int z) {
        String k = Pos.key(x, y, z);
        if (job == null || job.protectedLeft.containsKey(k)) return;
        if (job.protectedCount >= 200 || w.noCollision(x, y, z)) return;
        job.protectedLeft.put(k, w.name(x, y, z));
        job.protectedCount++;
    }

    /** noteOre: lists an ore left in place (once). */
    static void noteOre(ClearJob job, ClearWorld w, int x, int y, int z) {
        if (job.ores.note(x, y, z, w.name(x, y, z))) job.oresNoted++;
    }

    // ---- sight and safety ----

    /** Where to aim to break a block: the face the ray hits and the point aimed at. */
    public record Sight(Face face, double x, double y, double z) {}

    public static double eyeDistSq(double ex, double ey, double ez, int x, int y, int z) {
        double dx = x + 0.5 - ex, dy = y + 0.5 - ey, dz = z + 0.5 - ez;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * sightOf: can an eye at (ex, ey, ez) see block x y z? Only faces turned towards the eye with an open neighbour
     * count; the centre is tried first, then those faces. Null when it can't.
     */
    public static Sight sightOf(ClearWorld w, double ex, double ey, double ez, int x, int y, int z) {
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        List<double[]> pts = new ArrayList<>();
        for (int[] f : SIDES6) {
            if ((ex - cx) * f[0] + (ey - cy) * f[1] + (ez - cz) * f[2] <= 0.5) continue;
            if (w.fullBlock(x + f[0], y + f[1], z + f[2])) continue;
            pts.add(new double[] { cx + f[0] * 0.45, cy + f[1] * 0.45, cz + f[2] * 0.45 });
        }
        if (pts.isEmpty()) return null;
        pts.add(0, new double[] { cx, cy, cz });
        for (double[] p : pts) {
            Hit hit = w.clip(ex, ey, ez, p[0], p[1], p[2]);
            if (hit == null) continue;
            if (hit.x() == x && hit.y() == y && hit.z() == z) return new Sight(hit.face(), p[0], p[1], p[2]);
        }
        return null;
    }

    public static Sight sightOf(ClearWorld w, Bot bot, int x, int y, int z) {
        return sightOf(w, bot.x(), bot.eyeY(), bot.z(), x, y, z);
    }

    /** nextToLiquid: water or lava above it or beside it (not below). */
    public static boolean nextToLiquid(ClearWorld w, int x, int y, int z) {
        return w.fluid(x, y + 1, z) || w.fluid(x + 1, y, z) || w.fluid(x - 1, y, z) || w.fluid(x, y, z + 1) || w.fluid(x, y, z - 1);
    }

    /** Why a block shouldn't be broken: {@code perm} (never) or {@code temp} (not from where the bot stands now). */
    public record Hazard(String perm, String temp) {
        static Hazard perm(String why) { return new Hazard(why, null); }
        static Hazard temp(String why) { return new Hazard(null, why); }
    }

    public static final String NEXT_TO_LIQUID = "next to water/lava";

    /** breakHazard: null when breaking x y z from where the bot stands is fine. */
    public static Hazard breakHazard(ClearWorld w, Bot bot, ClearJob job, int x, int y, int z) {
        if (nextToLiquid(w, x, y, z)) return Hazard.perm(NEXT_TO_LIQUID);
        double px = bot.x(), pz = bot.z();
        int supY = floor(bot.y() - 0.01);
        if (x < floor(px - 0.3) || x > floor(px + 0.3) || z < floor(pz - 0.3) || z > floor(pz + 0.3)) return null;
        if (y == supY && w.noCollision(x, y - 1, z)) return Hazard.temp("I'm standing on it");
        // a vein clear stays on the walkway (minStandY): breaking its own floor would drop it below
        if (y == supY && job != null && job.minStandY != null && supY < job.minStandY) return Hazard.temp("I'd drop below the walkway");
        if (y > supY && ClearRules.falling(w.name(x, y + 1, z))) return Hazard.temp("sand/gravel would fall on me");
        return null;
    }

    // ---- failed tries ----

    /** clearFailed: given up on for now (4 tries, or tried within the last 10 blocks broken). */
    public static boolean clearFailed(ClearJob job, String key) {
        ClearJob.Fail f = job.fails.get(key);
        return f != null && (f.n >= 4 || job.broken - f.at < 10);
    }

    /** failTarget: one more failed try at a block. */
    public static void failTarget(ClearJob job, Pos t, String why) {
        ClearJob.Fail f = job.fails.computeIfAbsent(t.key(), k -> new ClearJob.Fail());
        f.n++;
        f.at = job.broken;
        f.why = why;
        job.consecFails++;
    }

    /**
     * clearPlan's second chance: once per job, when nothing is left to plan, the blocks given up on earlier (not
     * the hopeless ones, 4 tries) are tried again. True when there is something to try again (plan once more).
     */
    public static boolean retryRound(ClearJob job) {
        if (job.retried) return false;
        job.retried = true;
        boolean any = false;
        for (Iterator<Map.Entry<String, ClearJob.Fail>> it = job.fails.entrySet().iterator(); it.hasNext();) {
            if (it.next().getValue().n < 4) {
                it.remove();
                any = true;
            }
        }
        return any;
    }

    /** clearPlan gives up after this many failed tries in a row. */
    public static final int MAX_CONSEC_FAILS = 8;
    public static final String STUCK = "stopped: stuck - 8 tries in a row where I couldn't reach anything";

    // ---- the next block from where it stands ----

    /** A block to break: where, how far from the eye (squared), whether it's under the bot's feet, where to aim. */
    public record Target(int x, int y, int z, double d2, boolean under, Sight sight) {
        public String key() { return Pos.key(x, y, z); }
        public Pos pos() { return new Pos(x, y, z); }
    }

    /**
     * pickReachable: the block to break next from where the bot stands: highest first, then nearest; the one
     * under its feet last. Notes the built blocks and (keepOres) the ores it passes over; a block next to water or
     * lava is skipped for good. Null when nothing in reach can be seen.
     */
    public static Target pickReachable(ClearWorld w, Bot bot, ClearJob job) {
        ClearBox box = job.box;
        double ex = bot.x(), ey = bot.eyeY(), ez = bot.z(), r = CLEAR_REACH;
        int supY = floor(bot.y() - 0.01);
        int fx0 = floor(bot.x() - 0.3), fx1 = floor(bot.x() + 0.3);
        int fz0 = floor(bot.z() - 0.3), fz1 = floor(bot.z() + 0.3);
        List<Target> cands = new ArrayList<>();
        for (int x = Math.max(box.x1(), floor(ex - r)); x <= Math.min(box.x2(), floor(ex + r)); x++) {
            for (int y = Math.max(box.y1(), floor(ey - r)); y <= Math.min(box.y2(), floor(ey + r)); y++) {
                for (int z = Math.max(box.z1(), floor(ez - r)); z <= Math.min(box.z2(), floor(ez + r)); z++) {
                    double d2 = eyeDistSq(ex, ey, ez, x, y, z);
                    if (d2 > r * r) continue;
                    String key = Pos.key(x, y, z);
                    if (!job.wanted(key) || job.skip.containsKey(key) || clearFailed(job, key)) continue;
                    if (!clearable(w, job, x, y, z)) {
                        if (!w.air(x, y, z) && !w.blockEntity(x, y, z) && !w.fluid(x, y, z) && w.builtBlock(x, y, z)) noteProtected(job, w, x, y, z);
                        continue;
                    }
                    if (job.keepOres && w.ore(x, y, z)) {
                        noteOre(job, w, x, y, z);
                        continue;
                    }
                    boolean under = y == supY && x >= fx0 && x <= fx1 && z >= fz0 && z <= fz1;
                    // a vein clear stays on the walkway: breaking the block under its feet would drop it below
                    if (under && job.minStandY != null && supY < job.minStandY) continue;
                    cands.add(new Target(x, y, z, d2, under, null));
                }
            }
        }
        cands.sort(PICK_ORDER);
        for (Target c : cands) {
            Sight s = sightOf(w, ex, ey, ez, c.x(), c.y(), c.z());
            if (s == null) continue;
            Hazard h = breakHazard(w, bot, job, c.x(), c.y(), c.z());
            if (h != null && h.perm() != null) job.skip.put(c.key(), h.perm());
            if (h != null) continue;
            return new Target(c.x(), c.y(), c.z(), c.d2(), c.under(), s);
        }
        return null;
    }

    /** Its own footing last; then the highest; then the nearest. */
    static final Comparator<Target> PICK_ORDER = (a, b) -> {
        if (a.under() != b.under()) return a.under() ? 1 : -1;
        if (a.y() != b.y()) return b.y() - a.y();
        return Double.compare(a.d2(), b.d2());
    };

    // ---- breaking ----

    /** beginBreak: a block that takes more than 600 ticks (30 s) with the tool in hand is skipped. */
    public static boolean tooSlowToBreak(double progressPerTick) {
        return !(progressPerTick > 0) || 1 / progressPerTick > 600;
    }

    public static final String TOO_SLOW = "takes too long to break";

    /** beginBreak's tick budget for one block (it fails the target after that). */
    public static int breakLimit(double progressPerTick) {
        return (int) Math.ceil(1 / progressPerTick) + 30;
    }

    public static final String NEVER_FINISHED = "breaking it never finished";
    public static final String COULD_NOT_REACH = "couldn't reach it";

    /**
     * clearBreak's bookkeeping when the block is gone: counts it (and the ore, when collecting), takes a mined
     * ore off the list, and skips a block the server keeps putting back (3 times; not sand or gravel refilling
     * the spot). True every 250 blocks (the bridge whispers the requester "clearing: N blocks broken so far").
     *
     * @param falling the block was sand or gravel (beginBreak's t.falling)
     * @param oreId   the ore's block id when collecting an ore (beginBreak's t.oreId), else null
     */
    public static boolean onBroken(ClearJob job, Pos t, boolean falling, String oreId) {
        String key = t.key();
        job.broken++;
        job.consecFails = 0;
        if (oreId != null) {
            job.oresMined++;
            job.oreTally.merge(oreId, 1, Integer::sum);
            if (job.ores.forget(key)) job.memDirty = true;
        }
        if (!falling) {
            int n = job.brokenKeys.merge(key, 1, Integer::sum);
            if (n >= 3) job.skip.put(key, "the server keeps putting it back");
        }
        return job.broken % 250 == 0;
    }

    public static String milestoneText(ClearJob job) {
        return "clearing: " + job.broken + " blocks broken so far";
    }

    // ---- torches ----

    /**
     * clearTorch's choice: the first dug-out torch spot within 4 blocks of the eye. It is taken off the list; null
     * when there is none, or when another torch already lights it to 2 or more (monsters spawn only at block
     * light 0: about one torch every 13 blocks along a tunnel). Without torches the list is dropped.
     */
    public static Pos nextTorch(ClearWorld w, Bot bot, ClearJob job, boolean haveTorches) {
        if (!haveTorches) {
            job.torches.clear();
            return null;
        }
        for (int i = 0; i < job.torches.size(); i++) {
            Pos t = job.torches.get(i);
            if (eyeDistSq(bot.x(), bot.eyeY(), bot.z(), t.x(), t.y(), t.z()) > 16) continue;
            if (!w.air(t.x(), t.y(), t.z())) continue;          // not dug out yet (or already lit)
            job.torches.remove(i);
            if (w.blockLight(t.x(), t.y(), t.z()) > 1) {
                job.torchesSkipped++;
                return null;
            }
            return t;
        }
        return null;
    }

    // ---- the end ----

    /** scanOres: lists the exposed ores (an air cell beside them) in and around a box. */
    public static void scanOres(ClearWorld w, ClearJob job, ClearBox box) {
        for (int x = box.x1() - 1; x <= box.x2() + 1; x++) {
            for (int y = box.y1() - 1; y <= box.y2() + 1; y++) {
                for (int z = box.z1() - 1; z <= box.z2() + 1; z++) {
                    if (w.air(x, y, z) || !w.ore(x, y, z)) continue;
                    boolean exposed = false;
                    for (int n = 0; n < 6 && !exposed; n++) exposed = w.air(x + SIDES6[n][0], y + SIDES6[n][1], z + SIDES6[n][2]);
                    if (exposed) noteOre(job, w, x, y, z);
                }
            }
        }
    }

    /**
     * boxBlocked: the first cell in a box that something solid or liquid still fills ("x y z (block)"), or null.
     * A carpet or a rail is walked over, not a wall; a built block says how to dig it anyway.
     */
    public static String boxBlocked(ClearWorld w, ClearBox box) {
        for (int x = box.x1(); x <= box.x2(); x++) {
            for (int y = box.y1(); y <= box.y2(); y++) {
                for (int z = box.z1(); z <= box.z2(); z++) {
                    if (w.fluid(x, y, z)) return Pos.key(x, y, z) + " (" + w.name(x, y, z) + ")";
                    if (!w.noCollision(x, y, z) && !(w.collisionHeight(x, y, z) <= ClearRules.THIN)) {
                        String k = Pos.key(x, y, z);
                        return k + " (" + w.name(x, y, z) + (w.builtBlock(x, y, z) && !w.blockEntity(x, y, z)
                                ? ": a built block I don't break - if it's a mineshaft, PM dig " + k + " " + k + " force" : "") + ")";
                    }
                }
            }
        }
        return null;
    }

    /** The start of a clear's end message when water or lava stopped it ({@link ClearJob#liquidBlocks}). */
    public static final String BLOCKED_BY = "blocked by ";

    /** The end message says water or lava stopped the clear ("blocked by water at 377 -45 854 - broke ..."). */
    public static boolean blockedByLiquid(String msg) {
        return msg != null && msg.startsWith(BLOCKED_BY);
    }

    /**
     * Water plan item 1: the liquid cell that stopped the clear, or null. A block skipped as "next to water/lava" that
     * is still there and still touches a liquid (above or beside, as {@link #nextToLiquid}): the liquid cell next to the
     * first such block (the order the clear met them in).
     */
    public static Pos liquidBlock(ClearWorld w, ClearJob job) {
        int[][] sides = { { 0, 1, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 0, 1 }, { 0, 0, -1 } };
        for (Map.Entry<String, String> e : job.skip.entrySet()) {
            if (!NEXT_TO_LIQUID.equals(e.getValue())) continue;
            Pos p = Pos.parse(e.getKey());
            if (!clearable(w, job, p.x(), p.y(), p.z())) continue;
            for (int[] s : sides) {
                int x = p.x() + s[0], y = p.y() + s[1], z = p.z() + s[2];
                if (w.fluid(x, y, z)) return new Pos(x, y, z);
            }
        }
        // TLL 30: water between the bot and the rest of the box (it never got next to any of it)
        Pos lock = job.waterLock;
        if (lock != null && w.fluid(lock.x(), lock.y(), lock.z())) return lock;
        return null;
    }

    /** TLL 30: the words after "blocked by water at x y z" when the water cuts the bot off rather than touching a block. */
    public static final String CUT_OFF = " (it cuts me off from the rest of the dig)";

    /**
     * finishClear's report: "ok: done label - broke N blocks", "ok: finished ...; M left, e.g. ...", or the prefix
     * given ("stopped: ..."), plus ores mined, ores left in place and built blocks left alone. A corridor that has
     * to go all the way (mustFinish) says where it is blocked; a soft job turns "stopped" into "ok: gave up ...".
     * Lists the ores showing in the walls too (scanOres), except for a collecting box clear.
     */
    public static String finishMessage(ClearWorld w, ClearJob job, String prefix) {
        List<String> examples = new ArrayList<>();
        for (Map.Entry<String, String> e : job.skip.entrySet()) {
            Pos p = Pos.parse(e.getKey());
            if (examples.size() < 3 && clearable(w, job, p.x(), p.y(), p.z())) examples.add(e.getKey() + " " + e.getValue());
        }
        for (Map.Entry<String, ClearJob.Fail> e : job.fails.entrySet()) {
            Pos p = Pos.parse(e.getKey());
            if (examples.size() < 3 && !job.skip.containsKey(e.getKey()) && clearable(w, job, p.x(), p.y(), p.z())) {
                examples.add(e.getKey() + " " + e.getValue().why);
            }
        }
        for (String k : job.noSpot) {
            Pos p = Pos.parse(k);
            if (examples.size() < 3 && !job.skip.containsKey(k) && !job.fails.containsKey(k) && clearable(w, job, p.x(), p.y(), p.z())) {
                examples.add(k + " out of reach (nowhere to stand close enough)");
            }
        }
        int left = job.lastScanLeft != null ? job.lastScanLeft : 0;
        // water plan item 1: blocks left because they touch water or lava end a dig as blocked, naming the spot
        if (prefix == null && job.liquidBlocks) {
            Pos lb = liquidBlock(w, job);
            if (lb != null) prefix = BLOCKED_BY + w.fluidKind(lb.x(), lb.y(), lb.z()) + " at " + lb.key() + (lb.equals(job.waterLock) ? CUT_OFF : "");
        }
        // a strip mine's corridor has to go all the way, or every later branch is out of reach. Checks what's
        // really there (bedrock or a chest is never a target, so it doesn't count as "left", but it still blocks)
        if (prefix == null && job.mustFinish) {
            String blocked = boxBlocked(w, job.box);
            if (blocked != null) prefix = "stopped: the mine corridor is blocked at " + blocked;
        }
        if (job.soft && prefix != null && prefix.startsWith("stopped")) {
            prefix = "ok: gave up " + job.label + " (" + prefix.replaceFirst("^stopped: ", "") + ")";
        }
        // the ores showing in the walls too, not just the ones it skipped (a collecting branch leaves the ores it
        // exposed to its ore step, which lists what it couldn't mine)
        if (!(job.collect && job.only == null)) scanOres(w, job, job.box);
        int ores = job.oresNoted;
        StringBuilder msg = new StringBuilder(prefix != null ? prefix : (left != 0 ? "ok: finished " + job.label : "ok: done " + job.label));
        msg.append(" - broke ").append(job.broken).append(" blocks");
        if (left != 0) msg.append("; ").append(left).append(" left").append(examples.isEmpty() ? "" : ", e.g. " + String.join(", ", examples));
        if (job.collect && job.oresMined != 0) msg.append("; ").append(job.oresMined).append(" ores mined");
        if (ores != 0) msg.append("; ").append(ores).append(" ores left in place for you (PM \"ores\")");
        if (job.protectedCount != 0) {
            Map.Entry<String, String> first = job.protectedLeft.entrySet().iterator().next();
            msg.append("; left ").append(job.protectedCount).append(job.protectedCount >= 200 ? "+" : "")
               .append(" built block").append(job.protectedCount == 1 ? "" : "s")
               .append(" alone (e.g. ").append(first.getKey()).append(" ").append(first.getValue()).append(")");
        }
        return msg.toString();
    }
}
