package io.github.mojolowjo.entropybot.commands;

import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.aa.brain.Tuning;
import io.github.mojolowjo.entropybot.sim.Scenario;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * sim step 1 (to-do/simulator-plan.md): the game side of a {@link Scenario} - the blocks in a box of
 * debug.scenarioRadius round the feet read from the client level (a cell whose chunk the client does not hold is
 * Scenario.NOT_LOADED, never air: rule 15), the bag's slots, and the mod's own words (aa goals, aa status, the main
 * stations, the last decisions). Written to {@code <game dir>/entropybot/scenarios/<time>-<why>.json.gz}:
 * <ul>
 * <li>{@code aa scenario [note]}: now;</li>
 * <li>at a failed end (an order's incident, a job that ended failed) while debug.scenarioOnFail is 1 - off by default;
 * one file per {@link #GAP_MS} at most, a skipped one said.</li>
 * </ul>
 * Every write and every failure to write is logged with the file or the reason (rule 7). On the game thread: the box is
 * (2r+1)^3 block reads, 117,649 at the default radius 24 - a one-off at a failure, timed in its log line.
 *
 * <p>Loader notes: client only; vanilla types only (ClientLevel.getBlockState / isLoaded, BlockStateParser.serialize,
 * BuiltInRegistries.ITEM, Inventory.getContainerSize / getItem) - no loader API. The data class is loader-neutral.
 */
public final class ScenarioDump {
    private ScenarioDump() {}

    private static final Logger LOG = LogUtils.getLogger();
    static final long GAP_MS = 5_000L;      // structural: one automatic file per 5 s at most (a failed order ends several jobs at once)
    static final int LINES = 40;            // structural: the last decision lines kept
    static final int WHY_CHARS = 48;        // structural: the reason's part of the file name
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static long lastAutoMs;

    /** aa scenario [note]: a file now; the answer names it or says why not. */
    static String command(AaRuntime aa, String note) {
        return write(aa, "command" + (note == null || note.isBlank() ? "" : " " + note.trim()));
    }

    /** A failed end: a file when debug.scenarioOnFail is on and the last automatic one is over {@link #GAP_MS} old. */
    static void onFail(AaRuntime aa, String what) {
        try {
            if (Tuning.get("debug.scenarioOnFail") <= 0) return;
            // run JBS (two of four files were "stopped: re-priced: a source of spruce_log is known"): the planner's own
            // cancel of a running step is no failure - no file, said
            if (plannersCancel(what)) {
                LOG.info("[entropybot] scenario: not written for '{}' - the planner's own cancel (priced again), no failure", cut(what, 120));
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastAutoMs < GAP_MS) {
                LOG.info("[entropybot] scenario: not written for '{}' - one was written {} ms ago (one per {} ms)", cut(what, 120), now - lastAutoMs, GAP_MS);
                return;
            }
            lastAutoMs = now;
            write(aa, what);
        } catch (RuntimeException e) {
            LOG.warn("[entropybot] scenario: not written for '{}': {}", cut(what, 120), e.toString());
        }
    }

    /** The end's words carry the re-price cancel (Steps.RE_PRICED: the one source of that reason). Pure. */
    static boolean plannersCancel(String what) { return what != null && what.contains(io.github.mojolowjo.entropybot.aa.moves.Steps.RE_PRICED); }

    private static String cut(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n); }

    static String fileName(String stamp, String why) {
        String w = cut(why == null ? "" : why.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", ""), WHY_CHARS).replaceAll("-+$", "");
        return stamp + (w.isEmpty() ? "" : "-" + w) + ".json.gz";
    }

    static String write(AaRuntime aa, String why) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel lv = mc.level;
        if (p == null || lv == null) {
            LOG.warn("[entropybot] scenario: not written - no player or no world");
            return "scenario: not written - no player or no world";
        }
        long t0 = System.nanoTime();
        Scenario s = new Scenario();
        LocalDateTime now = LocalDateTime.now();
        s.when = now.toString();
        s.why = why;
        s.dim = lv.dimension().location().toString();
        int[] me = Jobs.here(p);
        s.feet = me.clone();
        s.hp = p.getHealth();
        s.food = p.getFoodData().getFoodLevel();
        int r = Math.max(1, Tuning.i("debug.scenarioRadius"));
        int lo = lv.getMinBuildHeight(), hi = lv.getMaxBuildHeight() - 1;
        s.x0 = me[0] - r;
        s.z0 = me[2] - r;
        s.y0 = Math.max(lo, me[1] - r);
        s.sx = s.sz = 2 * r + 1;
        s.sy = Math.max(1, Math.min(hi, me[1] + r) - s.y0 + 1);
        int[] cells = new int[s.size()];
        Map<BlockState, Integer> index = new IdentityHashMap<>();
        int notLoaded = -1, at = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = s.y0; y < s.y0 + s.sy; y++)
            for (int z = s.z0; z < s.z0 + s.sz; z++)
                for (int x = s.x0; x < s.x0 + s.sx; x++) {
                    pos.set(x, y, z);
                    if (!lv.isLoaded(pos)) {
                        if (notLoaded < 0) { notLoaded = s.palette.size(); s.palette.add(Scenario.NOT_LOADED); }
                        cells[at++] = notLoaded;
                        continue;
                    }
                    BlockState st = lv.getBlockState(pos);
                    Integer i = index.get(st);
                    if (i == null) {
                        i = s.palette.size();
                        s.palette.add(BlockStateParser.serialize(st));
                        index.put(st, i);
                    }
                    cells[at++] = i;
                }
        s.blocks(cells);
        var inv = p.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            if (it.isEmpty()) continue;
            s.bag.add(new Scenario.Slot(slot, BuiltInRegistries.ITEM.getKey(it.getItem()).toString(), it.getCount(), it.getDamageValue()));
        }
        s.orders = words(aa::goalsText);
        s.status = words(aa::statusLine);
        s.stations = words(() -> io.github.mojolowjo.entropybot.aa.MainStations.now().line());
        try {
            List<String> d = aa.decisions();
            s.lines.addAll(d.subList(Math.max(0, d.size() - LINES), d.size()));
        } catch (RuntimeException e) {
            s.lines.add("the decisions could not be read: " + e);
        }
        Path file = mc.gameDirectory.toPath().resolve("entropybot").resolve("scenarios").resolve(fileName(now.format(STAMP), why));
        try {
            s.write(file);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("[entropybot] scenario: not written to {}: {}", file, e.toString());
            return "scenario: not written to " + file + ": " + e;
        }
        double ms = (System.nanoTime() - t0) / 1e6;     // structural: nanoseconds a millisecond
        String line = "scenario: written " + file + " (" + s.summary() + String.format(java.util.Locale.ROOT, "; %.0f ms", ms) + ") for: " + cut(why, 200);
        LOG.info("[entropybot] {}", line);
        return line;
    }

    /** The mod's own words, or why they could not be read (a scenario is still written). */
    private static String words(java.util.function.Supplier<String> f) {
        try { return f.get(); } catch (RuntimeException e) { return "could not be read: " + e; }
    }
}
