package io.github.mojolowjo.entropybot.commands;

import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.cave.MineRules;
import io.github.mojolowjo.entropybot.chop.ChopRules;
import io.github.mojolowjo.entropybot.chop.TreeFinder;
import io.github.mojolowjo.entropybot.clear.ClearJob;
import io.github.mojolowjo.entropybot.clear.ClearRules;
import io.github.mojolowjo.entropybot.clear.Pos;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import io.github.mojolowjo.entropybot.surface.SurfaceColumns;
import io.github.mojolowjo.entropybot.surface.SurfaceExport;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * P3 (SURVIVAL_PLAN P3): "chop &lt;logs&gt; [log type]" / "chop trees &lt;n&gt; [log type]" / "chop status". A {@link Seq} job
 * whose "chopstep" decides and splices the work in front of itself: a walk to the tree, a clear of its bottom log(s)
 * (the clear engine: reach, sight, the best-fitting axe, its own break lease, fights and meals hold it, a full bag goes
 * to the base chests), a 2 s wait (Falling Trees' fall), a re-scan of the tree's own logs and more clears until none
 * stands (TreeChop needs several chops; with neither mod each log is broken), a pickup of the drops, then the sapling
 * placed where the trunk stood ({@link Clearing#placeStep}, a place lease). Leaves are never broken (the owner: they
 * decay). The decisions are pure in {@code chop/} ({@link TreeFinder}, {@link ChopRules}).
 * <p>
 * Loader notes: candidates come from the surface export's column source ({@link SurfaceExport#liveSource}, vanilla
 * client level reads); the tree mods' presence from {@code net.neoforged.fml.ModList.get().isLoaded} (Fabric:
 * {@code FabricLoader.getInstance().isModLoaded}); everything else goes through the existing NeoForge-free paths
 * (ClearEngine breaks via the game mode, placing via {@code useItemOn}). No new hooks or mixins. The breaks are the
 * job's purpose, so the P1 restore ledger records none of them (no path context is set).
 */
final class Chopping {
    private static final Logger LOG = LogUtils.getLogger();
    private static Chopping instance;

    final Core core;
    final Commands commands;
    final Jobs jobs;
    final Storage storage;
    final Crafting crafting;
    private final Map<Seq, Run> runs = new WeakHashMap<>();
    /** For "chop status": the totals since the game started and the last end line. */
    private int treesTotal, logsTotal, replantedTotal;
    private String lastEnd = "none yet";

    private Chopping(Core core) {
        this.core = core;
        this.commands = core.commands;
        this.jobs = commands.jobs;
        this.storage = commands.storage;
        this.crafting = commands.crafting;
    }

    static synchronized Chopping get() {
        if (instance == null) instance = new Chopping(Core.INSTANCE);
        return instance;
    }

    /** "chop status" answers at once, even mid-job. */
    static boolean instant(String rest) { return ChopRules.parse(rest).status() || (rest != null && rest.trim().toLowerCase().startsWith("check ")); }

    /** "chop check x z": what the tree search sees in that column (for a "no trees" that looks wrong). */
    private String probe(LocalPlayer p, String rest) {
        String[] w = rest.trim().split("\\s+");
        if (w.length != 3) return "usage: chop check x z";
        int x, z;
        try {
            x = Integer.parseInt(w[1]);
            z = Integer.parseInt(w[2]);
        } catch (NumberFormatException e) {
            return "usage: chop check x z";
        }
        int[] me = Jobs.here(p);
        SurfaceColumns.Source src = SurfaceExport.liveSource(p.clientLevel);
        int top = src.top(x, z);
        StringBuilder sb = new StringBuilder("column " + x + " " + z + ": top " + top + ", kinds");
        for (int y = Math.min(top, me[1] + 40); y >= Math.min(top, me[1] + 40) - 8; y--) sb.append(" ").append(y).append("=").append(src.kind(x, y, z)).append("/").append(src.family(x, y, z));
        int y = ChopRules.logTop(src, x, z, me[1] + 40, me[1] - 24);
        sb.append("; log top ").append(y).append("; in areas ").append(commands.inAreas(Storage.dim(), x, z));
        if (y != ChopRules.NONE) {
            TreeFinder.Result r = TreeFinder.find(new Live(p.clientLevel), x, y, z);
            sb.append("; ").append(r.ok() ? "a tree of " + r.tree().logs().size() + " " + ChopRules.shortId(r.tree().logId()) + " at " + r.tree().at() : r.why());
        }
        return sb.toString();
    }

