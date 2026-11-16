package io.github.mojolowjo.entropybot.sim;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.calc.AStarPathFinder;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.precompute.PrecomputedData;
import baritone.utils.BlockStateInterface;
import baritone.utils.ToolSet;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.Favoring;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;

/**
 * sim step 3 (to-do/simulator-plan.md; owner 2026-11-16: "as similar as possible to the real thing"): Baritone's REAL
 * search (the fork's AStarPathFinder and its movements) on the sim's blocks. The context and the block reader are
 * Baritone's own classes with their fields set by reflection, as the fork's own tests do it
 * (bs-vein ClimbGoalTest.MapContext): no Level, no player. Test code only.
 */
final class SimSearch {
    private SimSearch() {}

    private static boolean booted;

    /** The game's registries (blocks and their states), once a JVM. */
    static synchronized void boot() {
        if (booted) return;
        SharedConstants.tryDetectVersion();
        try {
            noMods();
        } catch (Exception e) {
            throw new IllegalStateException("sim search: the loader's empty mod list could not be set: " + e, e);
        }
        Bootstrap.bootStrap();
        // Loader note (NeoForge 21.1): the patched game fills the block-state id table and each state's cache from a
        // registry callback the loader runs at its freeze; no loader here, so they are filled as vanilla's Blocks does
        // (Baritone's PrecomputedData indexes that table)
        if (net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.size() == 0) {
            for (var block : BuiltInRegistries.BLOCK) {
                for (BlockState st : block.getStateDefinition().getPossibleStates()) {
                    net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.add(st);
                    st.initCache();
                }
            }
        }
        booted = true;
    }

    /**
     * Loader note (NeoForge 21.1, FML 4.0): the patched game reads the loader's mod list while its registries start
     * (FeatureFlagLoader.loadModdedFlags). No loader runs in a JUnit JVM: an EMPTY list is set through the loader's own
     * factory (LoadingModList.of), found by reflection so a changed signature fails here with its name.
     */
    private static void noMods() throws Exception {
        Class<?> lml = Class.forName("net.neoforged.fml.loading.LoadingModList");
        if (lml.getMethod("get").invoke(null) != null) return;
        for (var m : lml.getMethods()) {
            if (!m.getName().equals("of") || !java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
            Object[] args = new Object[m.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                Class<?> k = m.getParameterTypes()[i];
                if (k == java.util.List.class) args[i] = new ArrayList<>();
                else if (k == java.util.Map.class) args[i] = new java.util.HashMap<>();
                else throw new IllegalStateException("LoadingModList.of takes a " + k.getName() + " (a new loader version?)");
            }
            m.invoke(null, args);
            return;
        }
        throw new IllegalStateException("LoadingModList has no static of(...)");
    }
    private static final IBaritone B = (IBaritone) java.lang.reflect.Proxy.newProxyInstance(SimSearch.class.getClassLoader(),
            new Class[]{IBaritone.class}, (p, m, a) -> null);

    /** Baritone's block reader over the sim's world: a block id is its default state; an id the game does not know is air, counted. */
    static class Bsi extends BlockStateInterface {
        SimWorld world;
        int unknown;

        Bsi() { super((baritone.api.utils.IPlayerContext) null); }      // never run: allocated without a constructor

        @Override public BlockState get0(int x, int y, int z) { return state(world.at(x, y, z)); }
        @Override public boolean isLoaded(int x, int z) { return true; }
        @Override public boolean worldContainsLoadedChunk(int x, int z) { return true; }

        BlockState state(String id) {
            if (id == null || id.equals(SimWorld.AIR)) return Blocks.AIR.defaultBlockState();
            ResourceLocation rl = ResourceLocation.tryParse(id);
            var b = rl == null ? null : BuiltInRegistries.BLOCK.getOptional(rl).orElse(null);
            if (b == null) { unknown++; return Blocks.AIR.defaultBlockState(); }
            return b.defaultBlockState();
        }
    }

    /** The tool as a speed: Baritone's destruction time, hardness x 30 / {@code speed} for a harvestable block (speed 1: by hand x 100). */
    static class Tool extends ToolSet {
        double speed = 1;

        Tool() { super(null); }

        @Override public double getStrVsBlock(BlockState state) {
            float h = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            return h < 0 ? -1 : h == 0 ? 1 : speed <= 1 ? 1 / (h * 100.0) : 1 / (h * 30.0 / speed);
        }
    }

    static class Context extends CalculationContext {
        Context() { super(null); }

        @Override public int dimensionMinY() { return -64; }
        @Override public int dimensionHeight() { return 384; }
        @Override public int minBuildHeight() { return -64; }
        @Override public BetterWorldBorder searchBorder() { return new BetterWorldBorder(new WorldBorder()); }
    }

    private static Object unsafe() throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> c) throws Exception {
        Object u = unsafe();
        return (T) u.getClass().getMethod("allocateInstance", Class.class).invoke(u, c);
    }

