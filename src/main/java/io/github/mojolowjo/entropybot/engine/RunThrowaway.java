package io.github.mojolowjo.entropybot.engine;

import baritone.api.BaritoneAPI;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.39.z38 (rule 2: a process owns its throwaway list for its run): the mod's one door to Baritone's
 * {@code acceptableThrowawayItems} - the fork's {@code IBaritone.getRunThrowaway()} lease. The mod never writes the
 * setting. {@link #reserve} (0.39.z61, was z38's keep list): the run's counted reserves - a reserved item is placed only
 * while the bag holds more than the reserves need;
 * {@link #add} / {@link #end}: a danger leg's or pillar's extra blocks for its run.
 *
 * <p>Loader notes: Baritone API only; {@code getRunThrowaway()} is missing from the stock jar (LinkageError): logged
 * once per world with what is lost (hooks wired and loud), never silent. Client thread only. Never throws.
 */
public final class RunThrowaway {
    private static final Logger LOG = LogUtils.getLogger();
    private static Object warnedWorld;

    private RunThrowaway() {}

    private static baritone.api.process.IRunThrowaway lease(String what) {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone().getRunThrowaway();
        } catch (LinkageError e) {
            Object w = Minecraft.getInstance().level;
            if (w != warnedWorld) {
                warnedWorld = w;
                LOG.warn("[entropybot] run throwaway: this Baritone has no getRunThrowaway (the stock jar? install the entropy fork) - {} not done this world: {}", what, e.toString());
            }
            return null;
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] run throwaway: {} failed: {}", what, e.toString());
            return null;
        }
    }

    /** An id from the game's registry; null (said once per call site's caller) when the game has no such item. */
    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
        return it == Items.AIR ? null : it;
    }

    /**
     * 0.39.z61 (design D1 J2, replaces z38's keep list; run P z56 23:48:55: every stone member kept whole with 103 cobblestone
     * in the bag, no pillar priced): the run's counted reserves to the fork's lease ({@code IRunThrowaway.reserve}, which
     * replaces the whole table). Ids become the game's items; a member the registry does not know is left out and said; a
     * reserve with no member left is said and dropped (it could hold nothing). Empty: nothing reserved.
     */
    public static void reserve(List<baritone.api.process.Reserves.Reserve<String>> reserves) {
        List<baritone.api.process.Reserves.Reserve<Item>> out = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (var r : reserves) {
            List<Item> members = new ArrayList<>();
            java.util.Map<Item, Integer> weight = new java.util.LinkedHashMap<>();
            for (String id : r.members) {
                Item it = item(id);
                if (it == null) { missing.add(id); continue; }
                members.add(it);
                if (r.weightOf(id) != 1) weight.put(it, r.weightOf(id));
            }
            if (members.isEmpty()) { LOG.warn("[entropybot] run throwaway: reserve {}: none of its items is in this game's registry (not reserved)", r); continue; }
            out.add(new baritone.api.process.Reserves.Reserve<>(r.word, members, weight, r.units, r.kind, r.step, r.owner));
        }
        if (!missing.isEmpty()) LOG.warn("[entropybot] run throwaway: reserve: no such item in this game's registry (left out): {}", missing);
        var l = lease("reserve the run's items " + reserves.size());
        if (l == null) return;
        l.reserve(out);
        // 0.39.z55 item 1 / z61 (D1 3.7): the table at every step start and what the fork's lease can spare, behind debug.walkProbe
        if (io.github.mojolowjo.entropybot.commands.WalkProbe.on()) {
            var p = Minecraft.getInstance().player;
            LOG.info("[entropybot] probe reserve: reserved: {} | the fork's lease spare: {}", table(reserves), p == null ? "unknown (not in a world)" : spareInBag(l, p));
        }
    }

    /** The reserves as the lines write them ("stick 2 (craft stone_pickaxe), ..."; "nothing"). */
    private static String table(List<baritone.api.process.Reserves.Reserve<String>> reserves) {
        List<String> p = new ArrayList<>();
        for (var r : reserves) p.add(io.github.mojolowjo.entropybot.aa.moves.Reservations.forText(r));   // D1 ledger: "stick 2 for craft stone_pickaxe 1"
        return p.isEmpty() ? "nothing" : String.join(", ", p);
    }

    /** The bag's throwaway stacks with their spare now ("cobblestone 92, dirt 8"; "none"). */
    private static String spareInBag(baritone.api.process.IRunThrowaway l, net.minecraft.world.entity.player.Player p) {
        java.util.Map<Item, Integer> seen = new java.util.LinkedHashMap<>();
        var list = BaritoneAPI.getSettings().acceptableThrowawayItems.value;
        for (var st : p.getInventory().items) if (!st.isEmpty() && list.contains(st.getItem())) seen.putIfAbsent(st.getItem(), 0);
        StringBuilder b = new StringBuilder();
        for (Item it : seen.keySet()) {
            int s = l.spare(it);
            if (s > 0) b.append(b.length() == 0 ? "" : ", ").append(BuiltInRegistries.ITEM.getKey(it).getPath()).append(' ').append(s);
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    /**
     * 0.39.z61 (D1 3.7, aa why / aa status): "92 (cobblestone 92)" - how many throwaway blocks the bag may place in all and
     * the stacks they come from; the reason when the lease cannot say.
     */
    public static String spareLine(net.minecraft.world.entity.player.Player p) {
        var l = lease("read the spare throwaway");
        if (l == null) return "unknown (no lease in this Baritone)";
        try {
            return l.spareThrowaway() + " (" + spareInBag(l, p) + ")";
        } catch (RuntimeException e) {
            return "unknown (" + e + ")";
        }
    }

    /**
     * 0.39.z55 item 1 (probe): an item the bot just placed, as the lease sees it. 0.39.z61 (D1 3.7): "throwaway (spare 91 after
     * it)"; "RESERVED (spare 0: a lease failure)" when a reserve names it and the bag can no longer meet the reserves; "not
     * throwaway" (a block a build or a station placed). Read only; never writes a setting.
     */
    public static String placedAs(Item item) {
        var l = lease("read the reserves");
        if (l == null) return "unknown (no lease in this Baritone)";
        try {
            var reserves = l.reserves();
            var p = Minecraft.getInstance().player;
            if (p != null && baritone.api.process.Reserves.reserved(reserves, item)) {
                java.util.Map<Item, Integer> bag = new java.util.HashMap<>();
                for (var st : p.getInventory().items) if (!st.isEmpty()) bag.merge(st.getItem(), st.getCount(), Integer::sum);
                if (!baritone.api.process.Reserves.feasible(reserves, bag)) return "RESERVED (spare 0: a lease failure)";
            }
            return BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(item) ? "throwaway (spare " + l.spare(item) + " after it)" : "not throwaway (a build's or a station's block)";
        } catch (RuntimeException e) {
            return "unknown (" + e + ")";
        }
    }

    /**
     * 0.39.z56 item 1 (the step probe, run O: only dirt left as throwaway under ground): the bag's stacks Baritone may place
     * now - on its throwaway list, with their spare (z61: counts, not keep lists) - as "dirt 8, ..."; "none" when there is
     * none. Read only.
     */
    public static String throwawayInBag(net.minecraft.world.entity.player.Player p) {
        var l = lease("read the reserves");
        if (l == null) return "none (no lease in this Baritone: reserves unknown)";
        return spareInBag(l, p);
    }

    /** A run's extra throwaway items (the fork's lease; put back at {@link #end}). */
    public static void add(Object run, List<Item> items) {
        var l = lease("extra throwaway " + items.size() + " items");
        if (l != null) l.add(run, items);
    }

    /**
     * 0.39.z39 (owner 2026-10-23: placing on a walk is a price, not a ban; rule 1): Tuning {@code walk.placePenalty} to
     * the fork's walk lease, which holds it as Baritone's {@code blockPlacementPenalty} for each walk's run only and puts
     * the setting back at the walk's end. The mod never writes the setting; chop / strip / build / burrow / dig-out runs
     * keep their own.
     */
    public static void walkPenalty() {
        var l = lease("walk placement penalty");
        if (l != null) l.walkPenalty(io.github.mojolowjo.entropybot.aa.brain.Tuning.get("walk.placePenalty"));
    }

    /**
     * 0.39.z76 send-back 3 (rule 22: "no placing or running away inside an explore"): the explore's walks price a placement
     * at Baritone's own infinite cost (ActionCosts.COST_INF) through the same walk lease, so its searches never place a block
     * (a target only reachable by placing is no path, the explore's own failure). The next step's start puts
     * walk.placePenalty back ({@link #walkPenalty}).
     */
    public static void noPlacing() {
        var l = lease("explore: no placing");
        if (l != null) l.walkPenalty(baritone.api.pathing.movement.ActionCosts.COST_INF);
    }

    public static void end(Object run) {
        var l = lease("end of a throwaway run");
        if (l != null) l.end(run);
    }

    /**
     * The bag's blocks the bot may place as filler (AaEscape.pillarBlock: Junk.placeable from the item data - a cheap full
     * block, not a kept kit item; planks included), as items. The danger ways' pillar and the standing bag lease use it.
     */
    public static List<Item> bagBlocks(net.minecraft.world.entity.player.Player p) {
        List<Item> out = new ArrayList<>();
        if (p == null) return out;
        for (int i = 0; i < 36; i++) {  // game fact: the player inventory has 36 slots
            var s = p.getInventory().getItem(i);
            if (s.isEmpty() || !(s.getItem() instanceof net.minecraft.world.item.BlockItem)) continue;
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (io.github.mojolowjo.entropybot.aa.AaEscape.pillarBlock(id) && !out.contains(s.getItem())) out.add(s.getItem());
        }
        return out;
    }

    private static final Object BAG_RUN = new Object();
    private static List<Item> bagHeld = List.of();

    /**
     * Run HV (2026-11-02, the HS world: the bot 100 blocks under ground with 90 cobbled deepslate, granite, diorite and
     * andesite, the only way up a one-wide shaft; "spare throwaway in bag none" - Baritone's list is dirt, cobblestone,
     * netherrack, stone - so no pillar was priced and every search from the pocket found no path): the bag's placeable
     * junk blocks ({@link #bagBlocks}, the item data) are held as throwaway for EVERY walk through one standing lease,
     * refreshed when the bag's set changes (the reserves still keep what a craft needs: the lease's spare). Client thread.
     */
    public static void holdBag(net.minecraft.world.entity.player.Player p) {
        List<Item> now = bagBlocks(p);
        if (now.equals(bagHeld)) return;
        var l = lease("the bag's throwaway blocks");
        if (l == null) return;
        if (!bagHeld.isEmpty()) l.end(BAG_RUN);
        bagHeld = now;
        if (now.isEmpty()) return;
        l.add(BAG_RUN, now);
        List<String> names = new ArrayList<>();
        for (Item it : now) names.add(BuiltInRegistries.ITEM.getKey(it).getPath());
        LOG.info("[entropybot] run throwaway: the bag's placeable blocks are throwaway for every walk now: {}", String.join(", ", names));
    }
}