    private long now() { return core.tick(); }

    static final class Run {
        ChopRules.Args args;
        long deadline;
        int trees, replanted, failsInRow, rounds, lastLeft;
        final Map<String, Integer> got = new LinkedHashMap<>();
        final Set<String> tried = new HashSet<>();
        final List<String> notes = new ArrayList<>();
        int skippedBuilt;
        String firstBuilt, nearestOutside, axeNote, firstWhy;
        int looked;
        TreeFinder.Tree tree;
        Map<String, Integer> before;
        String phase;
        List<int[]> planted = List.of();
        String sapling;
        int brokeAny;
    }

    // ==== the verb ====

    String command(LocalPlayer p, String rest) {
        if (rest != null && rest.trim().toLowerCase().startsWith("check ")) return probe(p, rest);
        ChopRules.Args a = ChopRules.parse(rest);
        if (a.error() != null) return a.error();
        if (a.status()) return status();
        if (jobs.running() && !jobs.walking()) return "error: busy with \"" + jobs.job.status + "\" - send stop first";
        try {
            if (MineRules.policyBoxes(MineRules.parsePolicy(commands.policyJson()), "areas", PolicyCommands.DEFAULT_DIM).isEmpty())
                return "error: I chop only inside my areas, and there are none - " + PolicyCommands.AREA_HINT;
        } catch (MineRules.BadPolicy e) {
            return "error: " + MineRules.badPolicyText(e).replace("I won't mine", "I won't chop");
        }
        int[] me = Jobs.here(p);
        if (!commands.inAreas(Storage.dim(), me[0], me[2])) return "error: I'm outside my areas - walk me into one first (" + PolicyCommands.AREA_HINT.trim() + ")";
        if (Jobs.baritone() == null) return "error: baritone not loaded";
        Run r = new Run();
        r.args = a;
        r.deadline = now() + ChopRules.MAX_TICKS;
        String what = a.trees() ? a.n() + (a.n() == 1 ? " tree" : " trees") : a.n() + " logs";
        String label = "chopping " + what + (a.type() != null ? " (" + a.type() + ")" : "");
        List<Seq.Step> steps = new ArrayList<>();
        if (axeSlot(p) < 0) steps.add(new Seq.Step("chopaxe"));
        steps.add(new Seq.Step("chopstep"));
        Seq s = new Seq(jobs, storage, label, steps, "always");
        runs.put(s, r);
        String res = jobs.startSeq(s, "always");
        jobs.job.holdOnFight = true;              // a fight or a meal holds the chopping, it doesn't end it
        RestoreLive.INSTANCE.scanBuilds(p, me);   // P1: someone's build near here? (a whisper, never a box)
        LOG.info("[entropybot] chop: {} ({})", label, modsLine());
        return res;
    }

