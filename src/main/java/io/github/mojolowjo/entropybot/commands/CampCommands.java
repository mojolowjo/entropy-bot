package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.camp.BootstrapPlan;
import io.github.mojolowjo.entropybot.camp.JunkRules;
import io.github.mojolowjo.entropybot.camp.LightGrid;
import io.github.mojolowjo.entropybot.camp.StockRules;
import io.github.mojolowjo.entropybot.camp.ToolCareRules;
import io.github.mojolowjo.entropybot.camp.ArmorCareRules;
import io.github.mojolowjo.entropybot.guard.Guard;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.gui.Gui;
import io.github.mojolowjo.entropybot.gui.McMenu;
import io.github.mojolowjo.entropybot.storage.StorageRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * C4 + C5 (0.20.1, branch comp-c4): the game side of {@code bootstrap}, {@code light}, {@code stock} / {@code restock
 * base} / {@code gather ... to base}, {@code junk} (and the mid-job junk drop) and tool care. The rules are pure in
 * {@code camp.*} (JUnit); this class reads the world and the bag and starts chains or Seq jobs made of steps that
 * already exist (chop, craft, place, dig, gather, deposit, get), so every step reports on its own and a failed one
 * says what is missing.
 *
 * <p>Loader notes (docs/PLANNING.md): block lookups use vanilla {@code ClientLevel.getBlockState / getFluidState}; the
 * junk drop is a THROW click in the player's own inventory menu ({@link McMenu}, vanilla {@code MultiPlayerGameMode});
 * nothing here is NeoForge-specific, so a Fabric port only swaps the event wiring in Commands.
 */
final class CampCommands {
    private static final Logger LOG = LogUtils.getLogger();
    private final Commands c;
    /** Tool care: kind -> id carried at the last look (a kind gone since = broken), and when each kind was last tried. */
    private Map<String, String> toolsBefore = new LinkedHashMap<>();
    private final Map<String, Long> careTried = new LinkedHashMap<>();
    /** Tool care: kinds nearly broken that nothing could replace (for "check"). */
    final Map<String, String> careStuck = new LinkedHashMap<>();
    static final long CARE_RETRY_MS = 10 * 60_000L;

    private String lastRun;
    CampCommands(Commands c) { this.c = c; }

    private JsonObject brain() { return c.brainData(); }

    // ---------------------------------------------------------------- bootstrap

    String bootstrap(LocalPlayer p, String from, boolean internal, String rest) {
        boolean status = rest != null && rest.trim().equalsIgnoreCase("status");
        if (rest != null && rest.trim().equalsIgnoreCase("food")) return bootstrapFood(p, from, internal);   // 0.30.5
        // S2 (MINING_PLAN 16 Q1): "bootstrap tools" stops after the furnace (AA's tools rung; its rungs do the rest)
        boolean toolsOnly = rest != null && rest.trim().equalsIgnoreCase("tools");
        if (rest != null && !rest.isBlank() && !status && !toolsOnly) return "usage: bootstrap | bootstrap tools | bootstrap status | bootstrap food";
        String self = toolsOnly ? "bootstrap tools" : "bootstrap";
        // 0.24.3: inside a chain its steps go right after it (the chain "bootstrap then gather ..." stopped silently)
        boolean splice = !status && c.chainsRef().running() && internal;
        if (!status && !splice && c.chainsRef().running()) return "busy: " + c.chainsRef().chainStatus() + " (pm \"stop\" first)";
        if (!status && c.jobs.running() && !c.jobs.walking()) return "busy: " + c.jobs.job.status + " (pm \"stop\" first)";
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        int[] here = Jobs.here(p);
        // 0.28.1 (trial fix A): the camp goes on the ground under me (trial 1: on a spruce top, 8 up in the air once it was chopped)
        int[] feet = Jobs.groundUnder(lv, here[0], here[1], here[2]);
        if (feet[1] != here[1]) LOG.info("[entropybot] bootstrap: standing at {}, the camp goes on the ground at {}", Jobs.fmt(here), Jobs.fmt(feet));
        String dim = Guard.dimOf(lv);
        // AREAS: the world is open; only a keep-out / no-break spot refuses the camp
        String blocked = c.policy.blockedText(dim, feet[0], feet[2]);
        if (!status && blocked != null) return "error: this spot is " + blocked + ", so I may not dig or place here";
        boolean tableNear = near(lv, feet, "crafting_table"), furnaceNear = near(lv, feet, "furnace"), chestNear = near(lv, feet, "chest") || near(lv, feet, "barrel");
        int[] dir = quarryDir(lv, feet);
        List<int[]> free = new ArrayList<>(freeRing(lv, feet, dir));
        List<String> pre = new ArrayList<>();
        if (free.size() < 3) {
            // make spots: clear leaves or plants next to me (natural blocks, a dig of one cell and the one above)
            for (int[] cl : io.github.mojolowjo.entropybot.camp.FreeSpots.toClear(feet, x -> clearable(lv, x), 3 - free.size())) {
                pre.add("dig " + cl[0] + " " + cl[1] + " " + cl[2] + " " + cl[0] + " " + (cl[1] + 1) + " " + cl[2]);
                free.add(cl);
            }
        }
        if (free.isEmpty() && !status) {
            int[] spot = flatSpotNear(lv, feet, dir);
            if (spot == null) return Hints.next("error: no free spot for the crafting table within 16 blocks (every cell around me is solid or water)", "goto a flat, open spot, then " + self);
            String text = "goto " + Jobs.fmt(spot) + " then " + self;
            LOG.info("[entropybot] bootstrap: no room at {}, moving to {}", Jobs.fmt(feet), Jobs.fmt(spot));
            if (splice) {
                String err = c.chainsRef().spliceNext(text);
                return err == null ? "ok: no room for a camp here - walking to " + Jobs.fmt(spot) + " first" : "error: " + err;
            }
            return c.chainsRef().startChain(from, self, text, 1);
        }
        int[] table = free.size() > 0 ? free.get(0) : null, furnace = free.size() > 1 ? free.get(1) : null, chest = free.size() > 2 ? free.get(2) : null;
        boolean campMarked = Core.INSTANCE.knowledge.places().containsKey("camp");
        // 0.28.1 (trial fix D): a snow layer on a spot is dug first (trial 1: the table "couldn't go" on snow); snow near = snowy
        for (int[] s : new int[][]{tableNear ? null : table, furnaceNear ? null : furnace, chestNear ? null : chest}) {
            if (s != null && lv.getBlockState(new BlockPos(s[0], s[1], s[2])).is(net.minecraft.world.level.block.Blocks.SNOW))
                pre.add("dig " + s[0] + " " + s[1] + " " + s[2] + " " + s[0] + " " + s[1] + " " + s[2]);
        }
        boolean snowy = snowNear(lv, feet);
        boolean quarryBlocked = quarryBlocked(lv, feet, dir);
        LOG.info("[entropybot] bootstrap: {}", BootstrapPlan.planLine(feet, dir));
        if (quarryBlocked) LOG.info("[entropybot] bootstrap: ice, snow or water in the staircase quarry at {} - the stone step uses gather cobblestone", Jobs.fmt(feet));
        BootstrapPlan.Plan plan = BootstrapPlan.plan(new BootstrapPlan.Facts(Gui.inventory(p), tableNear, furnaceNear, chestNear, campMarked,
                feet, table, furnace, chest, dir, snowy, quarryBlocked));
        if (plan.err() != null) return plan.err();
        List<String> all = new ArrayList<>(pre);
        if (toolsOnly) {
            all.addAll(BootstrapPlan.cutAfterFurnace(plan.steps()));
            if (all.isEmpty()) return "ok: bootstrap tools: the table, the stone tools and the furnace are there already";
        } else all.addAll(io.github.mojolowjo.entropybot.camp.FoodPlan.withFood(plan.steps()));      // 0.30.5: the food step last, always
        if (status) return "bootstrap here would run " + plan.summary() + ": " + String.join(" > ", all);
        LOG.info("[entropybot] bootstrap at {}: {}", Jobs.fmt(feet), String.join(" > ", all));
        if (splice) {
            String err = c.chainsRef().spliceNext(String.join(" then ", all));
            lastRun = (err == null ? "started (in a chain) " : "refused: ") + plan.summary();
            return err == null ? "ok: bootstrap (" + plan.summary() + "): " + all.size() + " steps next in this chain" : "error: bootstrap: " + err;
        }
        String r = c.chainsRef().startChain(from, "bootstrap", String.join(" then ", all), 1);
        lastRun = (r.startsWith("started") ? "started " : "refused: ") + plan.summary();
        return r.startsWith("started") ? "started: bootstrap (" + plan.summary() + ") - each step reports; a failed one says what is missing" : r;
    }

