package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.clear.ClearBox;
import io.github.mojolowjo.entropybot.clear.LeaseSet;
import io.github.mojolowjo.entropybot.farm.FarmPlant;
import io.github.mojolowjo.entropybot.farm.FarmRound;
import io.github.mojolowjo.entropybot.farm.FarmSpot;
import io.github.mojolowjo.entropybot.farm.McFarmWorld;
import io.github.mojolowjo.entropybot.gui.Gui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * P5 "farm plant &lt;crop&gt; [x1 z1 x2 z2 | here &lt;r&gt;]": inside the box (in the areas; it becomes the farm), water
 * sources from carried water buckets on the {@link FarmPlant} lattice (a cell dug out, the bucket emptied into it), grass
 * and dirt tilled with a hoe (from the bag, the chests, or made), seeds planted on the wet cells. Never outside the box:
 * the job's break and place leases cover the box alone (the guard checks every click). Steps "plant..." (Seq routes them
 * here). Loader notes: vanilla client calls only (MultiPlayerGameMode.useItemOn / useItem / startDestroyBlock).
 */
final class FarmPlanting {
    private static final Logger LOG = LogUtils.getLogger();
    private static FarmPlanting instance;

    static final String[] HOES = {"minecraft:netherite_hoe", "minecraft:diamond_hoe", "minecraft:iron_hoe", "minecraft:golden_hoe",
            "minecraft:stone_hoe", "minecraft:wooden_hoe"};
    /** The longest planting job, in ticks (5 minutes). */
    static final long MAX_TICKS = 20L * 60 * 5;

    final Core core;
    final Commands commands;
    final Jobs jobs;
    final Crafting crafting;
    private final Map<Seq, Run> runs = new WeakHashMap<>();

    private FarmPlanting(Core core) {
        this.core = core;
        this.commands = core.commands;
        this.jobs = commands.jobs;
        this.crafting = commands.crafting;
    }

    static synchronized FarmPlanting get() {
        if (instance == null) instance = new FarmPlanting(Core.INSTANCE);
        return instance;
    }

    static final class Run {
        String seed, crop, dim;
        int x1, z1, x2, z2, refY;
        long deadline;
        LeaseSet leases;
        FarmPlant.Layout layout;
        final List<FarmPlant.Cell> water = new ArrayList<>(), cells = new ArrayList<>();
        final Set<String> done = new HashSet<>(), seeded = new HashSet<>(), tilledSet = new HashSet<>();
        final Map<String, Integer> tries = new HashMap<>(), walks = new HashMap<>();
        final Map<String, Long> clickedAt = new HashMap<>();
        int tilled, planted, watered, unreachable, waterFailed;
        String hoeNote, stopWhy, walkKey;
        long walkStart = -1;
        int[] digging;
        boolean noHoe;
    }

    // ==== the verb ====

