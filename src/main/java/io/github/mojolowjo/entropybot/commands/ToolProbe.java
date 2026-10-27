package io.github.mojolowjo.entropybot.commands;

import io.github.mojolowjo.entropybot.Quiet;
import io.github.mojolowjo.entropybot.aa.moves.Step;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 0.39.z77 hotbar (to-do candidate-strip-pace, runs CW-DI: "No log line names the tool held while a strip ran"; DI strip 1
 * at 4.9 s/cell with the wooden pickaxe in hotbar 1 while the stone pickaxe sat in the bag): with Tuning
 * {@code debug.toolProbe} 1, one {@code [entropybot] probe tool:} line in latest.log (never chat) per break stage of the
 * block being broken: the stage, the block, the item in hand and its speed on that block, and - when the bag holds a
 * faster one - that item, its slot and "LOWER than the bag's". Facts only: Baritone picks the tool (RULES rule 2, the
 * fork's ToolSet from the hotbar and its run lease's allowInventory reach into the bag); nothing here moves an item.
 * Off (the default): one Tuning read a tick. Never throws into the tick (Quiet.warn). Pure parts in {@link ProbeLines}
 * (JUnit: Z77ToolProbeTest). Loader notes: vanilla {@code Minecraft#hitResult}, {@code Inventory#selected / items},
 * {@code ItemStack#getDestroySpeed(BlockState)} (enchantments are not in it: the speed of the item itself), the break
 * stage through {@link StepSignalsLive#destroyStage}; Fabric: the same under Yarn names.
 */
public final class ToolProbe {
    private static final Logger LOG = LoggerFactory.getLogger("entropybot");
    private static final ProbeLines.Stages STAGES = new ProbeLines.Stages();

    private ToolProbe() {}

    /** Core, every client tick. */
    public static void tick(long t) {
        if (!ProbeLines.toolOn()) return;
        try {
            tick0(t);
        } catch (RuntimeException | LinkageError e) {
            Quiet.warn("ToolProbe.tick: the tool line could not be read", e);
        }
    }

    private static void tick0(long t) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        int stage = StepSignalsLive.destroyStage(p);
        BlockPos at = stage >= 0 && mc.hitResult instanceof BlockHitResult bh ? bh.getBlockPos() : null;
        String cell = at == null ? null : at.getX() + " " + at.getY() + " " + at.getZ();
        if (!STAGES.due(cell, at == null ? -1 : stage)) return;
        BlockState state = mc.level.getBlockState(at);
        var inv = p.getInventory();
        ProbeLines.Held hand = held(inv.items.get(inv.selected), inv.selected, state);
        ProbeLines.Held best = null;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.items.get(i);
            if (st.isEmpty()) continue;
            ProbeLines.Held h = held(st, i, state);
            if (best == null || h.speed() > best.speed()) best = h;
        }
        Step s = StepProbe.current();
        LOG.info("[entropybot] probe tool: {}", ProbeLines.tool(t, stage, cell, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(), hand, best,
                s == null ? null : Step.verb(s) + " " + s.text()));
    }

    private static ProbeLines.Held held(ItemStack st, int slot, BlockState state) {
        return new ProbeLines.Held(st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), slot, st.getDestroySpeed(state));
    }
}
