package io.github.mojolowjo.entropybot.baritone;

import io.github.mojolowjo.entropybot.Quiet;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import com.mojang.logging.LogUtils;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Guard;
import net.minecraft.world.level.block.Block;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * B7e (E1): the bridge's Baritone safety net, in the mod (rules in {@link SafetyRules}).
 * <ul>
 *   <li>The protected blocks ({@code Guard}'s floor: block entities and building blocks, never ores) go into Baritone's
 *       {@code blocksToDisallowBreaking} as Guava's hash-backed set view, so Baritone's own A* and "mine" never break
 *       them and the path search stays fast; put once the floor is ready and again every 200 ticks.</li>
 *   <li>0.39.z8 (owner 2026-10-22): breaking and placing are never flipped here; each fork process owns them for
 *       its own run.</li>
 *   <li>{@link #restore()} for "stop"; {@link #execute} for raw Baritone commands (a "set" never saves the list).</li>
 * </ul>
 * FIXED_OFF stays in {@link BaritoneHook#enforceSettings} (one owner); restore() calls it too.
 */
public final class SafetyNet {
    private static final Logger LOG = LogUtils.getLogger();
    public static final SafetyNet INSTANCE = new SafetyNet();

    private volatile List<Block> list;
    private volatile String kind = "none";
    private int errors;

    private SafetyNet() {}

    /** "hash", "list" or "none" (not built yet). */
    public String kind() { return kind; }

    public int size() { List<Block> l = list; return l == null ? 0 : l.size(); }

    /** Once a client tick in a world (Core's tick). Never throws. */
    public void tick(long tick) {
        try {
            if (!Guard.INSTANCE.floorReady()) return;
            Settings s = BaritoneAPI.getSettings();
            if (SafetyRules.protectedDue(tick, list != null)) putProtected(s);
            // 0.39.z2 (owner 2026-10-22, "AA is the only driver"): the 200-tick sweep that turned breaking/placing off
            // and cancelled Baritone with no mod job is deleted; AA's door is the one driver gate, the owner's raw
            // Baritone commands are the owner's. The protected-block list (the policy's floor) stays.
        } catch (Throwable t) {
            if (errors++ < 5) LOG.warn("[entropybot] safety net: {}", t.toString());
        }
    }

    /** The protected list into Baritone (built once from the guard's floor). */
    @SuppressWarnings("unchecked")
    void putProtected(Settings s) {
        List<Block> l = list;
        if (l == null) {
            Set<Block> set = Guard.INSTANCE.protectedBlocks();
            if (set == null) return;
            String k = "hash";
            try {
                l = com.google.common.collect.ImmutableSet.copyOf(set).asList();
            } catch (Throwable t) {
            Quiet.warn("SafetyNet.putProtected failed, carrying on with the fallback", t);
                l = new ArrayList<>(set);
                k = "list";
            }
            list = l;
            kind = k;
            LOG.info("[entropybot] {}", SafetyRules.kindLine(k, l.size()));
        }
        Settings.Setting<List<Block>> st = (Settings.Setting<List<Block>>) s.byLowerName.get("blockstodisallowbreaking");
        if (st != null && st.value != l) st.value = l;
    }

    /** The protected list back to Baritone's default (empty), for the moment a "set" saves settings.txt. */
    void resetProtected(Settings s) {
        Settings.Setting<?> st = s.byLowerName.get("blockstodisallowbreaking");
        if (st != null) st.reset();
    }

    /** What Baritone holds now: "hash, N", "list, N" or "none (N blocks)" when it isn't ours. */
    public String held() {
        try {
            Settings.Setting<?> st = BaritoneAPI.getSettings().byLowerName.get("blockstodisallowbreaking");
            Object v = st == null ? null : st.value;
            int n = v instanceof List<?> l ? l.size() : 0;
            if (v == null || v != list) return "none (" + n + " blocks)";
            return kind + ", " + n;
        } catch (Throwable t) {
            return "unknown (" + t + ")";
        }
    }

    /**
     * The job leftovers off (SafetyRules' lists; not breaking / placing, the fork processes' own), the protected list
     * and the FIXED_OFF settings back. For "stop" (Commands.stopAll).
     */
    @SuppressWarnings("unchecked")
    public void restore() {
        try {
            Settings s = BaritoneAPI.getSettings();
            for (String name : SafetyRules.RESTORE_FALSE) setFalse(s, name);
            for (String name : SafetyRules.RESTORE_RESET) {
                Settings.Setting<?> st = s.byLowerName.get(name.toLowerCase());
                if (st != null) st.reset();
            }
            for (String name : SafetyRules.RESTORE_FALSE_IF_PRESENT) setFalse(s, name);
            io.github.mojolowjo.entropybot.guard.PolicyRuntime.INSTANCE.setIntent(io.github.mojolowjo.entropybot.guard.BreakPolicy.Intent.TRAVEL, "restore");
            if (Guard.INSTANCE.floorReady()) putProtected(s);
            Core.INSTANCE.baritone.enforceSettings();
        } catch (Throwable t) {
            if (errors++ < 5) LOG.warn("[entropybot] safety net restore: {}", t.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void setFalse(Settings s, String name) {
        Settings.Setting<?> st = s.byLowerName.get(name.toLowerCase());
        if (st != null && st.value instanceof Boolean) ((Settings.Setting<Boolean>) st).value = false;
    }

    /** A raw Baritone command (the `b` verb): true when Baritone took it. A "set" never saves the protected list. */
    public boolean execute(IBaritone b, String text) {
        Settings s = BaritoneAPI.getSettings();
        return SafetyRules.executeGuarded(text, () -> resetProtected(s), () -> b.getCommandManager().execute(text), () -> {
            if (Guard.INSTANCE.floorReady()) putProtected(s);
        });
    }

    public static IBaritone primary() {
        try { return BaritoneAPI.getProvider().getPrimaryBaritone(); } catch (Throwable t) {
            Quiet.warn("SafetyNet.primary failed, carrying on with the fallback", t); return null; }
    }

    static boolean idle(IBaritone b) {
        return !b.getPathingBehavior().isPathing() && b.getPathingControlManager().mostRecentInControl().isEmpty();
    }

    /** Baritone's own "cancel" also clears a pause (sent only while the pause is in control), then cancelEverything. */
    public static void cancel(IBaritone b) {
        try {
            var c = b.getPathingControlManager().mostRecentInControl();
            if (c.isPresent() && c.get().displayName().contains("Pause")) b.getCommandManager().execute("cancel");
        } catch (Throwable caught) { Quiet.warn("SafetyNet.cancel failed, carrying on with the fallback", caught); }
        try { b.getPathingBehavior().cancelEverything(); } catch (Throwable caught) { Quiet.warn("SafetyNet.cancel failed, carrying on with the fallback", caught); }
    }
}