    String command(LocalPlayer p, String rest, String from) {
        FarmPlant.Args a = FarmPlant.parse(rest);
        if (a.error() != null) return a.error();
        String seed = FarmPlant.seedFor(a.crop());
        if (seed == null || !Storage.itemExists(seed)) {
            return "error: I don't know seeds for " + a.crop() + " - wheat, carrot, potato, beetroot, or a seed id (mysticalagriculture:inferium_seeds)";
        }
        if (jobs.running() && !jobs.walking()) return "error: busy with \"" + jobs.job.status + "\" - send stop first";
        Minecraft mc = Minecraft.getInstance();
        int[] me = Jobs.here(p);
        int[] box;
        int refY;
        String dim = Storage.dim();
        if (a.here()) {
            PolicyCommands.Pos c = commands.resolvePos(mc, "", from);
            if (c == null) return "error: I can't see you - come closer, or give the box: farm plant " + a.crop() + " x1 z1 x2 z2";
            box = new int[] {c.x() - a.r(), c.z() - a.r(), c.x() + a.r(), c.z() + a.r()};
            refY = c.y() - 1;
        } else if (a.box() != null) {
            box = a.box();
            refY = me[1] - 1;
        } else {
            JsonFarm f = farmSpot();
            if (f == null) return "error: I don't know a farm - give the box: farm plant " + a.crop() + " here <r> (or x1 z1 x2 z2)";
            int r = io.github.mojolowjo.entropybot.farm.FarmRules.FARM_R;
            box = new int[] {f.spot.x() - r, f.spot.z() - r, f.spot.x() + r, f.spot.z() + r};
            refY = f.spot.y() - 1;
        }
        int cx = (box[0] + box[2]) / 2, cz = (box[1] + box[3]) / 2;
        if (Math.abs(cx - me[0]) > 48 || Math.abs(cz - me[2]) > 48) return "error: the farm box is more than 48 blocks from me - walk me nearer first";
        for (int x = box[0]; x <= box[2]; x++) {
            for (int z = box[1]; z <= box[3]; z++) {
                if (!commands.inAreas(dim, x, z)) return "error: the farm box reaches outside my areas at " + x + " " + z + " - " + PolicyCommands.AREA_HINT.trim();
            }
        }
        if (Jobs.baritone() == null) return "error: baritone not loaded";
        Run r = new Run();
        r.seed = seed;
        r.crop = FarmPlant.cropName(seed);
        r.dim = dim;
        r.x1 = box[0];
        r.z1 = box[1];
        r.x2 = box[2];
        r.z2 = box[3];
        r.refY = refY;
        r.deadline = core.tick() + MAX_TICKS;
        FarmPlant.Layout lay = FarmPlant.plan(r.x1, r.z1, r.x2, r.z2, ground(p.level(), refY), bag(p, "minecraft:water_bucket"));
        if (lay.plant().isEmpty() && lay.water().isEmpty()) {
            return "error: nothing to plant in " + boxText(r) + ": " + (lay.dry() > 0 ? lay.dry() + " cells have no water within 4 - give me a water bucket" : "no grass, dirt or free farmland");
        }
        List<Seq.Step> steps = new ArrayList<>();
        // the seeds: the bag first, then the chests
        int need = lay.plant().size(), have = bag(p, seed);
        List<Crafting.Source> src = crafting.storageSources(p);
        if (have < need) {
            int stored = 0;
            for (Crafting.Source s : src) stored += s.items().getOrDefault(seed, 0);
            if (have + stored <= 0) return "error: no seeds for " + r.crop + " (" + GuiShort.of(seed) + ") in my bag or chests - next: give me some";
            if (stored > 0) {
                Map<String, Integer> want = new LinkedHashMap<>();
                want.put(seed, Math.min(need - have, stored));
                steps.addAll(Crafting.takeTrips(src, want).steps());
            }
        }
        // the hoe: needed only when there is grass or dirt to till
        boolean tillable = false;
        for (FarmPlant.Cell c : lay.plant()) if (c.kind() == FarmPlant.Kind.TILL) tillable = true;
        for (FarmPlant.Cell c : lay.water()) if (c.kind() == FarmPlant.Kind.TILL) tillable = true;
        if (tillable && hoeSlot(p) < 0) r.hoeNote = hoeSteps(p, src, steps);
        steps.add(Seq.Step.walk(new int[] {cx, refY + 1, cz}, true));
        steps.add(new Seq.Step("plantstep"));
        // the box is the farm from now on (as "farm here"; the compact setting stays)
        JsonFarm old = farmSpot();
        commands.putPlace("farm", new FarmSpot(cx, refY + 1, cz, dim, old != null ? old.spot.compact() : null).toJson());
        String label = "planting " + r.crop + " in " + boxText(r);
        Seq s = new Seq(jobs, commands.storage, label, steps, "always");
        runs.put(s, r);
        String res = jobs.startSeq(s, "always");
        jobs.job.holdOnFight = true;
        LOG.info("[entropybot] farm plant: {} ({} cells to plant, {} water, {} dry, {} buckets, hoe {})", label, lay.plant().size(), lay.water().size(),
                lay.dry(), bag(p, "minecraft:water_bucket"), hoeSlot(p) >= 0 ? "carried" : r.hoeNote);
        return res + (lay.dry() > 0 && lay.water().isEmpty() ? " (" + lay.dry() + " cells have no water within 4 and stay as they are - give me a water bucket to water them)" : "");
    }

