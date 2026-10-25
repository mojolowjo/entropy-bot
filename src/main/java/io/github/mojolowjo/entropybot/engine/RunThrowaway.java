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
 * setting. {@link #keep}: the job's pending ingredients (Steps' keep list) are never throwaway while a run holds;
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

    /** The job's pending items (ids) out of the throwaway list for every run; empty: none kept. */
    public static void keep(List<String> ids) {
        List<Item> items = new ArrayList<>();
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Item it = rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
            if (it == Items.AIR) LOG.warn("[entropybot] run throwaway: keep {}: no such item in this game's registry (not kept)", id);
            else items.add(it);
        }
        var l = lease("keep the job's items " + ids);
        if (l == null) return;
        l.keep(items);
        if (!items.isEmpty()) LOG.debug("[entropybot] run throwaway: kept out of the throwaway list {}", ids);
        // 0.39.z55 item 1 (run N: the furnace's cobblestone was placed on the walk to the table): the keep list at every
        // step start and what the fork's lease holds, behind debug.walkProbe
        if (io.github.mojolowjo.entropybot.commands.WalkProbe.on())
            LOG.info("[entropybot] probe keep: the job keeps {} | the fork's lease keeps {}", ids, l.kept());
    }

    /**
     * 0.39.z55 item 1 (probe): an item the bot just placed, as the lease sees it: "kept" (the job still needs it: a keep
     * failure), "throwaway" (on Baritone's throwaway list now), or "not throwaway" (a block a build or a station placed).
     * Read only; never writes a setting.
     */
    public static String placedAs(Item item) {
        var l = lease("read the kept items");
        if (l == null) return "unknown (no lease in this Baritone)";
        if (l.kept().contains(item)) return "KEPT (the job still needs it)";
        try {
            return BaritoneAPI.getSettings().acceptableThrowawayItems.value.contains(item) ? "throwaway" : "not throwaway (a build's or a station's block)";
        } catch (RuntimeException e) {
            return "unknown (" + e + ")";
        }
    }

    /**
     * 0.39.z56 item 1 (the step probe, run O: only dirt left as throwaway under ground): the bag's stacks Baritone may place
     * now - on its throwaway list and not kept by the job - as "dirt 8, ..."; "none" when there is none. Read only.
     */
    public static String throwawayInBag(net.minecraft.world.entity.player.Player p) {
        var l = lease("read the kept items");
        List<Item> kept = l == null ? List.of() : l.kept();
        java.util.Map<String, Integer> n = new java.util.LinkedHashMap<>();
        var list = BaritoneAPI.getSettings().acceptableThrowawayItems.value;
        for (var st : p.getInventory().items) {
            if (st.isEmpty() || !list.contains(st.getItem()) || kept.contains(st.getItem())) continue;
            n.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(), Integer::sum);
        }
        if (n.isEmpty()) return "none" + (l == null ? " (no lease in this Baritone: kept items unknown)" : "");
        StringBuilder b = new StringBuilder();
        n.forEach((k, v) -> b.append(b.length() == 0 ? "" : ", ").append(k).append(' ').append(v));
        return b.toString();
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

    public static void end(Object run) {
        var l = lease("end of a throwaway run");
        if (l != null) l.end(run);
    }
}