    private static String modsLine() {
        boolean ft = false, tc = false;
        try {
            ft = ModList.get().isLoaded("fallingtrees");
            tc = ModList.get().isLoaded("treechop");
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] chop: can't read the mod list: {}", e.toString());
        }
        return ChopRules.modsLine(ft, tc);
    }

    private String status() {
        return "chop: " + modsLine() + "; since the game started " + treesTotal + " trees, " + logsTotal + " logs, " + replantedTotal
                + " replanted; last: " + lastEnd + (jobs.running() && jobs.job.seq != null && runs.containsKey(jobs.job.seq) ? "; running: " + jobs.job.status : "");
    }

    // ==== the steps ====

    String step(Seq s, Seq.Step st, LocalPlayer p, long elapsed) {
        Run r = runs.get(s);
        if (r == null) return "the chop run is gone";
        try {
            switch (st.type) {
                case "chopstep": return main(s, r, p);
                case "chopwait": return elapsed >= st.ticks ? "next" : "wait";
                case "choppickup": return pickup(s, st, r, p, elapsed);
                case "chopplant": return plant(s, r, p);
                case "chopaxe": return axe(s, r, p);
                case "chopaxecheck": return axeCheck(s, st, r, p);
                default: return "unknown step " + st.type;
            }
        } catch (MineRules.BadPolicy e) {
            return end(s, r, p, MineRules.badPolicyText(e).replace("I won't mine", "I won't chop"));
        }
    }

    private void spliceHere(Seq s, List<Seq.Step> add) {
        s.splice(s.idx, add);
        s.stepStart = now();
        s.stage = null;
    }

    private String main(Seq s, Run r, LocalPlayer p) {
        Live w = new Live(p.clientLevel);
        if (r.tree != null && "fell".equals(r.phase)) return afterBreak(s, r, p, w);
        if (r.tree != null && "planted".equals(r.phase)) treeDone(r, p, w);
        String why = ChopRules.stopReason(r.args, ChopRules.total(r.got), r.trees, now(), r.deadline, r.failsInRow);
        if (why != null) return end(s, r, p, why);
        if (Mining.get().bagRoom(p) <= 2) {
            List<Seq.Step> dep = depositSteps(p);
            if (dep == null) return end(s, r, p, "my bag is full and I can't put things away (\"scan base\" once)");
            int[] me = Jobs.here(p);
            dep.add(Seq.Step.walk(me, true));
            spliceHere(s, dep);
            s.setStatus(s.label + " - bag full, putting things away");
            return "wait";
        }
        TreeFinder.Tree t = nextTree(r, p, w);
        if (t == null) {
            String none = r.trees == 0 ? "no trees in my areas" + (r.looked > 0 ? " (" + r.looked + " log columns looked at; " + r.firstWhy + ")"
                    : r.nearestOutside != null ? " (nearest log at " + r.nearestOutside + " is outside)" : "")
                    : "no more trees in my areas";
            return end(s, r, p, none);
        }
        r.tree = t;
        r.phase = "fell";
        r.rounds = 0;
        r.lastLeft = t.logs().size();
        r.brokeAny = 0;
        r.before = Gui.inventory(p);
        r.tried.add(TreeFinder.key(t.base()[0], t.base()[1], t.base()[2]));
        LOG.info("[entropybot] chop: tree at {} ({} logs of {})", t.at(), t.logs().size(), t.logId());
        s.setStatus(s.label + " - the " + ChopRules.shortId(t.logId()).replace("_log", "") + " tree at " + t.at() + " (" + ChopRules.total(r.got) + " logs so far)");
        List<Seq.Step> add = new ArrayList<>();
        add.add(Seq.Step.walk(t.base().clone(), true));
        add.addAll(breakSteps(r, TreeFinder.lowest(t.logs())));
        spliceHere(s, add);
        return "wait";
    }

    /** One break round: a clear of these cells, then 40 ticks for a fall (Falling Trees' animation is 1.5 s). */
    private List<Seq.Step> breakSteps(Run r, List<int[]> cells) {
        List<Pos> only = new ArrayList<>();
        for (int[] c : cells) only.add(new Pos(c[0], c[1], c[2]));
        Seq.Step clear = Clearing.clearStep(new ClearJob.Options().only(only).soft(true)
                .label("chopping the tree at " + r.tree.at()));
        Seq.Step wait = new Seq.Step("chopwait");
        wait.ticks = 40;
        return new ArrayList<>(List.of(clear, wait));
    }

    /** After a break round: more rounds while its logs stand (and progress is made), then the pickup and the replant. */
    private String afterBreak(Seq s, Run r, LocalPlayer p, Live w) {
        List<int[]> left = TreeFinder.left(w, r.tree);
        r.rounds++;
        boolean progress = left.size() < r.lastLeft;
        if (progress) r.brokeAny += r.lastLeft - left.size();
        r.lastLeft = left.size();
        if (!left.isEmpty() && r.rounds < ChopRules.ROUNDS && (progress || r.rounds < 3)) {
            LOG.info("[entropybot] chop: round {} at {}: {} logs left", r.rounds, r.tree.at(), left.size());
            spliceHere(s, breakSteps(r, left));
            return "wait";
        }
        if (!left.isEmpty() && r.brokeAny == 0) {
            // not one log came down: no spot to stand within reach (low leaves all around a short trunk), a guard refusal...
            String clr = lastClear(s);
            r.notes.add("tree at " + r.tree.at() + ": skipped - I couldn't get at its trunk" + (clr != null ? " (" + clr + ")" : ""));
            LOG.info("[entropybot] chop: nothing came down at {} - skipped", r.tree.at());
            r.failsInRow++;
            r.tree = null;
            r.phase = null;
            return main(s, r, p);
        }
        if (!left.isEmpty()) {
            int[] c = left.get(0);
            r.notes.add("tree at " + r.tree.at() + ": " + left.size() + " logs left (out of reach at " + c[0] + " " + c[1] + " " + c[2] + ")");
            LOG.info("[entropybot] chop: giving up on {} logs at {}", left.size(), r.tree.at());
        }
        r.phase = "planted";
        Seq.Step pick = new Seq.Step("choppickup");
        spliceHere(s, new ArrayList<>(List.of(pick, new Seq.Step("chopplant"))));
        return "wait";
    }

    /** Why the last clear step of this job left blocks (its message's tail), or null. */
    private static String lastClear(Seq s) {
        for (int i = s.idx - 1; i >= 0; i--) {
            Seq.Step st = s.steps.get(i);
            if (st.cleared != null && st.cleared.message() != null) {
                String m = st.cleared.message();
                int k = m.indexOf("out of reach");
                return k >= 0 ? m.substring(k).replaceFirst("^out of reach \\(", "").replaceFirst("\\)$", "") : null;
            }
        }
        return null;
    }

    /** The tree is done: what it gave, what was planted. */
    private void treeDone(Run r, LocalPlayer p, Live w) {
        Map<String, Integer> g = ChopRules.gained(r.before, Gui.inventory(p), Chopping::isLogItem);
        g.forEach((k, v) -> r.got.merge(k, v, Integer::sum));
        int planted = 0;
        for (int[] c : r.planted) if (r.sapling != null && r.sapling.equals(w.id(c[0], c[1], c[2]))) planted++;
        r.replanted += planted;
        if (r.brokeAny > 0 || ChopRules.total(g) > 0) {
            r.trees++;
            r.failsInRow = 0;
        } else {
            r.failsInRow++;
        }
        LOG.info("[entropybot] chop: tree at {} done: {}, replanted {}", r.tree.at(), ChopRules.logs(g), planted);
        r.tree = null;
        r.phase = null;
        r.planted = List.of();
    }

    /** The nearest tree in my areas that passes the finder (and the type), or null. */
    private TreeFinder.Tree nextTree(Run r, LocalPlayer p, Live w) {
        String dim = Storage.dim();
        List<Box> prot = MineRules.policyBoxes(MineRules.parsePolicy(commands.policyJson()), "protect", PolicyCommands.DEFAULT_DIM);
        int[] me = Jobs.here(p);
        SurfaceColumns.Source src = SurfaceExport.liveSource(p.clientLevel);
        List<int[]> inside = new ArrayList<>();
        long bestOut = Long.MAX_VALUE;
        for (int x = me[0] - ChopRules.RADIUS; x <= me[0] + ChopRules.RADIUS; x++)
            for (int z = me[2] - ChopRules.RADIUS; z <= me[2] + ChopRules.RADIUS; z++) {
                int y = ChopRules.logTop(src, x, z, me[1] + 40, me[1] - 24);
                if (y == ChopRules.NONE) continue;
                if (commands.inAreas(dim, x, z)) inside.add(new int[]{x, y, z});
                else {
                    long d = (long) (x - me[0]) * (x - me[0]) + (long) (z - me[2]) * (z - me[2]);
                    if (d < bestOut) {
                        bestOut = d;
                        r.nearestOutside = x + " " + y + " " + z;
                    }
                }
            }
        r.looked = inside.size();
        LOG.info("[entropybot] chop: {} log columns in my areas within {} (nearest outside: {})", inside.size(), ChopRules.RADIUS, r.nearestOutside);
        r.looked = inside.size();
        LOG.info("[entropybot] chop: {} log columns in my areas within {} (nearest outside: {})", inside.size(), ChopRules.RADIUS, r.nearestOutside);
        int checked = 0;
        for (int[] c : ChopRules.order(inside, me[0], me[1], me[2])) {
            if (checked >= 24) break;
            TreeFinder.Result res = TreeFinder.find(w, c[0], c[1], c[2]);
            if (!res.ok()) {
                if (r.firstWhy == null) r.firstWhy = res.why();
                if (res.why().contains("built blocks")) {
                    String k = "built " + res.why();
                    if (r.tried.add(k)) {
                        r.skippedBuilt++;
                        if (r.firstBuilt == null) r.firstBuilt = res.why();
                        LOG.info("[entropybot] chop: skipped: {}", res.why());
                    }
                }
                continue;
            }
            TreeFinder.Tree t = res.tree();
            int[] b = t.base();
            if (r.tried.contains(TreeFinder.key(b[0], b[1], b[2]))) continue;
            checked++;
            if (!ChopRules.matches(r.args.type(), t.logId())) {
                if (r.firstWhy == null) r.firstWhy = "the tree at " + t.at() + " is " + ChopRules.shortId(t.logId());
                continue;
            }
            String fence = jobs.goalAllowed(b[0], b[1], b[2]);
            if (fence != null) {
                if (r.firstWhy == null) r.firstWhy = "the tree at " + t.at() + ": " + fence;
                continue;
            }
            boolean off = false;
            for (int[] l : t.logs()) {
                if (!commands.inAreas(dim, l[0], l[2]) || MineRules.protectNear(prot, dim, l[0], l[1], l[2], 0) != null) {
                    off = true;
                    break;
                }
            }
            if (off) {
                if (r.firstWhy == null) r.firstWhy = "the tree at " + t.at() + " reaches out of my areas or into a protect box";
                r.tried.add(TreeFinder.key(b[0], b[1], b[2]));
                continue;
            }
            return t;
        }
        return null;
    }

    /** Walks over the drops near the tree for up to 10 s (Falling Trees drops them where the trunk lands). */
    private String pickup(Seq s, Seq.Step st, Run r, LocalPlayer p, long elapsed) {
        IBaritone b = Jobs.baritone();
        if (r.tree == null || b == null) return "next";
        if (elapsed > 200) {
            Jobs.cancel(b);
            return "next";
        }
        int[] base = r.tree.base();
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : p.clientLevel.entitiesForRendering()) {
            if (!(e instanceof ItemEntity ie) || !e.isAlive()) continue;
            if (Math.abs(e.getX() - base[0]) > 9 || Math.abs(e.getZ() - base[2]) > 9 || Math.abs(e.getY() - base[1]) > 8) continue;
            int x = (int) Math.floor(e.getX()), y = (int) Math.floor(e.getY() + 0.01), z = (int) Math.floor(e.getZ());
            if (jobs.goalAllowed(x, y, z) != null) continue;
            if (p.getInventory().getFreeSlot() < 0 && p.getInventory().getSlotWithRemainingSpace(ie.getItem()) < 0) continue;
            double d = e.distanceTo(p);
            if (d < bd) {
                bd = d;
                best = ie;
            }
        }
        if (best == null) {
            if (elapsed < 30) return "wait";          // the drops of a fall land a moment later
            Jobs.cancel(b);
            return "next";
        }
        if (st.pos == null || st.pos[0] != (int) Math.floor(best.getX()) || st.pos[2] != (int) Math.floor(best.getZ()) || Jobs.idle(b)) {
            st.pos = new int[]{(int) Math.floor(best.getX()), (int) Math.floor(best.getY() + 0.01), (int) Math.floor(best.getZ())};
            Jobs.safeSettings();
            b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(new BlockPos(st.pos[0], st.pos[1], st.pos[2])));
            s.setStatus(s.label + " - picking up the logs");
        }
        return "wait";
    }

    /** The saplings where the trunk stood (from the bag, after the pickup). */
    private String plant(Seq s, Run r, LocalPlayer p) {
        if (r.tree == null) return "next";
        Live w = new Live(p.clientLevel);
        r.sapling = ChopRules.sapling(r.tree.logId());
        List<int[]> free = new ArrayList<>();
        for (int[] c : r.tree.bases()) {
            BlockState st = p.clientLevel.getBlockState(new BlockPos(c[0], c[1], c[2]));
            if (st.canBeReplaced() && w.soil(c[0], c[1] - 1, c[2])) free.add(c);
        }
        int have = r.sapling == null ? 0 : Gui.inventory(p).getOrDefault(r.sapling, 0);
        ChopRules.Replant rp = ChopRules.replant(free.isEmpty() ? List.of() : free, r.sapling, free.isEmpty() ? 0 : have);
        if (free.isEmpty()) {
            r.notes.add("tree at " + r.tree.at() + ": the trunk's spot isn't free - not replanted");
            return "next";
        }
        if (rp.note() != null) r.notes.add("tree at " + r.tree.at() + ": " + rp.note());
        r.planted = rp.cells();
        List<Seq.Step> add = new ArrayList<>();
        for (int[] c : rp.cells()) add.add(Clearing.placeStep(c.clone(), r.sapling, null, true));
        s.splice(s.idx + 1, add);
        return "next";
    }

    // ---- the axe ----

    private static final List<String> AXES = List.of("minecraft:wooden_axe", "minecraft:stone_axe", "minecraft:iron_axe", "minecraft:golden_axe",
            "minecraft:diamond_axe", "minecraft:netherite_axe");

    static boolean isAxe(String id) { return id != null && id.endsWith("_axe") && !id.endsWith("_pickaxe"); }

    private static int axeSlot(LocalPlayer p) {
        for (int i = 0; i < 36; i++) if (isAxe(Gui.itemId(p.getInventory().getItem(i)))) return i;
        return -1;
    }

    /** No axe: one from storage, else a stone or a wooden one made; else on by hand with an actionable note. */
    private String axe(Seq s, Run r, LocalPlayer p) {
        if (axeSlot(p) >= 0) return "next";
        List<Crafting.Source> src = crafting.storageSources(p);
        for (Crafting.Source x : src) {
            for (String id : AXES) {
                if (x.items().getOrDefault(id, 0) <= 0) continue;
                Map<String, Integer> need = new LinkedHashMap<>();
                need.put(id, 1);
                List<Seq.Step> add = new ArrayList<>(Crafting.takeTrips(src, need).steps());
                Seq.Step check = new Seq.Step("chopaxecheck");
                check.text = "fetched";
                add.add(check);
                s.splice(s.idx + 1, add);
                s.setStatus(s.label + " - fetching an axe");
                return "next";
            }
        }
        String lastErr = null;
        int[] table = crafting.findTable(p);
        if (table == null || Jobs.distSq(table, Jobs.here(p)) > 48 * 48) {
            // an axe is a 3x3 recipe: without a table near, a craft would only turn the logs into planks and fail
            r.axeNote = "no axe, and no crafting table near me to make one - chopped by hand; next: give me an axe, or put a crafting table near me";
            LOG.info("[entropybot] chop: {}", r.axeNote);
            return "next";
        }
        for (String text : List.of("minecraft:stone_axe 1", "minecraft:wooden_axe 1")) {
            Crafting.Prepared pr = crafting.prepareCraft(p, text, null);
            if (pr.err() != null) {
                lastErr = pr.err().replaceFirst("^error: ", "");
                continue;
            }
            List<Seq.Step> add = new ArrayList<>(pr.steps());
            for (Seq.Step a : add) a.direct = false;
            Seq.Step check = new Seq.Step("chopaxecheck");
            check.text = "made";
            add.add(check);
            s.splice(s.idx + 1, add);
            s.setStatus(s.label + " - making " + (text.contains("stone") ? "a stone" : "a wooden") + " axe");
            return "next";
        }
        r.axeNote = "no axe and nothing to make one from" + (lastErr != null ? " (" + lastErr + ")" : "")
                + " - chopped by hand; next: give me an axe or 3 logs and a crafting table nearby";
        LOG.info("[entropybot] chop: {}", r.axeNote);
        return "next";
    }

    private String axeCheck(Seq s, Seq.Step st, Run r, LocalPlayer p) {
        int i = axeSlot(p);
        if (i >= 0) {
            r.axeNote = st.text + " a " + ChopRules.shortId(Gui.itemId(p.getInventory().getItem(i)));
            return "next";
        }
        r.axeNote = "no axe (" + st.text + " none) - chopped by hand; next: give me an axe";
        return "next";
    }

    // ---- the end ----

    private String end(Seq s, Run r, LocalPlayer p, String why) {
        List<String> notes = new ArrayList<>();
        if (r.axeNote != null) notes.add(r.axeNote);
        if (r.skippedBuilt > 0) notes.add(r.skippedBuilt + " skipped: " + r.firstBuilt.replaceFirst("^the tree at \\S+ \\S+ \\S+ is ", "")
                + (r.skippedBuilt > 1 ? " and more" : ""));
        notes.addAll(r.notes.size() > 4 ? r.notes.subList(0, 4) : r.notes);
        String next = r.trees == 0 ? (r.skippedBuilt > 0 ? "\"protect\" that build, or \"area add\" one with trees away from builds" : "\"area add <name> here <r>\" where trees grow") : null;
        String text = ChopRules.endText(r.trees, r.got, r.replanted, why, notes, next);
        treesTotal += r.trees;
        logsTotal += ChopRules.total(r.got);
        replantedTotal += r.replanted;
        lastEnd = text;
        LOG.info("[entropybot] chop: {}", text);
        jobs.finish(text);
        return "wait";
    }

    /** The base chests trip (like the clear's), or null when there is nothing to put away / no chests known. */
    private List<Seq.Step> depositSteps(LocalPlayer p) {
        Map<String, Integer> items = StorageRules.depositables(Storage.held(p), "", false, null, storage.keeps());
        if (items.isEmpty()) return null;
        boolean atBase = StorageRules.depositAtBase(core.knowledge.places().get("base"), Jobs.here(p), Storage.dim());
        StorageRules.Plan plan = StorageRules.depositPlan(items, storage.baseChests(p, atBase), Jobs.here(p), "");
        if (plan.err() != null) {
            LOG.info("[entropybot] chop: can't put things away ({})", plan.err());
            return null;
        }
        List<Seq.Step> steps = new ArrayList<>();
        for (StorageRules.Stop stop : plan.stops()) {
            steps.add(Seq.Step.walk(stop.chest().pos(), false));
            steps.add(Seq.Step.open(stop.chest().pos(), "no"));
            Seq.Step put = new Seq.Step("put");
            put.items = stop.items();
            put.fallback = stop.fallback();
            steps.add(put);
            steps.add(Seq.Step.close());
        }
        return steps;
    }

    // ---- the world ----

    static boolean isLogItem(String id) {
        if (id == null) return false;
        String path = id.substring(id.indexOf(':') + 1);
        return (path.endsWith("_log") || path.endsWith("_stem")) && !path.startsWith("stripped_");
    }

    /** The client level as the tree finder sees it. */
    static final class Live implements TreeFinder.World {
        private final ClientLevel level;
        private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        Live(ClientLevel level) { this.level = level; }

        private BlockState st(int x, int y, int z) { return level.getBlockState(m.set(x, y, z)); }

        @Override public String id(int x, int y, int z) {
            if (!level.isLoaded(m.set(x, y, z))) return null;
            return BuiltInRegistries.BLOCK.getKey(st(x, y, z).getBlock()).toString();
        }

        @Override public boolean log(int x, int y, int z) {
            BlockState s = st(x, y, z);
            if (s.isAir()) return false;
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if (ClearRules.choppedLog(id)) return true;                     // TreeChop's partly chopped log
            return s.is(BlockTags.LOGS) && isLogItem(id);                    // never stripped logs or 6-sided wood (built)
        }

        @Override public boolean leaves(int x, int y, int z) {
            BlockState s = st(x, y, z);
            return s.getBlock() instanceof LeavesBlock || s.is(BlockTags.LEAVES);
        }

        @Override public boolean persistentLeaves(int x, int y, int z) {
            BlockState s = st(x, y, z);
            return s.hasProperty(LeavesBlock.PERSISTENT) && s.getValue(LeavesBlock.PERSISTENT);
        }

        @Override public boolean soil(int x, int y, int z) { return st(x, y, z).is(BlockTags.DIRT); }

        @Override public boolean built(int x, int y, int z) {
            BlockState s = st(x, y, z);
            if (s.isAir()) return false;
            String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
            if (ClearRules.choppedLog(id)) return false;                       // TreeChop's partly chopped log
            if (s.hasBlockEntity()) return true;
            if (id.endsWith("_sapling") || id.endsWith("torch") || id.endsWith("_propagule")) return false;   // its own replants and torches
            return ClearRules.builtId(id);
        }
    }

}