    private record JsonFarm(FarmSpot spot) {}

    private JsonFarm farmSpot() {
        var o = core.knowledge.places().get("farm");
        FarmSpot f = o == null ? null : FarmSpot.fromJson(o);
        return f == null ? null : new JsonFarm(f);
    }

    static String boxText(Run r) { return r.x1 + " " + r.z1 + " " + r.x2 + " " + r.z2; }

    /** Tiny helper: "minecraft:x" -> "x". */
    static final class GuiShort {
        static String of(String id) { return id.startsWith("minecraft:") ? id.substring(10) : id; }
    }

    static int bag(LocalPlayer p, String id) { return Gui.inventory(p).getOrDefault(id, 0); }

    static int hoeSlot(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            String id = Gui.itemId(p.getInventory().getItem(i));
            if (id != null && id.endsWith("_hoe")) return i;
        }
        return -1;
    }

    /** No hoe: one from the chests, else a stone or wooden one made at a table near; the note when neither works. */
    private String hoeSteps(LocalPlayer p, List<Crafting.Source> src, List<Seq.Step> steps) {
        for (String id : HOES) {
            for (Crafting.Source x : src) {
                if (x.items().getOrDefault(id, 0) <= 0) continue;
                Map<String, Integer> need = new LinkedHashMap<>();
                need.put(id, 1);
                steps.addAll(Crafting.takeTrips(src, need).steps());
                return "fetched a " + GuiShort.of(id);
            }
        }
        int[] table = crafting.findTable(p);
        if (table == null || Jobs.distSq(table, Jobs.here(p)) > 48 * 48) return "no hoe, and no crafting table near me to make one";
        String lastErr = null;
        for (String text : List.of("minecraft:stone_hoe 1", "minecraft:wooden_hoe 1")) {
            Crafting.Prepared pr = crafting.prepareCraft(p, text, null);
            if (pr.err() != null) {
                lastErr = pr.err().replaceFirst("^error: ", "");
                continue;
            }
            for (Seq.Step a : pr.steps()) a.direct = false;
            steps.addAll(pr.steps());
            return "made a " + (text.contains("stone") ? "stone" : "wooden") + " hoe";
        }
        return "no hoe and nothing to make one from" + (lastErr != null ? " (" + lastErr + ")" : "");
    }

    // ==== the world ====

    /** The box's columns as FarmPlant sees them, looking from refY + 3 down to refY - 4 (client thread). */
    static FarmPlant.Ground ground(Level level, int refY) {
        return (x, z) -> {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int y = refY + 3; y >= refY - 4; y--) {
                m.set(x, y, z);
                if (!level.isLoaded(m)) return null;
                BlockState st = level.getBlockState(m);
                if (st.isAir()) continue;
                if (isCrop(st)) {
                    boolean onFarmland = id(level.getBlockState(new BlockPos(x, y - 1, z))).equals("minecraft:farmland");
                    return new FarmPlant.Cell(x, y - 1, z, onFarmland ? FarmPlant.Kind.PLANTED : FarmPlant.Kind.BLOCKED);
                }
                if (isPlant(level, m, st)) continue;
                var fluid = st.getFluidState();
                if (!fluid.isEmpty()) return new FarmPlant.Cell(x, y, z, fluid.is(FluidTags.WATER) && fluid.isSource() ? FarmPlant.Kind.WATER : FarmPlant.Kind.BLOCKED);
                String id = id(st);
                FarmPlant.Kind k = switch (id) {
                    case "minecraft:farmland" -> FarmPlant.Kind.FARMLAND;
                    case "minecraft:grass_block", "minecraft:dirt", "minecraft:dirt_path" -> FarmPlant.Kind.TILL;
                    default -> FarmPlant.Kind.BLOCKED;
                };
                return new FarmPlant.Cell(x, y, z, k);
            }
            return null;
        };
    }

    static String id(BlockState st) { return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString(); }

    /** A crop (vanilla's tag, or any CropBlock: Mystical Agriculture's). */
    static boolean isCrop(BlockState st) {
        return st.is(BlockTags.CROPS) || st.getBlock() instanceof net.minecraft.world.level.block.CropBlock;
    }

    /** Short grass, flowers, ferns: no collision, no liquid, replaceable (a seed goes over it, a hoe needs it gone). */
    static boolean isPlant(Level level, BlockPos pos, BlockState st) {
        return !st.isAir() && st.getCollisionShape(level, pos).isEmpty() && st.getFluidState().isEmpty() && st.canBeReplaced() && !isCrop(st);
    }

    // ==== the steps ====

    String step(Seq s, Seq.Step st, LocalPlayer p, long elapsed) {
        Run r = runs.get(s);
        if (r == null) return "the planting run is gone";
        String res;
        try {
            res = st.type.equals("plantstep") ? main(s, r, p) : "unknown step " + st.type;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] farm plant: {}", e.toString());
            res = "farm plant failed: " + e;
        }
        if (!res.equals("wait") && r.leases != null) {
            r.leases.releaseAll();
            Minecraft.getInstance().options.keyAttack.setDown(false);
        }
        return res;
    }

    private String main(Seq s, Run r, LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        Level level = p.level();
        if (r.layout == null) {
            // standing at the farm now: the layout again, from what the loaded chunks show
            r.layout = FarmPlant.plan(r.x1, r.z1, r.x2, r.z2, ground(level, r.refY), bag(p, "minecraft:water_bucket"));
            r.water.addAll(r.layout.water());
            r.cells.addAll(r.layout.plant());
            r.leases = Clearing.newLeases();
            ClearBox b = new ClearBox(r.x1, r.refY - 4, r.z1, r.x2, r.refY + 4, r.z2);
            String le = r.leases.take("farm plant " + boxText(r), b, false, false);
            if (le == null) le = r.leases.take("farm plant " + boxText(r) + " (seeds, water)", b, true, false);
            if (le != null) return le.replaceFirst("^error: ", "");
            if (hoeSlot(p) < 0) r.noHoe = true;
        }
        String le = r.leases.ensure();
        if (le != null) return le.replaceFirst("^error: ", "");
        r.leases.beat();
        if (core.tick() > r.deadline) return finish(s, r, p, "out of time (5 minutes)");
        McFarmWorld w = new McFarmWorld(p);
        long now = core.tick();
        // a walk under way: let it get there
        if (r.walkStart >= 0) {
            if (now - r.walkStart < 15 || (w.pathing() && now - r.walkStart < 300)) return "wait";
            w.apply(new FarmRound.CancelWalk());
            r.walkStart = -1;
        }
        Vec3 eye = p.getEyePosition();
        // 1. the water sources first (fresh farmland without water dries back to dirt)
        for (FarmPlant.Cell c : r.water) {
            String key = c.key();
            if (r.done.contains(key)) continue;
            BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
            BlockState st = level.getBlockState(pos);
            if (st.getFluidState().is(FluidTags.WATER) && st.getFluidState().isSource()) {
                r.done.add(key);
                r.watered++;
                continue;
            }
            if (bag(p, "minecraft:water_bucket") <= 0 || r.tries.getOrDefault(key, 0) >= 40 || !sealed(level, c)) {
                r.done.add(key);
                r.waterFailed++;
                if (r.digging != null) mc.options.keyAttack.setDown(false);
                continue;
            }
            int[] me = Jobs.here(p);
            if (me[0] == c.x() && me[2] == c.z()) return walkTo(r, w, key, new int[] {c.x() + 2, c.y() + 1, c.z()}, 0, now);
            if (eye.distanceTo(new Vec3(c.x() + 0.5, c.y() + 0.5, c.z() + 0.5)) > 3.5) return walkTo(r, w, key, new int[] {c.x(), c.y() + 1, c.z()}, 2, now);
            if (!st.isAir() && !st.canBeReplaced()) {
                // dig the cell out (a break lease covers the box; farmland is on the guard's built-block list, so a
                // farmland cell gets a one-cell force lease of its own)
                if (id(st).equals("minecraft:farmland") && (r.digging == null || r.digging[0] != c.x() || r.digging[2] != c.z())) {
                    String fl = r.leases.take("farm plant water hole " + c.x() + " " + c.y() + " " + c.z(), new ClearBox(c.x(), c.y(), c.z(), c.x(), c.y(), c.z()), false, true);
                    if (fl != null) {
                        r.done.add(key);
                        r.waterFailed++;
                        continue;
                    }
                }
                p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(c.x() + 0.5, c.y() + 0.5, c.z() + 0.5));
                if (r.digging == null || r.digging[0] != c.x() || r.digging[2] != c.z()) {
                    mc.gameMode.startDestroyBlock(pos, Direction.UP);
                    r.digging = new int[] {c.x(), c.y(), c.z()};
                } else {
                    mc.gameMode.continueDestroyBlock(pos, Direction.UP);
                }
                p.swing(InteractionHand.MAIN_HAND);
                r.tries.merge(key, 1, Integer::sum);
                s.setStatus(s.label + " - digging a water hole at " + c.x() + " " + c.y() + " " + c.z());
                return "wait";
            }
            r.digging = null;
            Long at = r.clickedAt.get(key);
            if (at != null && now - at < 10) return "wait";
            // aim into the hole and pour: the bucket's own ray must land in this cell
            if (!Clearing.holdItem(p, "minecraft:water_bucket")) continue;
            p.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(c.x() + 0.5, c.y() + 0.4, c.z() + 0.5));
            mc.gameRenderer.pick(1.0f);
            if (!(mc.hitResult instanceof BlockHitResult h) || h.getType() != HitResult.Type.BLOCK
                    || !h.getBlockPos().relative(h.getDirection()).equals(pos)) {
                r.tries.merge(key, 5, Integer::sum);
                return walkTo(r, w, key + " close", new int[] {c.x() + 1, c.y() + 1, c.z()}, 0, now);
            }
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            r.clickedAt.put(key, now);
            r.tries.merge(key, 10, Integer::sum);
            s.setStatus(s.label + " - pouring water at " + c.x() + " " + c.y() + " " + c.z());
            return "wait";
        }
        // 2. till and plant, the nearest cell in reach first
        FarmPlant.Cell walkTo = null;
        double walkD = 0;
        boolean pending = false;
        for (FarmPlant.Cell c : r.cells) {
            String key = c.key();
            if (r.done.contains(key)) continue;
            BlockPos gpos = new BlockPos(c.x(), c.y(), c.z()), above = gpos.above();
            BlockState g = level.getBlockState(gpos), a = level.getBlockState(above);
            String gid = id(g);
            if (gid.equals("minecraft:farmland") && !a.isAir() && !isPlant(level, above, a)) {
                r.done.add(key);
                if (r.seeded.contains(key)) r.planted++;
                continue;
            }
            if (r.tries.getOrDefault(key, 0) >= 4) {
                r.done.add(key);
                r.unreachable++;
                continue;
            }
            boolean till = !gid.equals("minecraft:farmland");
            if (till && !(gid.equals("minecraft:grass_block") || gid.equals("minecraft:dirt") || gid.equals("minecraft:dirt_path"))) {
                r.done.add(key);
                r.unreachable++;
                continue;
            }
            if (till && hoeSlot(p) < 0) {
                r.noHoe = true;
                continue;             // left for the report
            }
            if (!till && bag(p, r.seed) <= 0) {
                r.stopWhy = "no seeds";
                continue;
            }
            Long at = r.clickedAt.get(key);
            if (at != null && now - at < 12) {
                pending = true;       // the server hasn't answered yet
                continue;
            }
            Vec3 top = new Vec3(c.x() + 0.5, c.y() + 1.0, c.z() + 0.5);
            double d = eye.distanceTo(top);
            if (d > 4.3) {
                if (walkTo == null || d < walkD) {
                    walkTo = c;
                    walkD = d;
                }
                continue;
            }
            p.lookAt(EntityAnchorArgument.Anchor.EYES, top);
            if (!a.isAir() && isPlant(level, above, a)) {
                // short grass on it: a hoe needs air above, so it goes first
                mc.gameMode.startDestroyBlock(above, Direction.UP);
                p.swing(InteractionHand.MAIN_HAND);
                r.clickedAt.put(key, now - 6);
                return "wait";
            }
            if (till) {
                p.getInventory().selected = selectHoe(mc, p);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(top, Direction.UP, gpos, false));
                p.swing(InteractionHand.MAIN_HAND);
                if (r.tilledSet.add(key)) r.tilled++;
                s.setStatus(s.label + " - tilling (" + r.tilled + ")");
            } else {
                if (!Clearing.holdItem(p, r.seed)) {
                    r.stopWhy = "no seeds";
                    continue;
                }
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(top, Direction.UP, gpos, false));
                p.swing(InteractionHand.MAIN_HAND);
                r.seeded.add(key);
                s.setStatus(s.label + " - planting (" + (r.planted + 1) + ")");
            }
            r.clickedAt.put(key, now);
            r.tries.merge(key, 1, Integer::sum);
            return "wait";
        }
        if (walkTo != null) return walkTo(r, w, walkTo.key(), new int[] {walkTo.x(), walkTo.y() + 1, walkTo.z()}, 2, now);
        if (pending) return "wait";
        return finish(s, r, p, null);
    }

    /** A hoe in the hand: its hotbar slot, or swapped into the selected one (Clearing.holdItem). */
    private static int selectHoe(Minecraft mc, LocalPlayer p) {
        int i = hoeSlot(p);
        if (i >= 0 && i < 9) return i;
        if (i >= 0) Clearing.holdItem(p, Gui.itemId(p.getInventory().getItem(i)));
        return p.getInventory().selected;
    }

    /** The water stays in its hole: solid on all four sides and below (else it would run over the field). */
    private static boolean sealed(Level level, FarmPlant.Cell c) {
        BlockPos pos = new BlockPos(c.x(), c.y(), c.z());
        if (level.getBlockState(pos.below()).getCollisionShape(level, pos.below()).isEmpty()) return false;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = pos.relative(d);
            BlockState st = level.getBlockState(n);
            if (st.getCollisionShape(level, n).isEmpty() && !(st.getFluidState().is(FluidTags.WATER) && st.getFluidState().isSource())) return false;
        }
        return true;
    }

    /** Walk toward a cell (range 0: onto that block); after 3 walks for the same cell it counts as unreachable. */
    private String walkTo(Run r, McFarmWorld w, String key, int[] to, int range, long now) {
        int n = r.walks.merge(key, 1, Integer::sum);
        if (n > 3) {
            r.tries.put(key.replace(" close", ""), 99);
            return "wait";
        }
        Jobs.safeSettings();
        w.walk(new FarmRound.Walk(to[0], to[1], to[2], range));
        r.walkStart = now;
        return "wait";
    }

    private String finish(Seq s, Run r, LocalPlayer p, String early) {
        int left = 0;
        for (FarmPlant.Cell c : r.cells) if (!r.done.contains(c.key())) left++;
        left += r.unreachable;
        String why = early != null ? early : r.stopWhy != null ? r.stopWhy : r.noHoe ? "no hoe" + (r.hoeNote != null ? " (" + r.hoeNote + ")" : "")
                : r.unreachable > 0 ? "couldn't reach or work them" : null;
        StringBuilder sb = new StringBuilder(FarmPlant.report(r.crop, r.planted, r.tilled, r.watered, left, why));
        if (r.layout != null && r.layout.dry() > 0) {
            sb.append("; ").append(r.layout.dry()).append(" cells have no water within 4")
                    .append(r.layout.waterWanted() > r.layout.water().size() ? " (give me " + (r.layout.waterWanted() - r.layout.water().size()) + " more water bucket" + (r.layout.waterWanted() - r.layout.water().size() > 1 ? "s" : "") + ")" : "");
        }
        if (r.waterFailed > 0) sb.append("; ").append(r.waterFailed).append(" water hole").append(r.waterFailed > 1 ? "s" : "").append(" not filled (open sides or no bucket left)");
        if (r.hoeNote != null && !r.noHoe) sb.append("; ").append(r.hoeNote);
        s.note = sb.toString();
        LOG.info("[entropybot] farm plant: {}", s.note);
        return "next";
    }
}
