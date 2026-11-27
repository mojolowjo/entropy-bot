package io.github.mojolowjo.entropybot.nav.rules;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.BlockStateInterface;
import io.github.mojolowjo.entropybot.nav.config.NavConfig;
import io.github.mojolowjo.entropybot.nav.world.BlockView;
import io.github.mojolowjo.entropybot.sim.SimSearch;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Test helper for the nav steps that check our lifted code against the fork jar (1.11.3-entropy) on the same cells:
 * the fork's own {@code BlockStateInterface} and {@code CalculationContext} over one of our {@link BlockView}s, built
 * without a game (allocated, fields set by reflection, as {@code sim/SimSearch} does), and the fork's global settings
 * set from a {@link NavConfig} (and put back afterwards). The fork's ToolSet would read a live player, so the fork
 * context's tool is ours ({@link Tool}): the mining-time logic is compared, the tool maths separately.
 */
public final class ForkMirror {
    private ForkMirror() {}

    /** The fork's block reader over our view. */
    public static final class Bsi extends BlockStateInterface {
        BlockView view;

        Bsi() { super((baritone.api.utils.IPlayerContext) null); } // never run: allocated without a constructor

        @Override public BlockState get0(int x, int y, int z) { return view.get0(x, y, z); }
        @Override public boolean isLoaded(int x, int z) { return view.isLoaded(x, z); }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return view.worldContainsLoadedChunk(x, z); }
    }

    /** The fork's ToolSet answering with our ToolSet. */
    public static final class Tool extends baritone.utils.ToolSet {
        ToolSet ours;

        Tool() { super(null); } // never run

        @Override public double getStrVsBlock(BlockState state) { return ours.getStrVsBlock(state); }
    }

    /** The fork's context with the same view, tool and switches as {@code ours}. */
    public static final class Context extends CalculationContext {
        Context() { super(null); } // never run

        @Override public int dimensionMinY() { return -64; }
        @Override public int dimensionHeight() { return 384; }
        @Override public int minBuildHeight() { return -64; }
        @Override public baritone.utils.pathing.BetterWorldBorder searchBorder() { return new baritone.utils.pathing.BetterWorldBorder(new WorldBorder()); }
    }

    public static Bsi bsi(BlockView view) throws Exception {
        SimSearch.boot();
        Bsi b = allocate(Bsi.class);
        b.view = view;
        set(b, "isPassableBlockPos", new BlockPos.MutableBlockPos());
        Constructor<?> w = Class.forName("baritone.utils.BlockStateInterfaceAccessWrapper").getDeclaredConstructor(BlockStateInterface.class);
        w.setAccessible(true);
        set(b, "access", w.newInstance(b));
        set(b, "worldBorder", new baritone.utils.pathing.BetterWorldBorder(new WorldBorder()));
        return b;
    }

    /** The fork's context mirroring ours: same view, same tool answers, same switches; advisor as given (may be null). */
    public static Context context(SearchContext ours, baritone.pathing.movement.CostAdvisor advisor) throws Exception {
        Context c = allocate(Context.class);
        Tool t = allocate(Tool.class);
        t.ours = ours.toolSet;
        set(c, "precomputedData", new baritone.pathing.precompute.PrecomputedData());
        set(c, "safeForThreadedUse", true);
        // the fork's Path builds Movement objects, which ask the IBaritone for a player context: a proxy answering null
        set(c, "baritone", java.lang.reflect.Proxy.newProxyInstance(ForkMirror.class.getClassLoader(),
                new Class<?>[]{baritone.api.IBaritone.class}, (p, m, a) -> null));
        set(c, "bsi", bsi(ours.bsi));
        set(c, "toolSet", t);
        set(c, "hasThrowaway", ours.hasThrowaway);
        set(c, "hasWaterBucket", ours.hasWaterBucket);
        set(c, "canSprint", ours.canSprint);
        set(c, "placeBlockCost", ours.placeBlockCost);
        set(c, "allowBreak", ours.allowBreak);
        set(c, "allowBreakAnyway", new ArrayList<>(ours.allowBreakAnyway));
        set(c, "allowParkour", ours.allowParkour);
        set(c, "allowParkourPlace", ours.allowParkourPlace);
        set(c, "allowJumpAtBuildLimit", ours.allowJumpAtBuildLimit);
        set(c, "allowParkourAscend", ours.allowParkourAscend);
        set(c, "assumeWalkOnWater", ours.assumeWalkOnWater);
        set(c, "frostWalker", ours.frostWalker);
        set(c, "allowDiagonalDescend", ours.allowDiagonalDescend);
        set(c, "allowDiagonalAscend", ours.allowDiagonalAscend);
        set(c, "allowDownward", ours.allowDownward);
        set(c, "minFallHeight", ours.minFallHeight);
        set(c, "maxFallHeightNoWater", ours.maxFallHeightNoWater);
        set(c, "maxFallHeightBucket", ours.maxFallHeightBucket);
        set(c, "waterWalkSpeed", ours.waterWalkSpeed);
        set(c, "breakBlockAdditionalCost", ours.breakBlockAdditionalCost);
        set(c, "backtrackCostFavoringCoefficient", ours.backtrackCostFavoringCoefficient);
        set(c, "jumpPenalty", ours.jumpPenalty);
        set(c, "walkOnWaterOnePenalty", ours.walkOnWaterOnePenalty);
        set(c, "allowWalkOnMagmaBlocks", ours.allowWalkOnMagmaBlocks);
        set(c, "worldBorder", new baritone.utils.pathing.BetterWorldBorder(new WorldBorder()));
        set(c, "advisor", advisor);
        set(c, "advisorToken", null);
        set(c, "pricesMoves", ours.pricesMoves);
        return c;
    }

    /**
     * Sets the fork's global settings to {@code cfg} (same names) and returns the old values for {@link #restore}.
     * Every component of every sub-record must name a fork setting: a missing one fails here.
     */
    public static Map<String, Object> apply(NavConfig cfg) throws Exception {
        SimSearch.boot();
        Settings s = Baritone.settings();
        Map<String, Object> old = new HashMap<>();
        for (Object part : List.of(cfg.search(), cfg.moves(), cfg.breaking(), cfg.placing())) {
            for (RecordComponent rc : part.getClass().getRecordComponents()) {
                Settings.Setting<Object> setting = setting(s, rc.getName());
                old.put(rc.getName(), setting.value);
                Object v = rc.getAccessor().invoke(part);
                setting.value = v instanceof List<?> l ? new ArrayList<>(l) : v;
            }
        }
        return old;
    }

    public static void restore(Map<String, Object> old) throws Exception {
        Settings s = Baritone.settings();
        for (Map.Entry<String, Object> e : old.entrySet()) setting(s, e.getKey()).value = e.getValue();
    }

    @SuppressWarnings("unchecked")
    private static Settings.Setting<Object> setting(Settings s, String name) throws Exception {
        return (Settings.Setting<Object>) Settings.class.getField(name).get(s);
    }

    private static Object unsafe() throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    @SuppressWarnings("unchecked")
    static <T> T allocate(Class<T> c) throws Exception {
        Object u = unsafe();
        return (T) u.getClass().getMethod("allocateInstance", Class.class).invoke(u, c);
    }

    static void set(Object o, String name, Object value) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(name)) {
                    f.setAccessible(true);
                    f.set(o, value);
                    return;
                }
            }
        }
        throw new NoSuchFieldException(name);
    }
}