    private static void set(Object o, String name, Object value) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, value);
                return;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }

    /**
     * The walk's context on {@code world}: placing allowed when {@code throwaway} (the bag has filler to spare), priced at
     * {@code placeCost} (the mod's walk.placePenalty); breaking allowed, by the tool's speed.
     */
    static CalculationContext context(SimWorld world, boolean throwaway, double placeCost, double toolSpeed) throws Exception {
        boot();
        Bsi bsi = allocate(Bsi.class);
        bsi.world = world;
        set(bsi, "isPassableBlockPos", new BlockPos.MutableBlockPos());
        Constructor<?> w = Class.forName("baritone.utils.BlockStateInterfaceAccessWrapper").getDeclaredConstructor(BlockStateInterface.class);
        w.setAccessible(true);
        set(bsi, "access", w.newInstance(bsi));
        set(bsi, "worldBorder", new BetterWorldBorder(new WorldBorder()));
        Tool tool = allocate(Tool.class);
        tool.speed = toolSpeed;
        Context c = allocate(Context.class);
        var s = Baritone.settings();
        set(c, "precomputedData", new PrecomputedData());
        set(c, "safeForThreadedUse", true);
        set(c, "baritone", B);
        set(c, "bsi", bsi);
        set(c, "toolSet", tool);
        set(c, "hasThrowaway", throwaway);
        set(c, "hasWaterBucket", false);
        set(c, "canSprint", true);
        set(c, "placeBlockCost", placeCost);
        set(c, "allowBreak", true);
        set(c, "allowBreakAnyway", new ArrayList<>());
        set(c, "allowParkour", false);
        set(c, "allowParkourPlace", false);
        set(c, "allowJumpAtBuildLimit", false);
        set(c, "allowParkourAscend", false);
        set(c, "assumeWalkOnWater", false);
        set(c, "frostWalker", 0);
        set(c, "allowDiagonalDescend", s.allowDiagonalDescend.value);
        set(c, "allowDiagonalAscend", s.allowDiagonalAscend.value);
        set(c, "allowDownward", s.allowDownward.value);
        set(c, "minFallHeight", 3);
        set(c, "maxFallHeightNoWater", s.maxFallHeightNoWater.value);
        set(c, "maxFallHeightBucket", s.maxFallHeightBucket.value);
        set(c, "waterWalkSpeed", baritone.api.pathing.movement.ActionCosts.WALK_ONE_IN_WATER_COST);
        set(c, "breakBlockAdditionalCost", s.blockBreakAdditionalPenalty.value);
        set(c, "backtrackCostFavoringCoefficient", s.backtrackCostFavoringCoefficient.value);
        set(c, "jumpPenalty", s.jumpPenalty.value);
        set(c, "walkOnWaterOnePenalty", s.walkOnWaterOnePenalty.value);
        set(c, "allowWalkOnMagmaBlocks", s.allowWalkOnMagmaBlocks.value);
        set(c, "worldBorder", new BetterWorldBorder(new WorldBorder()));
        set(c, "pricesMoves", false);
        return c;
    }

    /** One search from {@code from} (the feet) as the game's first segment runs it: Baritone's own timeouts. Null path: none found. */
    static IPath search(CalculationContext c, int[] from, Goal goal) {
        BetterBlockPos start = new BetterBlockPos(from[0], from[1], from[2]);
        AStarPathFinder f = new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, c), c);
        PathCalculationResult r = f.calculate(Baritone.settings().primaryTimeoutMS.value, Baritone.settings().failureTimeoutMS.value);
        return r.getPath().orElse(null);
    }
}