    /**
     * 0.30.5 (trial 9, finding 3): "bootstrap food", the bootstrap chain's last step (FoodPlan). Never fails: the hunt
     * and the cook go in as soft steps. In a chain they go right after it; alone it starts a chain of its own.
     */
    String bootstrapFood(LocalPlayer p, String from, boolean internal) {
        io.github.mojolowjo.entropybot.camp.FoodPlan.Decision d;
        try {
            d = io.github.mojolowjo.entropybot.camp.FoodPlan.decide(Gui.inventory(p), c.hunting.on(), c.hunting.animalsNear(p));
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] bootstrap food: {}", e.toString());
            return "ok: food skipped: couldn't look for food (" + e + ") - next: hunt 4 cow when you see some";
        }
        LOG.info("[entropybot] bootstrap food: {} {}", d.text(), d.steps());
        if (d.steps().isEmpty()) return d.text();
        String text = String.join(" then ", d.steps());
        if (internal && c.chainsRef().running()) {
            String err = c.chainsRef().spliceNext(text);
            return err == null ? d.text() : "ok: food skipped: " + err + " - next: hunt 4 cow";
        }
        if (c.chainsRef().running()) return "busy: " + c.chainsRef().chainStatus() + " (pm \"stop\" first)";
        String r = c.chainsRef().startChain(from, "bootstrap food", text, 1);
        return r.startsWith("started") ? "started: " + d.text().replaceFirst("^ok: ", "") : r;
    }

    /** 0.28.2 (B3): ice, snow or water in a cell of the staircase quarry's dig boxes (BootstrapPlan.quarrySteps). */
    static boolean quarryBlocked(ClientLevel lv, int[] feet, int[] dir) {
        int dx = dir[0], dz = dir[1], px = dz != 0 ? 1 : 0, pz = dx != 0 ? 1 : 0;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= BootstrapPlan.QUARRY_LEN; i++) {
            int cx = feet[0] + dx * (i + 1), cz = feet[2] + dz * (i + 1);
            for (int a = -1; a <= 1; a++) for (int y = BootstrapPlan.stepBottom(feet[1], i); y <= BootstrapPlan.stepTop(feet[1], i); y++) {
                m.set(cx + px * a, y, cz + pz * a);
                if (!lv.isLoaded(m)) continue;
                BlockState st = lv.getBlockState(m);
                if (!st.getFluidState().isEmpty() || st.is(net.minecraft.tags.BlockTags.ICE) || st.is(net.minecraft.tags.BlockTags.SNOW)) return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- house (0.28.2, trial fix package 2 C)

    /** "house &lt;area&gt; [block]": floor, walls, roof, door gap, chest/table/furnace inside, base marked (HousePlan). */
    String house(LocalPlayer p, String from, boolean internal, String rest) {
        List<String> w = io.github.mojolowjo.entropybot.commands.Texts.words(rest == null ? "" : rest.trim().toLowerCase());
        if (w.isEmpty() || w.size() > 2) return "usage: house <area> [block]  (e.g. house house, house house oak_planks)";
        boolean splice = c.chainsRef().running() && internal;
        if (!splice && c.chainsRef().running()) return "busy: " + c.chainsRef().chainStatus() + " (pm \"stop\" first)";
        if (c.jobs.running() && !c.jobs.walking()) return "busy: " + c.jobs.job.status + " (pm \"stop\" first)";
        String area = w.get(0);
        JsonObject ab = c.policyArea(area);
        if (ab == null) return "error: I have no area called " + area + " - next: area x z x2 z2 " + area + " main y1 y2";
        if (PolicyCommands.typeOf(ab) == io.github.mojolowjo.entropybot.guard.AreaType.NO_BREAK) return "error: " + area + " is a no-break area - I never build there";
        String[] err = new String[1];
        int[] box = DigCommands.areaBox(c, area, err);
        // 0.30.4 (trial 9, open terrain): an area without heights gets them from the ground: the floor at the lowest
        // surface block of its columns (top solid block, snow layers and leaves skipped), 5 high; saved on the area
        String floorNote = "";
        int[] autoY = null;                       // review 1: saved on the area only once the chain started
        if (box == null && err[0] != null && err[0].contains("covers every height") && !ab.has("round") && Minecraft.getInstance().level != null) {
            int ax1 = Math.min(PolicyCommands.n(ab, "x1"), PolicyCommands.n(ab, "x2")), ax2 = Math.max(PolicyCommands.n(ab, "x1"), PolicyCommands.n(ab, "x2"));
            int az1 = Math.min(PolicyCommands.n(ab, "z1"), PolicyCommands.n(ab, "z2")), az2 = Math.max(PolicyCommands.n(ab, "z1"), PolicyCommands.n(ab, "z2"));
            if (ax2 - ax1 + 1 < io.github.mojolowjo.entropybot.camp.HousePlan.MIN_SIDE || az2 - az1 + 1 < io.github.mojolowjo.entropybot.camp.HousePlan.MIN_SIDE)
                return io.github.mojolowjo.entropybot.camp.HousePlan.TOO_SMALL;
            if ((long) (ax2 - ax1 + 1) * (az2 - az1 + 1) > 4096) return io.github.mojolowjo.entropybot.camp.HouseGround.TOO_LARGE;
            int[] tops = surfaceTops(Minecraft.getInstance().level, ax1, az1, ax2, az2);
            for (int t : tops) if (t == io.github.mojolowjo.entropybot.camp.HouseGround.WATER)
                return io.github.mojolowjo.entropybot.camp.HouseGround.waterText(area, ax1, az1, ax2, az2);
            int fy = io.github.mojolowjo.entropybot.camp.HouseGround.floorY(tops);
            for (int t : tops) if (t == io.github.mojolowjo.entropybot.camp.HouseGround.NONE) { fy = t; break; }
            if (fy == io.github.mojolowjo.entropybot.camp.HouseGround.NONE)
                return "error: I can't see the ground of " + area + " (not loaded) - next: goto " + (ax1 + ax2) / 2 + " ~ " + (az1 + az2) / 2 + ", then house " + area;
            int fy2 = fy + io.github.mojolowjo.entropybot.camp.HouseGround.HEIGHT - 1;
            autoY = new int[]{fy, fy2};
            LOG.info("[entropybot] house {}: no heights given, floor at y {} (lowest surface), y {}..{}", area, fy, fy, fy2);
            floorNote = "floor at y " + fy + " (the lowest ground in " + area + ", 5 high), ";
            box = new int[]{ax1, fy, az1, ax2, fy2, az2};
        }
        Map<String, Integer> inv = Gui.inventory(p);
        // 0.30.2: keep the block that already stands in the house's shell (built blocks and cobblestone, counted per id)
        Map<String, Integer> placed = new java.util.HashMap<>();
        if (box != null) {
            try {
                Clearing.LiveWorld lw = new Clearing.LiveWorld();
                lw.set(p);
                for (io.github.mojolowjo.entropybot.clear.Pos q : io.github.mojolowjo.entropybot.clear.RimPlan.shapeCells("shell",
                        io.github.mojolowjo.entropybot.clear.ClearBox.of(box[0], box[1], box[2], box[3], box[4], box[5]))) {
                    String qid = lw.id(q.x(), q.y(), q.z());
                    if (qid == null || lw.air(q.x(), q.y(), q.z())) continue;
                    // review d: full building blocks only (no torches, doors, glass, ladders, signs, stairs)
                    if (!lw.fullBlock(q.x(), q.y(), q.z()) || !io.github.mojolowjo.entropybot.camp.HousePlan.buildingBlock(qid)) continue;
                    if (lw.builtBlock(q.x(), q.y(), q.z()) || qid.equals("minecraft:cobblestone")) placed.merge(qid, 1, Integer::sum);
                }
            } catch (RuntimeException ex) {
                LOG.warn("[entropybot] house {}: could not count the standing blocks: {}", area, ex.toString());
            }
        }
        String blk = io.github.mojolowjo.entropybot.camp.HousePlan.chooseBlock(w.size() > 1 ? w.get(1) : null, inv, placed);
        String rid = c.crafting.planner.resolveItem(blk.replaceFirst("^minecraft:", ""), inv);
        if (rid != null) blk = rid;
        ClientLevel lv = Minecraft.getInstance().level;
        Map<String, Boolean> standing = new java.util.HashMap<>();
        if (box != null && lv != null) {
            for (String s : io.github.mojolowjo.entropybot.camp.HousePlan.STATIONS) standing.put(s, insideHas(lv, box, s));
        }
        standing.put(io.github.mojolowjo.entropybot.camp.HousePlan.SHELL, placed.getOrDefault(blk, 0) > 0);
        JsonObject camp = Core.INSTANCE.knowledge.places().get("camp");
        int[] cp = camp != null && camp.has("x") ? Jobs.pos(camp) : null;
        // 0.30.4: on a rerun the floor layer inside the wall line may still hold snow or dirt (a natural block is no floor):
        // those cells are dug one by one before the fill (the cobblestone floor itself stays)
        List<int[]> foreign = new java.util.ArrayList<>();
        if (box != null && Boolean.TRUE.equals(standing.get(io.github.mojolowjo.entropybot.camp.HousePlan.SHELL))) {
            try {
                Clearing.LiveWorld lw = new Clearing.LiveWorld();
                lw.set(p);
                int fy = Math.min(box[1], box[4]);
                // review 4: the whole floor layer, the wall ring too (the door cells are above it; built blocks are skipped)
                for (int x = Math.min(box[0], box[3]); x <= Math.max(box[0], box[3]); x++) {
                    for (int z = Math.min(box[2], box[5]); z <= Math.max(box[2], box[5]); z++) {
                        String qid = lw.id(x, fy, z);
                        if (qid == null || lw.air(x, fy, z) || io.github.mojolowjo.entropybot.clear.PlaceRules.free(lw, x, fy, z)) continue;
                        if (qid.equals(blk) || lw.builtBlock(x, fy, z) || lw.blockEntity(x, fy, z)) continue;
                        foreign.add(new int[]{x, fy, z});
                    }
                }
            } catch (RuntimeException ex) {
                LOG.warn("[entropybot] house {}: could not look at the floor layer: {}", area, ex.toString());
            }
        }
        // 0.31.0 (4): the world as it stands, so a rerun leaves out the steps already done (HousePlan names them)
        io.github.mojolowjo.entropybot.camp.HousePlan.Probe probe = null;
        if (box != null) {
            try {
                Clearing.LiveWorld lw = new Clearing.LiveWorld();
                lw.set(p);
                final String house = blk;
                probe = new io.github.mojolowjo.entropybot.camp.HousePlan.Probe() {
                    @Override public boolean hasBlock(int x, int y, int z) { return house.equals(lw.id(x, y, z)); }

                    @Override public boolean clear(int x, int y, int z) {
                        String id = lw.id(x, y, z);
                        if (id == null) return false;                          // not loaded: never counted as done
                        return lw.air(x, y, z) || io.github.mojolowjo.entropybot.clear.PlaceRules.free(lw, x, y, z)
                                || lw.builtBlock(x, y, z) || lw.blockEntity(x, y, z);
                    }

                    @Override public boolean walkable(int x, int y, int z) {           // 0.31.1: the door's outside step
                        if (lw.id(x, y, z) == null) return true;                        // not loaded: no opinion
                        boolean feet = lw.air(x, y, z) || io.github.mojolowjo.entropybot.clear.PlaceRules.free(lw, x, y, z);
                        boolean head = lw.air(x, y + 1, z) || io.github.mojolowjo.entropybot.clear.PlaceRules.free(lw, x, y + 1, z);
                        return feet && head && lw.fullBlock(x, y - 1, z);
                    }

                    @Override public boolean diggableStep(int x, int y, int z) {       // review 4
                        if (lw.id(x, y, z) == null || !lw.fullBlock(x, y - 1, z)) return false;
                        try {
                            var pol = Core.INSTANCE.guard.core.policy();
                            String dim = Storage.dim();
                            return pol.areaAt(dim, x, y, z) != null && pol.areaAt(dim, x, y + 1, z) != null
                                    && pol.protectAt(dim, x, y, z) == null && pol.protectAt(dim, x, y + 1, z) == null
                                    && !lw.blockEntity(x, y, z) && !lw.blockEntity(x, y + 1, z);
                        } catch (RuntimeException ex) {
                            return false;
                        }
                    }
                };
            } catch (RuntimeException ex) {
                LOG.warn("[entropybot] house {}: could not look at what stands (every step runs): {}", area, ex.toString());
            }
        }
        var plan = io.github.mojolowjo.entropybot.camp.HousePlan.plan(new io.github.mojolowjo.entropybot.camp.HousePlan.Facts(
                area, box, box != null, blk, inv, standing, cp, foreign, probe));
        if (plan.err() != null) return box == null && err[0] != null && !err[0].contains("covers every height") ? err[0] : plan.err();
        String text = String.join(" then ", plan.steps());
        LOG.info("[entropybot] house {}: {}", area, String.join(" > ", plan.steps()));
        if (splice) {
            String e = c.chainsRef().spliceNext(text);
            if (e == null && autoY != null) {
                String se = c.policySetHeights(area, autoY[0], autoY[1]);
                if (se != null) LOG.warn("[entropybot] house {}: could not save the heights: {}", area, se);
            }
            return e == null ? "ok: " + floorNote + plan.summary() + " - next in this chain" : "error: house: " + e;
        }
        String r = c.chainsRef().startChain(from, "house", text, 1);
        if (r.startsWith("started") && autoY != null) {
            String se = c.policySetHeights(area, autoY[0], autoY[1]);
            if (se != null) LOG.warn("[entropybot] house {}: could not save the heights: {}", area, se);
        }
        return r.startsWith("started") ? "started: " + floorNote + plan.summary() + " - each step reports; run house " + area + " again after a stop, it skips what stands" : r;
    }

    /** 0.30.4: per column (x-major, then z) the y of the top solid block that is not a snow layer or leaves; NONE when not loaded. */
    static int[] surfaceTops(ClientLevel lv, int x1, int z1, int x2, int z2) {
        int[] out = new int[(x2 - x1 + 1) * (z2 - z1 + 1)];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int i = 0;
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                int top = io.github.mojolowjo.entropybot.camp.HouseGround.NONE;
                m.set(x, lv.getMinBuildHeight(), z);
                if (lv.isLoaded(m)) {
                    int y = lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
                    for (; y >= lv.getMinBuildHeight(); y--) {
                        m.set(x, y, z);
                        BlockState st = lv.getBlockState(m);
                        var k = io.github.mojolowjo.entropybot.camp.HouseGround.surface(st.isAir(),
                                st.getBlock() instanceof net.minecraft.world.level.block.SnowLayerBlock, st.is(net.minecraft.tags.BlockTags.LEAVES),
                                !st.getFluidState().isEmpty(), st.getCollisionShape(lv, m).isEmpty());
                        if (k == io.github.mojolowjo.entropybot.camp.HouseGround.Surface.SKIP) continue;
                        top = k == io.github.mojolowjo.entropybot.camp.HouseGround.Surface.WATER ? io.github.mojolowjo.entropybot.camp.HouseGround.WATER : y;
                        break;
                    }
                }
                out[i++] = top;
            }
        }
        return out;
    }

    /** A block whose id contains the word inside the area box's walls (x1+1..x2-1, y1+1..y2-1, z1+1..z2-1). */
    static boolean insideHas(ClientLevel lv, int[] b, String word) {
        int x1 = Math.min(b[0], b[3]) + 1, x2 = Math.max(b[0], b[3]) - 1, y1 = Math.min(b[1], b[4]) + 1, y2 = Math.max(b[1], b[4]) - 1;
        int z1 = Math.min(b[2], b[5]) + 1, z2 = Math.max(b[2], b[5]) - 1;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) {
            m.set(x, y, z);
            // 0.30.2: Visual Workbench's table is visualworkbench:minecraft/crafting_table: match the id's last part
            if (lv.isLoaded(m) && io.github.mojolowjo.entropybot.camp.HousePlan.isStation(
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(lv.getBlockState(m).getBlock()).toString(), word)) return true;
        }
        return false;
    }

    /** A block whose id contains the word within BootstrapPlan.NEAR blocks (a few levels up and down). */
    static boolean near(ClientLevel lv, int[] c, String word) {
        int r = BootstrapPlan.NEAR;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dy = -3; dy <= 3; dy++) for (int dz = -r; dz <= r; dz++) {
            m.set(c[0] + dx, c[1] + dy, c[2] + dz);
            String id = lv.getBlockState(m).getBlock().getDescriptionId();
            if (id.endsWith("." + word) || id.endsWith("_" + word) && !id.contains("ender_chest")) return true;
        }
        return false;
    }

    /** 0.28.1: snow, a snow block or powder snow within 3 blocks of the camp's feet (that level and the one under it). */
    static boolean snowNear(ClientLevel lv, int[] f) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) for (int dy = -1; dy <= 0; dy++) {
            m.set(f[0] + dx, f[1] + dy, f[2] + dz);
            BlockState st = lv.getBlockState(m);
            if (st.is(net.minecraft.world.level.block.Blocks.SNOW) || st.is(net.minecraft.world.level.block.Blocks.SNOW_BLOCK)
                    || st.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) return true;
        }
        return false;
    }

    /** The cell can take a block: air (or grass), no fluid, a solid full block under it. */
    static boolean placeable(ClientLevel lv, BlockPos b) {
        BlockState st = lv.getBlockState(b);
        return st.canBeReplaced() && lv.getFluidState(b).isEmpty() && lv.getBlockState(b.below()).isCollisionShapeFullBlock(lv, b.below())
                && lv.getFluidState(b.below()).isEmpty();
    }

    /** The quarry's direction: the first of east, south, west, north whose 10 columns stand on dry, solid ground. */
    static int[] quarryDir(ClientLevel lv, int[] f) {
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        for (int[] d : dirs) {
            boolean ok = true;
            for (int i = 2; i <= BootstrapPlan.QUARRY_LEN + 1 && ok; i++) {
                BlockPos g = new BlockPos(f[0] + d[0] * i, f[1] - 1, f[2] + d[1] * i);
                BlockState st = lv.getBlockState(g);
                ok = !st.isAir() && lv.getFluidState(g).isEmpty() && lv.getFluidState(g.above()).isEmpty() && !st.hasBlockEntity();
            }
            if (ok) return d;
        }
        return dirs[0];
    }

    /** Free cells 2 away from the bot (feet level), on the side away from the quarry, nearest the bot's back first. */
    static List<int[]> freeRing(ClientLevel lv, int[] f, int[] dir) {
        // 0.24.3: rings 1-3 (a 3x3 pit has only ring 1), air above each, the quarry's side last (FreeSpots)
        return io.github.mojolowjo.entropybot.camp.FreeSpots.find(f, dir, c -> placeable(lv, new BlockPos(c[0], c[1], c[2])),
                c -> lv.getBlockState(new BlockPos(c[0], c[1] + 1, c[2])).canBeReplaced());
    }

    /** 0.24.3: leaves or a plant (not a block entity, not a built block) on solid ground: clearing it makes a free spot. */
    static boolean clearable(ClientLevel lv, int[] c) {
        BlockPos b = new BlockPos(c[0], c[1], c[2]);
        BlockState st = lv.getBlockState(b);
        if (st.isAir() || st.hasBlockEntity() || !lv.getFluidState(b).isEmpty()) return false;
        String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
        boolean soft = id.endsWith("_leaves") || id.contains("bush") || id.contains("fern") || id.contains("grass") && !id.equals("grass_block")
                || id.contains("flower") || id.contains("sapling") || id.contains("vine") || st.canBeReplaced();
        return soft && lv.getBlockState(b.below()).isCollisionShapeFullBlock(lv, b.below())
                && lv.getBlockState(b.above()).getCollisionShape(lv, b.above()).isEmpty() | lv.getBlockState(b.above()).getBlock().getDescriptionId().endsWith("_leaves");
    }

    /** 0.24.3: the nearest spot within 16 blocks with 3 free cells around it (a walkable cell on solid ground), or null. */
    static int[] flatSpotNear(ClientLevel lv, int[] f, int[] dir) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -16; dx <= 16; dx += 2) for (int dz = -16; dz <= 16; dz += 2) for (int dy = -3; dy <= 3; dy++) {
            int[] c = {f[0] + dx, f[1] + dy, f[2] + dz};
            BlockPos b = new BlockPos(c[0], c[1], c[2]);
            if (!placeable(lv, b) || !lv.getBlockState(b.above()).canBeReplaced()) continue;
            double d = dx * dx + dz * dz + dy * dy;
            if (d >= bestD || freeRing(lv, c, dir).size() < 3) continue;
            best = c;
            bestD = d;
        }
        return best;
    }

    // ---------------------------------------------------------------- light

    Chains.Reply light(LocalPlayer p, String rest, String from, boolean internal, String raw, JobRequests.Listener l) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        int[] me = Jobs.here(p);
        int[] box = LightGrid.parse(rest, new int[]{me[0], me[2]});
        if (box == null) return Chains.Reply.now(LightGrid.USAGE);
        String dim = Guard.dimOf(lv);
        List<int[]> spots = new ArrayList<>();
        int water = 0, air = 0, outside = 0, lit = 0;
        for (int[] s : LightGrid.spots(box[0], box[1], box[2], box[3])) {
            if (c.fenceOn() && !c.inAreas(dim, s[0], s[1])) {
                outside++;
                continue;
            }
            int[] g = ground(lv, s[0], me[1], s[1]);
            if (g == null) {
                air++;
                continue;
            }
            if (g[1] == Integer.MIN_VALUE) {
                water++;
                continue;
            }
            if (lv.getBlockState(new BlockPos(g[0], g[1], g[2])).getBlock().getDescriptionId().contains("torch")) {
                lit++;
                continue;
            }
            spots.add(g);
        }
        List<String> why = new ArrayList<>();
        if (water > 0) why.add(water + " water");
        if (air > 0) why.add(air + " no ground");
        if (outside > 0) why.add(outside + " outside my areas");
        if (lit > 0) why.add(lit + " lit already");
        int skipped = water + air + outside + lit;
        if (spots.isEmpty()) return Chains.Reply.now("ok: " + LightGrid.report(0, skipped, String.join(", ", why)));
        Map<String, Integer> bag = Gui.inventory(p);
        int have = bag.getOrDefault("minecraft:torch", 0);
        // 0.23.1: short of torches (in a chain too: camp here): fetch them, else craft them from coal/charcoal, else from logs
        // (charcoal at a furnace near me, the craft planner smelts it), else light with what I have and say what's missing
        List<Seq.Step> before = new ArrayList<>();
        String supplyNote = null;
        if (have < spots.size()) {
            if (c.jobs.running() && !c.jobs.walking()) return Chains.Reply.now("busy: " + c.jobs.job.status + " (pm \"stop\" first)");
            int stored = 0, fuel = bag.getOrDefault("minecraft:coal", 0) + bag.getOrDefault("minecraft:charcoal", 0), logs = 0;
            for (Map.Entry<String, Integer> e : bag.entrySet()) if (Chopping.isLogItem(e.getKey())) logs += e.getValue();
            List<Crafting.Source> src = c.crafting.storageSources(p);
            for (Crafting.Source s : src) {
                stored += s.items().getOrDefault("minecraft:torch", 0);
                fuel += s.items().getOrDefault("minecraft:coal", 0) + s.items().getOrDefault("minecraft:charcoal", 0);
                for (Map.Entry<String, Integer> e : s.items().entrySet()) if (Chopping.isLogItem(e.getKey())) logs += e.getValue();
            }
            LightGrid.Supply sup = LightGrid.supply(have, spots.size(), stored, fuel, logs, near(lv, me, "furnace"));
            LOG.info("[entropybot] light: {} torches for {} spots: {} ({} stored, {} coal/charcoal, {} logs)", have, spots.size(), sup.source(), stored, fuel, logs);
            switch (sup.source()) {
                case FETCH -> {
                    Map<String, Integer> need = new LinkedHashMap<>();
                    need.put("minecraft:torch", sup.count());
                    before.addAll(Crafting.takeTrips(src, need).steps());
                }
                case CRAFT -> {
                    Crafting.Prepared pr = c.crafting.prepareCraft(p, "minecraft:torch " + sup.count(), null);
                    if (pr.err() != null) {
                        supplyNote = "couldn't make torches (" + pr.err().replaceFirst("^error: ", "") + ")";
                    } else {
                        for (Seq.Step a : pr.steps()) a.direct = false;
                        before.addAll(pr.steps());
                        have += sup.count();
                    }
                }
                case PARTIAL -> supplyNote = sup.text();
                case NONE -> { return Chains.Reply.now(sup.text()); }
                default -> { }
            }
            if (sup.source() == LightGrid.Source.FETCH) have += sup.count();
        }
        if (have < spots.size()) {
            why.add((spots.size() - have) + " no torches left" + (supplyNote != null ? ": " + supplyNote : ""));
            skipped += spots.size() - have;
            spots = spots.subList(0, have);
        }
        if (spots.isEmpty()) return Chains.Reply.now(Hints.next("error: I have no torches" + (supplyNote != null ? " - " + supplyNote : ""), "craft torch 16 (coal or charcoal + sticks), then light " + rest.trim()));
        if (c.jobs.running() && !c.jobs.walking()) return Chains.Reply.now("busy: " + c.jobs.job.status + " (pm \"stop\" first)");
        c.jobs.replaceWalk();
        // nearest first, then each next one nearest the last (a short walk)
        List<int[]> order = new ArrayList<>();
        int[] at = me;
        List<int[]> left = new ArrayList<>(spots);
        while (!left.isEmpty()) {
            int bi = 0;
            for (int i = 1; i < left.size(); i++) if (Jobs.distSq(left.get(i), at) < Jobs.distSq(left.get(bi), at)) bi = i;
            at = left.remove(bi);
            order.add(at);
        }
        List<Seq.Step> steps = new ArrayList<>(before);
        for (int[] s : order) steps.add(Clearing.placeStep(s, "minecraft:torch", null, true));
        Seq.Step note = new Seq.Step("lightnote");
        note.items = new LinkedHashMap<>();
        note.n = skipped;
        note.why = String.join(", ", why);
        note.text = encode(order);
        steps.add(note);
        String r = c.jobs.startSeq(new Seq(c.jobs, c.storage, "lighting " + order.size() + " spots", steps, "fail"), "fail");
        return new Chains.Reply(r, c.jobs.attach("pm", from, raw, r, l));
    }

    /** The cell above the ground at x z near y (the top solid block in y+6 .. y-10 with air above); null: none; y MIN_VALUE: water. */
    static int[] ground(ClientLevel lv, int x, int y, int z) {
        for (int yy = y + 6; yy >= y - 10; yy--) {
            BlockPos b = new BlockPos(x, yy, z);
            if (!lv.getFluidState(b).isEmpty()) return new int[]{x, Integer.MIN_VALUE, z};
            BlockState below = lv.getBlockState(b.below());
            if (lv.getBlockState(b).getBlock().getDescriptionId().contains("torch")) return new int[]{x, yy, z};     // lit already (the caller says so)
            // 0.23.1: the cell above a torch is air over a non-full block: that spot is lit, not "no ground" (live: 22 of 25)
            if (below.getBlock().getDescriptionId().contains("torch")) return new int[]{x, yy - 1, z};
            if (lv.getBlockState(b).canBeReplaced() && !below.isAir()) {
                if (!lv.getFluidState(b.below()).isEmpty()) return new int[]{x, Integer.MIN_VALUE, z};
                if (below.isCollisionShapeFullBlock(lv, b.below())) return new int[]{x, yy, z};
                return null;
            }
        }
        return null;
    }

    static String encode(List<int[]> ps) {
        StringBuilder sb = new StringBuilder();
        for (int[] q : ps) sb.append(q[0]).append(',').append(q[1]).append(',').append(q[2]).append(';');
        return sb.toString();
    }

    /** The light job's last step: counts the torches that stand at the spots now, the end line in the note. */
    static String lightNote(Seq s, Seq.Step st) {
        ClientLevel lv = Minecraft.getInstance().level;
        int placed = 0, missed = 0;
        for (String q : st.text.split(";")) {
            if (q.isEmpty()) continue;
            String[] v = q.split(",");
            BlockPos b = new BlockPos(Integer.parseInt(v[0]), Integer.parseInt(v[1]), Integer.parseInt(v[2]));
            if (lv.getBlockState(b).getBlock().getDescriptionId().contains("torch")) placed++;
            else missed++;
        }
        String why = st.why == null ? "" : st.why;
        if (missed > 0) why = (why.isEmpty() ? "" : why + ", ") + missed + " couldn't be placed (nowhere to stand within reach, or the guard refused)";
        s.note = LightGrid.report(placed, (st.n == null ? 0 : st.n) + missed, why);
        return "next";
    }

    // ---------------------------------------------------------------- stock

    Map<String, Integer> stockWant() {
        Map<String, Integer> out = new LinkedHashMap<>();
        JsonObject b = brain();
        if (b.has("stockTargets") && b.get("stockTargets").isJsonObject()) for (Map.Entry<String, JsonElement> e : b.getAsJsonObject("stockTargets").entrySet()) {
            try { out.put(e.getKey(), e.getValue().getAsInt()); } catch (RuntimeException ignored) {}
        }
        return out;
    }

    Map<String, Integer> baseHave(LocalPlayer p) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (StorageRules.Chest ch : c.storage.baseChests(p, true)) ch.items().forEach((k, v) -> out.merge(k, v, Integer::sum));
        return out;
    }

    String stock(LocalPlayer p, String rest) {
        String t = rest == null ? "" : rest.trim();
        List<String> w = Texts.words(t.toLowerCase());
        Map<String, Integer> want = stockWant();
        if (!w.isEmpty() && w.get(0).equals("set")) {
            if (w.size() != 3 || !w.get(2).matches("^\\d{1,5}$")) return StockRules.USAGE;
            String id = c.crafting.planner.resolveItem(w.get(1), Gui.inventory(p));
            if (id == null) id = JunkRules.full(w.get(1));
            if (!Storage.itemExists(id)) return "error: I don't know an item called " + w.get(1) + " - next: find or recipe the exact id";
            if (!want.containsKey(id) && want.size() >= StockRules.MAX_ITEMS) return "error: I keep at most " + StockRules.MAX_ITEMS + " stock targets - stock clear <item> first";
            int n = Integer.parseInt(w.get(2));
            if (n == 0) want.remove(id);
            else want.put(id, n);
        } else if (!w.isEmpty() && w.get(0).equals("clear")) {
            if (w.size() != 2) return StockRules.USAGE;
            if (w.get(1).equals("all")) want.clear();
            else if (want.remove(JunkRules.full(w.get(1))) == null) return "no stock target for " + w.get(1);
        } else if (!w.isEmpty() && !w.get(0).equals("targets")) {
            return StockRules.USAGE;
        }
        if (!w.isEmpty() && !w.get(0).equals("targets")) {
            JsonObject o = new JsonObject();
            want.forEach(o::addProperty);
            brain().add("stockTargets", o);
            c.saved();
        }
        return StockRules.text(want, baseHave(p));
    }

    String restockBase(LocalPlayer p, String from) {
        Map<String, Integer> want = stockWant();
        if (want.isEmpty()) return "error: no stock targets - next: stock set torch 64";
        if (c.storage.baseChests(p, true).isEmpty()) return Hints.next("error: I know no base chests", "scan base");
        Map<String, Integer> sh = StockRules.shortOf(want, baseHave(p));
        if (sh.isEmpty()) return "ok: the base chests hold all of the stock (" + StockRules.text(want, baseHave(p)).replaceFirst("^stock[^:]*: ", "") + ")";
        if (c.chainsRef().running()) return "busy: " + c.chainsRef().chainStatus() + " (pm \"stop\" first)";
        return c.chainsRef().startChain(from, "restock base", StockRules.chain(sh), 1);
    }

    // ---------------------------------------------------------------- junk

    Set<String> junkList() {
        JsonObject b = brain();
        if (!b.has("junk") || !b.get("junk").isJsonArray()) return new LinkedHashSet<>(JunkRules.DEFAULT);
        Set<String> out = new LinkedHashSet<>();
        for (JsonElement e : b.getAsJsonArray("junk")) out.add(e.getAsString());
        return out;
    }

    String junkMode() {
        JsonObject b = brain();
        return b.has("junkMode") ? b.get("junkMode").getAsString() : "drop";
    }

    private void saveJunk(Set<String> s) {
        JsonArray a = new JsonArray();
        s.forEach(a::add);
        brain().add("junk", a);
        c.saved();
    }

    String junk(String rest) {
        List<String> w = Texts.words(rest == null ? "" : rest.toLowerCase());
        if (w.isEmpty() || w.get(0).equals("list")) return JunkRules.listText(junkList(), junkMode());
        String op = w.get(0);
        List<String> ids = w.subList(1, w.size());
        switch (op) {
            case "add" -> {
                if (ids.isEmpty()) return JunkRules.USAGE;
                List<String> refused = new ArrayList<>();
                saveJunk(JunkRules.add(junkList(), ids, refused));
                return (refused.isEmpty() ? "ok: " : "ok (never junk, left out: " + String.join(", ", refused) + "): ") + JunkRules.listText(junkList(), junkMode());
            }
            case "remove" -> {
                if (ids.isEmpty()) return JunkRules.USAGE;
                saveJunk(JunkRules.remove(junkList(), ids));
                return "ok: " + JunkRules.listText(junkList(), junkMode());
            }
            case "default" -> {
                saveJunk(new LinkedHashSet<>(JunkRules.DEFAULT));
                return "ok: " + JunkRules.listText(junkList(), junkMode());
            }
            case "mode" -> {
                if (ids.size() != 1 || !ids.get(0).matches("^(drop|chest)$")) return JunkRules.USAGE;
                brain().addProperty("junkMode", ids.get(0));
                c.saved();
                String warn = ids.get(0).equals("chest") && !Core.INSTANCE.knowledge.places().containsKey("junk")
                        ? " - no chest is marked junk yet: next: mark junk (standing at it), then scan base" : "";
                return "ok: " + JunkRules.listText(junkList(), junkMode()) + warn;
            }
            default -> { return JunkRules.USAGE; }
        }
    }

    /** Every 40 ticks: a job (not a walk) running with the bag nearly full: drop the junk (mode drop). */
    void junkTick(LocalPlayer p) {
        if (!c.jobs.running() || c.jobs.walking() || Core.INSTANCE.reflexes.hold()) return;
        if (!"drop".equals(junkMode()) || Gui.open(p) || Minecraft.getInstance().screen != null) return;
        if (c.freeSlots() > JunkRules.LOW) return;
        // never litter at the owner's feet: with them within 8 blocks the junk waits (the deposit takes it)
        net.minecraft.world.entity.player.Player owner = Jobs.findPlayer(c.owner());
        if (owner != null && owner != p && owner.distanceTo(p) < 8) return;
        Jobs.Job j = c.jobs.job;
        Map<String, Integer> plan = junkPlan(p, j.label + " " + j.status);
        if (plan.isEmpty()) return;
        int n = 0;
        for (Map.Entry<String, Integer> e : plan.entrySet()) {
            String r = io.github.mojolowjo.entropybot.gui.GuiCore.drop(new McMenu(p), e.getKey() + " " + e.getValue());
            if (r.startsWith("ok: dropped")) n += e.getValue();
            else LOG.warn("[entropybot] junk drop {}: {}", e.getKey(), r);
        }
        j.junkDropped += n;
        LOG.info("[entropybot] junk: bag nearly full mid-job, dropped {} ({})", n, plan);
    }


    Map<String, Integer> junkPlan(LocalPlayer p, String collecting) {
        List<StorageRules.Held> held = Storage.held(p);
        Map<String, Integer> dep = StorageRules.depositables(held, null, true, null, c.storage.keeps());
        // 0.28.1: a running or queued build/shelter keeps its blocks (the trial's house cobblestone went over the side)
        List<String> texts = new ArrayList<>();
        texts.add(collecting);
        if (c.jobs.job != null && !c.jobs.job.done) texts.add(c.jobs.job.type + " " + c.jobs.job.label);
        texts.addAll(c.chainsRef().restSteps());
        return JunkRules.plan(junkList(), dep, Gui.inventory(p), collecting, JunkRules.wanted(texts));
    }

    /** Junk mode chest: the junk items of a deposit go to the chest marked "junk" (its pos), or null. */
    int[] junkChest() {
        if (!"chest".equals(junkMode())) return null;
        JsonObject pl = Core.INSTANCE.knowledge.places().get("junk");
        return pl == null ? null : Jobs.pos(pl);
    }

    // ---------------------------------------------------------------- tool care

    List<ToolCareRules.Tool> tools(LocalPlayer p) {
        List<ToolCareRules.Tool> out = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (!st.isEmpty() && st.isDamageableItem() && st.getMaxDamage() > 0) {
                out.add(new ToolCareRules.Tool(Gui.itemId(st), st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
            }
        }
        return out;
    }

    String toolsText(LocalPlayer p) {
        return p == null ? "" : ToolCareRules.text(tools(p)) + "\n" + ArmorCareRules.text(armor(p))
                + "\n" + ArmorCareRules.orderText(ironFirst());
    }

    /** 0.25.1 gear.ironFirst (a brain setting; defaults until the brain loaded them). */
    static boolean ironFirst() {
        return true;      // AA_CLEANUP 3: was BrainConfig gear.ironFirst (default 1)
    }

    /** Every 100 ticks; at a pause (no job, chain or request, no reflex) a worn or broken tool is replaced. */
    void toolCareTick(LocalPlayer p, boolean idle) {
        List<ToolCareRules.Tool> tl = tools(p);
        Map<String, String> needs = ToolCareRules.needs(tl, toolsBefore);
        Map<String, String> now = ToolCareRules.carried(tl);
        // a broken kind stays "before" until replaced (so the next pause still sees it)
        for (Map.Entry<String, String> e : needs.entrySet()) now.putIfAbsent(e.getKey(), e.getValue());
        careStuck.keySet().removeIf(k -> !k.startsWith("armor:") && !needs.containsKey(k));
        if (!idle || needs.isEmpty()) {
            toolsBefore = now;
            return;
        }
        long ms = System.currentTimeMillis();
        for (Map.Entry<String, String> e : needs.entrySet()) {
            String kind = e.getKey();
            if (ms - careTried.getOrDefault(kind, 0L) < CARE_RETRY_MS) continue;
            careTried.put(kind, ms);
            Set<String> stored = new LinkedHashSet<>();
            Map<String, Integer> have = new LinkedHashMap<>(Gui.inventory(p));
            for (Crafting.Source s : c.crafting.storageSources(p)) {
                stored.addAll(s.items().keySet());
                s.items().forEach((k, v) -> have.merge(k, v, Integer::sum));
            }
            String cmd = ToolCareRules.replacement(kind, e.getValue(), stored, have);
            if (cmd == null) {
                careStuck.put(kind, e.getValue());
                LOG.info("[entropybot] tool care: my {} is worn or broken and nothing can replace it", e.getValue());
                toolsBefore = now;
                return;
            }
            careStuck.remove(kind);
            LOG.info("[entropybot] tool care: {} worn or broken -> {}", e.getValue(), cmd);
            Chains.Reply r = c.handle(c.owner(), cmd, false, true, null);
            LOG.info("[entropybot] tool care: {} -> {}", cmd, r.text());
            if (r.text() != null && r.text().startsWith("started")) {
                now.remove(kind);           // the new one shows up when the job ends
                toolsBefore = now;
                return;                     // one at a time
            }
        }
        toolsBefore = now;
    }

    // ---------------------------------------------------------------- armour care (0.23.6)

    /** The four worn slots (empty ones as a Piece with id null). */
    static List<ArmorCareRules.Piece> armor(LocalPlayer p) {
        List<ArmorCareRules.Piece> out = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String slot = ArmorCareRules.SLOTS.get(3 - i);          // armor list: 0 boots .. 3 helmet
            ItemStack st = p.getInventory().armor.get(i);
            out.add(st.isEmpty() ? new ArmorCareRules.Piece(slot, null, 0, 0)
                    : new ArmorCareRules.Piece(slot, Gui.itemId(st), st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
        }
        return out;
    }

    /** Armour pieces in the bag (not worn). */
    static List<ArmorCareRules.Piece> armorInBag(LocalPlayer p) {
        List<ArmorCareRules.Piece> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack st = p.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String id = Gui.itemId(st);
            String slot = ArmorCareRules.slot(id);
            if (slot != null) out.add(new ArmorCareRules.Piece(slot, id, st.getMaxDamage() - st.getDamageValue(), st.getMaxDamage()));
        }
        return out;
    }

    private Set<String> armorBefore = new LinkedHashSet<>();

    /**
     * At a pause, after tool care: a worn or missing piece is fetched or crafted and worn ("... then wear"); a good spare
     * in the bag is just put on. One at a time, each slot retried every 10 minutes. True when it started something.
     */
    private long armorBusyLog;

    /** A crafting table within r blocks (6 up and down) of the player. */
    static boolean tableWithin(LocalPlayer p, int r) {
        var lv = Minecraft.getInstance().level;
        if (lv == null) return false;
        net.minecraft.core.BlockPos c = p.blockPosition();
        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -6; dy <= 6; dy++) {
            m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
            if (lv.isLoaded(m) && lv.getBlockState(m).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    boolean armorCareTick(LocalPlayer p, String busy) {
        List<ArmorCareRules.Piece> on = armor(p), bag = armorInBag(p);
        Set<String> filled = new LinkedHashSet<>();
        for (ArmorCareRules.Piece a : on) if (!a.empty()) filled.add(a.slot());
        Set<String> before = armorBefore;
        armorBefore = filled;
        if (busy != null) {
            if (ArmorCareRules.lowest(on) != null && System.currentTimeMillis() - armorBusyLog > 60_000) {
                armorBusyLog = System.currentTimeMillis();
                LOG.info("[entropybot] armour care: {} - waiting for a pause (busy: {})", ArmorCareRules.lowest(on), busy);
            }
            return false;
        }
        // a good spare for a worn or empty slot: wear swaps it in
        for (ArmorCareRules.Piece a : on) {
            if ((a.empty() || a.worn()) && ArmorCareRules.spare(a.slot(), bag) != null && Minecraft.getInstance().screen == null) {
                String r = Gui.wearArmor(p);
                LOG.info("[entropybot] armour care: wear -> {}", r);
                if (r.startsWith("ok")) return true;
            }
        }
        boolean ironFirst = ironFirst();
        Map<String, String> needs = ArmorCareRules.ordered(ArmorCareRules.needs(on, bag), ironFirst);      // 0.25.1
        careStuck.keySet().removeIf(k -> k.startsWith("armor:") && !needs.containsKey(k.substring(6)));
        long ms = System.currentTimeMillis();
        Set<String> stored = null;
        Map<String, Integer> have = null;
        for (Map.Entry<String, String> e : needs.entrySet()) {
            String slot = e.getKey();
            if (ms - careTried.getOrDefault("armor:" + slot, 0L) < CARE_RETRY_MS) continue;
            careTried.put("armor:" + slot, ms);
            if (stored == null) {
                stored = new LinkedHashSet<>();
                have = new LinkedHashMap<>(Gui.inventory(p));
                for (Crafting.Source s : c.crafting.storageSources(p)) {
                    stored.addAll(s.items().keySet());
                    final Map<String, Integer> h = have;
                    s.items().forEach((k, v) -> h.merge(k, v, Integer::sum));
                }
            }
            String cmd = ArmorCareRules.replacement(slot, e.getValue(), stored, ArmorCareRules.spendable(slot, on, have, ironFirst));
            if (cmd == null) {
                // a worn piece, or one that broke since the last look: a check line; a slot never filled stays quiet
                if (e.getValue() != null || before.contains(slot)) careStuck.put("armor:" + slot, e.getValue() == null ? slot : e.getValue());
                continue;
            }
            careStuck.remove("armor:" + slot);
            String pre = ArmorCareRules.withTablePrereq(cmd, tableWithin(p, 12), Gui.inventory(p));
            if (!pre.equals(cmd)) LOG.info("[entropybot] armour care: prerequisite for {}: no crafting table within 12, none in the bag, no wood for one -> cut 4 logs first (~30 s), the craft makes the table", cmd);
            cmd = pre;
            LOG.info("[entropybot] armour care: {} {} -> {}", slot, e.getValue() == null ? "missing" : e.getValue() + " worn", cmd);
            Chains.Reply r = c.handle(c.owner(), cmd, false, true, null);
            LOG.info("[entropybot] armour care: {} -> {}", cmd, r.text());
            if (r.text() == null || !r.text().startsWith("error")) return true;      // one at a time
        }
        return false;
    }

    /** "check": tools that are nearly broken and that nothing can replace. */
    List<String> careFindings() {
        List<String> out = new ArrayList<>();
        careStuck.forEach((k, id) -> out.add(StockRules.shortId(id)));
        return out;
    }
}
