package io.github.mojolowjo.entropybot.strip;

import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.ClearEngine;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.ClearRules;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.storage.StorageRules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B7d D2: the strip mine's decisions, ported from the bridge (package A and B4): what blocks a corridor
 * (corridorBlock), where a mine may start or turn to (standCheck, mineSpotCheck, the turn's options), where the
 * table and chests go (setupPlanSteps), what a failed dig means for the run (stripRecover's choice), and the trip to
 * the base (basedeposit's test, bagRoom). Pure.
 */
public final class StripRules {
    private StripRules() {}

    public static final int TURN_ALONG = 8, BASE_TRIP_MIN = 64, BASE_TRIP_FREE = 4, MAX_RUNS = 30;
    /** "mine strip" starts no new run after an hour. */
    public static final long MAX_TICKS = 20L * 60 * 60;

    /** The pickaxe tier a block needs: stone 2, iron 4, diamond 5 (the clear's toolTier scale). */
    public static int pickTier(String need) {
        return switch (need == null ? "" : need) {
            case "stone" -> 2;
            case "iron" -> 4;
            case "diamond" -> 5;
            default -> 4;
        };
    }

    /** What stops a corridor: kind (tool, liquid, protected, unbreakable, stuck), the "blocked:..." text, and the tool tier it needs. */
    public record Block(String kind, String text, String need) {}

    /**
     * corridorBlock: the first cell of the box (nearest to the bot) still solid or liquid, as a reason the run acts on:
     * "blocked:tool deepslate_redstone_ore at x y z needs iron", "blocked:liquid water at x y z", "blocked:protected
     * oak_planks at x y z - if it's a mineshaft, PM dig ... force", "blocked:unbreakable bedrock at x y z",
     * "blocked:stuck stone at x y z". clearMessage is the clear's report: its "e.g. x y z <why>" examples say which
     * blocks it skipped and why (the bridge read the job's skip notes); bestPick is the best pickaxe tier carried.
     */
    public static Block corridorBlock(StripWorld w, ClearBox box, int[] me, String clearMessage, int bestPick) {
        List<Pos> cells = new ArrayList<>();
        for (int x = box.x1(); x <= box.x2(); x++) for (int y = box.y1(); y <= box.y2(); y++) for (int z = box.z1(); z <= box.z2(); z++) cells.add(new Pos(x, y, z));
        cells.sort((a, b) -> Long.compare(distSq(a, me), distSq(b, me)));
        for (Pos c : cells) {
            int x = c.x(), y = c.y(), z = c.z();
            String key = c.key(), name = w.name(x, y, z);
            if (w.fluid(x, y, z)) return new Block("liquid", "blocked:liquid " + name + " at " + key, null);
            if (w.noCollision(x, y, z) || w.collisionHeight(x, y, z) <= ClearRules.THIN) continue;
            if (w.blockEntity(x, y, z)) return new Block("protected", "blocked:protected " + name + " at " + key + " (I never break containers)", null);
            if (w.builtBlock(x, y, z)) return new Block("protected", "blocked:protected " + name + " at " + key + " - if it's a mineshaft, PM dig " + key + " " + key + " force", null);
            if (w.unbreakable(x, y, z)) return new Block("unbreakable", "blocked:unbreakable " + name + " at " + key, null);
            String sk = skipHint(clearMessage, key);
            String need = w.toolNeed(x, y, z);
            if (sk.contains("better tool") || (w.needsCorrectTool(x, y, z) && pickTier(need) > bestPick)) {
                return new Block("tool", "blocked:tool " + name + " at " + key + " needs " + need, need);
            }
            if (sk.contains("too long")) return new Block("unbreakable", "blocked:unbreakable " + name + " at " + key + " (takes too long to break)", null);
            if (sk.contains("water/lava") || ClearEngine.nextToLiquid(w, x, y, z)) return new Block("liquid", "blocked:liquid " + name + " at " + key + " (next to water/lava)", null);
            return new Block("stuck", "blocked:stuck " + name + " at " + key, null);
        }
        return null;
    }

    /** What a clear's report says about one block ("...e.g. 1 2 3 needs a better tool (x), ..."): the text after its key, or "". */
    public static String skipHint(String msg, String key) {
        if (msg == null) return "";
        int i = msg.indexOf(" " + key + " ");
        if (i < 0) return "";
        String rest = msg.substring(i + key.length() + 2);
        int end = rest.length();
        for (String stop : new String[]{", ", "; "}) {
            int j = rest.indexOf(stop);
            if (j >= 0 && j < end) end = j;
        }
        return rest.substring(0, end);
    }

    static long distSq(Pos a, int[] b) {
        long dx = a.x() - b[0], dy = a.y() - b[1], dz = a.z() - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- a corridor the bot can't walk along (S1, live 2026-10-03: a corridor crossed a lava cave) ----

    /** A corridor cell (index i) it can't walk: at = the block that is the trouble, what = "lava", "water" or "a drop". */
    public record BadFloor(int i, Pos at, String what) {}

    /**
     * The first corridor cell from index from to to (inclusive) whose walk is broken: lava or water in its floor (or in
     * the cell itself, for lava), or no floor over a hole 2 or more deep (the walk can't climb back without placing).
     * Cells in unloaded chunks are passed over (unknown, not bad). lavaOnly: water and holes don't count. Null when every
     * known cell is fine.
     */
    public static BadFloor badFloor(StripWorld w, MineGeom g, int from, int to, boolean lavaOnly) {
        for (int i = Math.max(0, from); i <= to; i++) {
            Pos c = g.cell(i, 0);
            int x = c.x(), y = c.y(), z = c.z();
            if (!w.loaded(x, y, z)) continue;
            for (int dy = 0; dy < 2; dy++) {
                if (isLava(w, x, y + dy, z)) return new BadFloor(i, new Pos(x, y + dy, z), "lava");
            }
            if (isLava(w, x, y - 1, z)) return new BadFloor(i, new Pos(x, y - 1, z), "lava");
            boolean open = w.noCollision(x, y - 1, z);
            if (open && isLava(w, x, y - 2, z)) return new BadFloor(i, new Pos(x, y - 2, z), "lava");
            if (lavaOnly) continue;
            if (w.fluid(x, y - 1, z)) return new BadFloor(i, new Pos(x, y - 1, z), "water");
            if (open && w.fluid(x, y - 2, z)) return new BadFloor(i, new Pos(x, y - 2, z), "water");
            if (open && w.noCollision(x, y - 2, z)) return new BadFloor(i, new Pos(x, y - 1, z), "a drop");
        }
        return null;
    }

    private static boolean isLava(StripWorld w, int x, int y, int z) {
        return w.fluid(x, y, z) && w.id(x, y, z).endsWith(":lava");
    }

    /**
     * Where to look for trouble on the way to corridor cell to: from the bot's own cell when it stands by the corridor
     * (within 20 sideways and 10 levels: it is already past what lies behind it), else from the entrance.
     */
    public static int scanFrom(MineGeom g, int[] me, int to) {
        if (me == null) return 0;
        if (Math.abs(g.side(me)) > 20 || Math.abs(me[1] - g.y) > MineGeom.LEG_LEVELS) return 0;
        return Math.max(0, Math.min(to, g.along(me)));
    }

    /**
     * S1: a strip dig's box (and with torches its shell, where they go) reaching into a protect box: the reason ("it
     * reaches into the protected base"), else null. The guard refuses every break and placement there, in every mode.
     */
    public static String protectRefusal(List<io.github.mojolowjo.entropybot.guard.Box> protect, String dim, ClearBox b, boolean torches) {
        ClearBox shell = torches ? b.grow(1) : b;
        io.github.mojolowjo.entropybot.guard.Box box = new io.github.mojolowjo.entropybot.guard.Box(null, dim, shell.x1(), shell.y1(), shell.z1(), shell.x2(), shell.y2(), shell.z2());
        for (io.github.mojolowjo.entropybot.guard.Box pr : protect) {
            if (pr.overlaps(box)) return "it reaches into the protected " + (pr.name != null ? pr.name : "box");
        }
        return null;
    }

    // ---- where a mine may start ----

    private static boolean solid(StripWorld w, int x, int y, int z) {
        return !w.noCollision(x, y, z) && !(w.collisionHeight(x, y, z) <= ClearRules.THIN);
    }

    /** standCheck: null when the bot can stand at p (feet and head free of solids and liquid, something under it), else why not. */
    public static String standCheck(StripWorld w, Pos p) {
        for (int y = 0; y < 2; y++) {
            if (w.fluid(p.x(), p.y() + y, p.z()) || solid(w, p.x(), p.y() + y, p.z())) {
                return "I can't stand at " + p.key() + " (" + w.name(p.x(), p.y() + y, p.z()) + (y == 1 ? " above it" : "") + ")";
            }
        }
        if (w.noCollision(p.x(), p.y() - 1, p.z())) return "there is nothing to stand on at " + p.key();
        return null;
    }

    /** The guard's dry run ("ok", "would refuse: ...", "would refuse (log mode): ...") for breaking at x y z. */
    @FunctionalInterface
    public interface GuardCheck {
        String check(int x, int y, int z);
    }

    /**
     * mineSpotCheck: null when a mine may start at p going dir: a loaded spot I can stand in, the fence lets me walk
     * there (goalAllowed: null or the reason), and the first 3 cells of its corridor hold nothing a corridor stops at
     * (water or lava by them, a container or built block, bedrock), and the guard would let me dig them.
     */
    public static String mineSpotCheck(StripWorld w, Pos p, String dir, Function<Pos, String> goalAllowed, GuardCheck guard, boolean fenceOn) {
        if (!w.loaded(p.x(), p.y(), p.z())) return "I can't see " + p.key() + " from here";
        String r = standCheck(w, p);
        if (r == null) r = goalAllowed.apply(p);
        if (r != null) return r;
        int[] f = MineGeom.DIRS.get(dir);
        for (int i = 1; i <= 3; i++) {
            for (int y = 0; y < 2; y++) {
                Pos q = new Pos(p.x() + f[0] * i, p.y() + y, p.z() + f[1] * i);
                if (w.fluid(q.x(), q.y(), q.z()) || ClearEngine.nextToLiquid(w, q.x(), q.y(), q.z())) return "water or lava at " + q.key();
                if (solid(w, q.x(), q.y(), q.z())) {
                    if (w.blockEntity(q.x(), q.y(), q.z()) || w.builtBlock(q.x(), q.y(), q.z())) return w.name(q.x(), q.y(), q.z()) + " at " + q.key() + " (I don't break it)";
                    if (w.unbreakable(q.x(), q.y(), q.z())) return w.name(q.x(), q.y(), q.z()) + " at " + q.key();
                }
                if (guard != null) {
                    String g = guard.check(q.x(), q.y(), q.z());
                    // "no lease here" is expected (the dig takes its lease); with the fence off (log mode) an area refusal is
                    // only noted; the floor (protect boxes...) always counts
                    if (!g.equals("ok") && !g.endsWith("no lease here") && !(g.startsWith("would refuse (log mode)") && !fenceOn)) {
                        return "the guard says no at " + q.key() + " (" + g.replaceFirst("^would refuse( \\(log mode\\))?: ", "").replaceFirst("^error: ", "") + ")";
                    }
                }
            }
        }
        return null;
    }

    /** A place a turned mine may start: where, and the way it digs. */
    public record TurnOption(Pos pos, String dir) {}

    /**
     * stripTurn's options for mine g at progress k: left and right at the last open corridor cell (3(k-1)); with
     * only == null and k > 1 also a fresh mine TURN_ALONG blocks along the last branch, going on or out.
     */
    public static List<TurnOption> turnOptions(MineGeom g, int k, String only) {
        int e = Math.max(3 * (k - 1), 0);
        List<TurnOption> out = new ArrayList<>();
        String left = g.leftDir(), right = g.rightDir();
        if (!"right".equals(only)) out.add(new TurnOption(g.cell(e, 0), left));
        if (!"left".equals(only)) out.add(new TurnOption(g.cell(e, 0), right));
        if (only == null && k > 1) {
            out.add(new TurnOption(g.cell(e, TURN_ALONG), g.dir));
            out.add(new TurnOption(g.cell(e, TURN_ALONG), left));
            out.add(new TurnOption(g.cell(e, -TURN_ALONG), g.dir));
            out.add(new TurnOption(g.cell(e, -TURN_ALONG), right));
        }
        return out;
    }

    /** The first option that passes the check, or null with the reason for each in err ("the mine can't turn - ..."). */
    public record Turn(TurnOption option, String err) {}

    public static Turn chooseTurn(List<TurnOption> options, Function<TurnOption, String> check) {
        List<String> tried = new ArrayList<>();
        for (TurnOption o : options) {
            String r = check.apply(o);
            if (r == null) return new Turn(o, null);
            tried.add(o.dir() + " at " + o.pos().key() + ": " + r);
        }
        return new Turn(null, "the mine can't turn - " + String.join("; ", tried));
    }

    public static String turnedText(String dir, Pos p) { return "mine turned " + dir + " at " + p.key(); }

    // ---- a failed dig (stripRecover) ----

    private static final Pattern BLOCKED = Pattern.compile("blocked:(\\w+)");
    private static final Pattern STUCK = Pattern.compile("^(stopped|error): (stuck|couldn't get there)");

    /** The kind a dig's message names ("blocked:<kind>", or "stuck" for a stuck clear), or null. */
    public static String kindOf(String msg) {
        Matcher m = BLOCKED.matcher(msg);
        if (m.find()) return m.group(1);
        return STUCK.matcher(msg).find() ? "stuck" : null;
    }

    /** The reason as the run's report and whispers name it: from "blocked:" on (or the message), without "- broke N blocks...". */
    public static String whyOf(String msg) {
        int i = msg.indexOf("blocked:");
        String w = i >= 0 ? msg.substring(i) : msg.replaceFirst("^(stopped|error): ", "");
        return w.replaceFirst(" - broke \\d+ blocks.*$", "");
    }

    /** What a strip dig's end means: fail (the run ends with this text), or a kind to recover from (null kind: go on). */
    public record Verdict(String fail, String kind, String why, String need) {
        static final Verdict GO_ON = new Verdict(null, null, null, null);
    }

    /**
     * A strip dig ended with msg (the clear's report). part: "corridor", "left", "right", or null for a plain dig (the
     * setup's rooms, the ore step). The corridor has to end up open (the bridge's mustFinish): after a normal end or a
     * stuck one, blocked (corridorBlock, asked lazily) says what still fills it. Any other failure ends the run as before.
     */
    public static Verdict judge(String part, String msg, java.util.function.Supplier<Block> blocked) {
        boolean ok = msg.startsWith("ok");
        String failed = msg.replaceFirst("^(stopped|error): ", "");
        if (part == null) return ok ? Verdict.GO_ON : new Verdict(failed, null, null, null);
        if (part.equals("corridor")) {
            boolean stuck = STUCK.matcher(msg).find();
            if (!ok && !stuck) return new Verdict(failed, null, null, null);
            Block b = blocked.get();
            if (b != null) return new Verdict(null, b.kind(), b.text(), b.need());
            return stuck ? new Verdict(null, "stuck", whyOf(msg), null) : Verdict.GO_ON;
        }
        if (ok) return Verdict.GO_ON;
        String kind = kindOf(msg);
        return kind == null ? new Verdict(failed, null, null, null) : new Verdict(null, kind, whyOf(msg), null);
    }

    /** After a turn the rest of the run belongs to the old mine: of the steps after the failed one (their types), the ones to keep (a trip to the base, and "mine strip"'s next run). */
    public static List<Integer> keepAfterTurn(List<String> restTypes) {
        List<Integer> keep = new ArrayList<>();
        for (int i = 0; i < restTypes.size(); i++) {
            if (restTypes.get(i).equals("stripnext")) {
                for (int j = i; j < restTypes.size(); j++) keep.add(j);
                break;
            }
            if (restTypes.get(i).equals("stripbase")) keep.add(i);
        }
        return keep;
    }

    public enum Action { TOOL, RETRY, SKIP, TURN, FAIL }

    /**
     * stripRecover's choice for a failed strip dig (kind as kindOf, part corridor/left/right, n = how often this kind
     * has hit this part of this branch in the run, counting this time): a pickaxe trip once (then FAIL), home and back
     * (the corridor twice, a branch once), a branch skipped, else the corridor turns the mine.
     */
    public static Action recover(String kind, String part, int n) {
        if (kind.equals("tool")) return n > 1 ? Action.FAIL : Action.TOOL;
        if (kind.equals("stuck") && n <= (part.equals("corridor") ? 2 : 1)) return Action.RETRY;
        if (!part.equals("corridor")) return Action.SKIP;
        return Action.TURN;
    }

    /** "branch 7 right skipped (outside my areas)". */
    public static String skipText(int k, String part, String kind, String why) {
        return "branch " + k + " " + part + " skipped (" + (kind.equals("area") ? "outside my areas" : why) + ")";
    }

    // ---- the trip to the base ----

    /**
     * basedeposit: the end of a run with ore collecting goes to the base when it carries 64+ valuables above their keep
     * counts, or bagRoom is down to 4. keep = depositables(..., only VALUABLE) ({id: keep}), have = the bag.
     */
    public static boolean baseTrip(Map<String, Integer> keep, Map<String, Integer> have, int bagRoom) {
        int vals = 0;
        for (Map.Entry<String, Integer> e : keep.entrySet()) vals += have.getOrDefault(e.getKey(), 0) - e.getValue();
        return vals > 0 && (vals >= BASE_TRIP_MIN || bagRoom <= BASE_TRIP_FREE);
    }

    /** bagRoom: slots that are free or hold only junk the next dump empties (junk = depositables with the valuables kept, {id: keep}). */
    public static int bagRoom(List<StorageRules.Held> slots36, Map<String, Integer> junk) {
        Map<String, Integer> kept = new java.util.HashMap<>();
        int n = 0;
        for (StorageRules.Held h : slots36) {
            if (h == null || h.id() == null) {
                n++;
                continue;
            }
            Integer keep = junk.get(h.id());
            if (keep == null) continue;
            if (keep > 0 && kept.getOrDefault(h.id(), 0) < keep) {
                kept.merge(h.id(), h.n(), Integer::sum);
                continue;
            }
            n++;
        }
        return n;
    }

    // ---- the mine's table and chests (setupPlanSteps) ----

    public record Setup(List<StripPlan.Item> items, String note, String err) {}

    /**
     * Where the mine's crafting table and two chests go, looked at on arrival: a table or chests already near the
     * entrance (an older mine's) are used; else free cells next to the entrance (dug out already, or rock the clear may
     * break: never a container, a built block, bedrock or a cell by water), the old spots first (chests left of the
     * entrance, the table right of it).
     */
    public static Setup setupPlan(StripWorld w, MineGeom g) {
        Pos s = g.start();
        Set<String> used = new HashSet<>();
        used.add(s.key());
        record Found(Pos p, boolean table) {}
        List<Found> found = new ArrayList<>();
        for (int dx = -3; dx <= 3; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -3; dz <= 3; dz++) {
            int x = s.x() + dx, y = s.y() + dy, z = s.z() + dz;
            String id = w.id(x, y, z);
            if (w.untrusted(x, y, z)) continue;                   // the owner's "untrust": never the mine's
            if (id.contains("crafting_table")) found.add(new Found(new Pos(x, y, z), true));
            else if (w.blockEntity(x, y, z) && StorageRules.isStorageId(id) && !id.contains("ender")) found.add(new Found(new Pos(x, y, z), false));
        }
        found.sort((a, b) -> Long.compare(distSq(a.p(), new int[]{s.x(), s.y(), s.z()}), distSq(b.p(), new int[]{s.x(), s.y(), s.z()})));
        Pos table = null;
        List<Pos> chests = new ArrayList<>();
        for (Found f : found) {
            if (f.table() && table == null) table = f.p();
            if (!f.table() && chests.size() < 2) chests.add(f.p());
            used.add(f.p().key());
        }
        List<String> notes = new ArrayList<>();
        if (table != null) notes.add("the table at " + table.key());
        if (!chests.isEmpty()) notes.add(chests.size() == 1 ? "the chest at " + chests.get(0).key() : "the chests at " + chests.get(0).key() + " and " + chests.get(1).key());
        List<Pos> cands = List.of(g.cell(0, 1), g.cell(-1, 1), g.cell(0, -1), g.cell(-1, -1), g.cell(-1, 0), g.cell(1, 1), g.cell(1, -1),
                g.cell(-2, 0), g.cell(-2, 1), g.cell(-2, -1));
        List<Pos> newChests = new ArrayList<>();
        Pos p;
        while (chests.size() + newChests.size() < 2 && (p = pick(w, cands, used, 2)) != null) newChests.add(p);
        Pos newTable = table != null ? null : pick(w, cands, used, 1);
        if (table == null && newTable == null) return new Setup(null, null, "couldn't place the crafting_table: no free cell next to the mine entrance " + s.key());
        if (chests.isEmpty() && newChests.isEmpty()) return new Setup(null, null, "couldn't place the chests: no free cell next to the mine entrance " + s.key());
        List<StripPlan.Item> items = new ArrayList<>();
        // (the chests 2 high, or the far one, diagonal from the start, can't be seen into)
        if (newChests.size() == 2 && Math.abs(newChests.get(0).x() - newChests.get(1).x()) + Math.abs(newChests.get(0).z() - newChests.get(1).z()) == 1) {
            items.add(StripPlan.dig(new ClearJob.Options().box(MineGeom.box(newChests.get(0), newChests.get(1), 2)).label("digging room for the mine chests"), 0, null, false));
        } else {
            for (Pos c : newChests) items.add(StripPlan.dig(new ClearJob.Options().box(MineGeom.box(c, c, 2)).label("digging room for a mine chest"), 0, null, false));
        }
        if (newTable != null) {
            items.add(StripPlan.dig(new ClearJob.Options().box(MineGeom.box(newTable, newTable, 1)).label("digging room for a crafting table"), 0, null, false));
            items.add(StripPlan.craft("crafting_table", "minecraft:crafting_table", 1, false));
            items.add(StripPlan.place("minecraft:crafting_table", newTable));
        }
        if (!newChests.isEmpty()) {
            items.add(StripPlan.craft("chest " + newChests.size(), "minecraft:chest", newChests.size(), false));
            for (Pos c : newChests) items.add(StripPlan.place("minecraft:chest", c));
        }
        StripPlan.Item done = StripPlan.simple("setupdone", 0);
        List<Pos> all = new ArrayList<>(chests);
        all.addAll(newChests);
        done.chests = all;
        done.table = table != null ? table : newTable;
        items.add(done);
        return new Setup(items, notes.isEmpty() ? null : "used " + String.join(" and ", notes) + " already there", null);
    }

    private static Pos pick(StripWorld w, List<Pos> cands, Set<String> used, int h) {
        for (Pos c : cands) {
            if (used.contains(c.key()) || !usable(w, c, h)) continue;
            used.add(c.key());
            return c;
        }
        return null;
    }

    private static boolean usable(StripWorld w, Pos q, int h) {
        for (int yy = 0; yy < h; yy++) {
            int y = q.y() + yy;
            if (w.fluid(q.x(), y, q.z()) || ClearEngine.nextToLiquid(w, q.x(), y, q.z())) return false;
            if (w.replaceable(q.x(), y, q.z())) continue;
            if (!ClearEngine.clearableState(w, q.x(), y, q.z(), false)) return false;
        }
        return !w.noCollision(q.x(), q.y() - 1, q.z());       // a floor to put it on
    }
}
