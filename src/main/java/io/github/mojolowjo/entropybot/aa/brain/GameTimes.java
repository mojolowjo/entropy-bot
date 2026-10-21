package io.github.mojolowjo.entropybot.aa.brain;

/**
 * aa-times (RULES row 3, TIME_MODEL): step times come from the game's own numbers, never from a table of ours. This is
 * the pure half: the game's break-time and walk-speed formulas with every term as a plain number, so JUnit can check
 * them and a Fabric adapter would feed them the same way. The thin NeoForge adapter is
 * {@code commands.GameTimesLive}, which reads the terms with vanilla calls (all of them exist on Fabric too):
 * <ul>
 * <li>{@code ItemStack.getDestroySpeed(BlockState)} and {@code ItemStack.isCorrectToolForDrops(BlockState)} for the
 *     candidate tool (so a tool that is not held is priced without swapping it in);</li>
 * <li>{@code BlockState.getDestroySpeed(BlockGetter, BlockPos)} for the hardness (-1 = unbreakable);</li>
 * <li>{@code BlockState.requiresCorrectToolForDrops()}: the 30 / 100 divisor of {@code BlockBehaviour.getDestroyProgress};</li>
 * <li>the player's attributes {@code MINING_EFFICIENCY} (Efficiency: level² + 1, data-driven in 1.21), {@code BLOCK_BREAK_SPEED},
 *     {@code SUBMERGED_MINING_SPEED} (0.2, x5 by Aqua Affinity), and the effects Haste / Conduit Power and Mining Fatigue, the
 *     way {@code Player.getDigSpeed} reads them; {@code Player.isEyeInFluid(WATER)} and {@code Entity.onGround()};</li>
 * <li>{@code LivingEntity.getAttributeValue(MOVEMENT_SPEED)} for the walk (speed effects and Soul Speed are modifiers on it;
 *     sprinting adds x1.3 as the modifier "minecraft:sprinting"), {@code WATER_MOVEMENT_EFFICIENCY} for Depth Strider.</li>
 * </ul>
 * Formula (verified in {@code Player.getDigSpeed} and {@code BlockBehaviour.getDestroyProgress}, NeoForge 21.1.249 sources):
 * <pre>
 * speed = tool speed; if speed > 1: speed += miningEfficiency
 * speed *= 1 + (haste + 1) x 0.2 (with Haste); speed *= 0.3 / 0.09 / 0.0027 / 8.1e-4 (Mining Fatigue I..IV)
 * speed *= blockBreakSpeed; in water (eyes): speed *= submergedMiningSpeed; off the ground: speed /= 5
 * progress per tick = speed / hardness / (correct tool ? 30 : 100); ticks = ceil(1 / progress); seconds = ticks / 20
 * </pre>
 * The game also waits {@value #AFTER_BREAK_TICKS} ticks after a break before the next one starts ({@code MultiPlayerGameMode.destroyDelay});
 * {@link #breakSeconds} does not add it, so a column of blocks is predicted a little short; the ledger will show by how much.
 * Pure (JUnit: GameTimesTest).
 */
public final class GameTimes {
    private GameTimes() {}

    /** Vanilla walking speed: MOVEMENT_SPEED 0.1 = 4.317 blocks a second (the number Unit has always used). */
    public static final double BASE_SPEED_ATTR = 0.1, BASE_WALK_BPS = Unit.WALK_BPS, SPRINT_FACTOR = 1.3;
    /** Swimming at the surface without Depth Strider, about 2.2 blocks a second (the wiki's number; the adapter does not measure it). */
    public static final double BASE_SWIM_BPS = 2.2;
    /** Ticks a second, and the client's pause after a break before the next block starts. */
    public static final int TPS = 20, AFTER_BREAK_TICKS = 5;

    /**
     * The player's terms of {@code Player.getDigSpeed}, read by the adapter.
     *
     * @param miningEfficiency the MINING_EFFICIENCY attribute for the candidate tool (Efficiency: level² + 1; 0 none)
     * @param hasteLevel       Haste / Conduit Power amplifier (0 = level I), -1 none
     * @param fatigueLevel     Mining Fatigue amplifier (0 = level I), -1 none
     * @param blockBreakSpeed  BLOCK_BREAK_SPEED attribute (1 default)
     * @param submergedSpeed   SUBMERGED_MINING_SPEED attribute (0.2 default, 1 with Aqua Affinity)
     * @param inWater          eyes in water
     * @param onGround         standing on the ground (off it: a fifth of the speed)
     */
    public record Dig(double miningEfficiency, int hasteLevel, int fatigueLevel, double blockBreakSpeed, double submergedSpeed,
                      boolean inWater, boolean onGround) {
        /** No effects, no enchantments, dry, on the ground. */
        public static final Dig PLAIN = new Dig(0, -1, -1, 1, 0.2, false, true);

        public Dig withWater(boolean water) { return new Dig(miningEfficiency, hasteLevel, fatigueLevel, blockBreakSpeed, submergedSpeed, water, onGround); }

        public Dig withGround(boolean ground) { return new Dig(miningEfficiency, hasteLevel, fatigueLevel, blockBreakSpeed, submergedSpeed, inWater, ground); }

        public Dig withEfficiency(int level) { return new Dig(level <= 0 ? 0 : (double) level * level + 1, hasteLevel, fatigueLevel, blockBreakSpeed, submergedSpeed, inWater, onGround); }
    }

    /** {@code Player.getDigSpeed} for a candidate tool's {@code ItemStack.getDestroySpeed} (1 = by hand). */
    public static double digSpeed(double toolSpeed, Dig d) {
        double f = toolSpeed;
        if (f > 1) f += d.miningEfficiency();
        if (d.hasteLevel() >= 0) f *= 1 + (d.hasteLevel() + 1) * 0.2;
        if (d.fatigueLevel() >= 0) f *= switch (d.fatigueLevel()) {
            case 0 -> 0.3;
            case 1 -> 0.09;
            case 2 -> 0.0027;
            default -> 8.1e-4;
        };
        f *= d.blockBreakSpeed();
        if (d.inWater()) f *= d.submergedSpeed();
        if (!d.onGround()) f /= 5;
        return f;
    }

    /**
     * Seconds to break one block the way the game counts them: the whole ticks until the destroy progress reaches 1, at
     * least one. Infinite for an unbreakable block (hardness < 0), one tick for an instant one (hardness 0).
     *
     * @param hardness    {@code BlockState.getDestroySpeed} (stone 1.5, -1 unbreakable)
     * @param toolSpeed   {@code ItemStack.getDestroySpeed(state)} of the candidate tool, 1 by hand
     * @param correctTool the block needs no tool, or the candidate is the right one ({@code isCorrectToolForDrops})
     */
    public static double breakSeconds(double hardness, double toolSpeed, boolean correctTool, Dig d) {
        if (hardness < 0) return Double.POSITIVE_INFINITY;
        if (hardness == 0) return 1.0 / TPS;
        double speed = digSpeed(toolSpeed, d);
        if (speed <= 0) return Double.POSITIVE_INFINITY;
        double progress = speed / hardness / (correctTool ? 30 : 100);
        double ticks = Math.max(1, Math.ceil(1 / progress - 1e-9));
        return ticks / TPS;
    }

    /** The plain case: no effects, dry, on the ground. */
    public static double breakSeconds(double hardness, double toolSpeed, boolean correctTool) {
        return breakSeconds(hardness, toolSpeed, correctTool, Dig.PLAIN);
    }

    /**
     * Blocks a second on land from the MOVEMENT_SPEED attribute value: 0.1 = {@value #BASE_WALK_BPS}. The value already
     * holds every modifier on it (Speed and Slowness effects, Soul Speed on soul blocks, sprinting); {@code sprintIncluded}
     * says the sprint modifier is in it, and it is taken out so the number is the walk Unit prices with.
     */
    public static double walkBps(double movementSpeedAttr, boolean sprintIncluded) {
        if (!(movementSpeedAttr > 0) || Double.isNaN(movementSpeedAttr) || Double.isInfinite(movementSpeedAttr)) return BASE_WALK_BPS;
        double v = movementSpeedAttr / BASE_SPEED_ATTR * BASE_WALK_BPS;
        return sprintIncluded ? v / SPRINT_FACTOR : v;
    }

    /** The same walk sprinting (x{@value #SPRINT_FACTOR}). */
    public static double sprintBps(double walkBps) { return walkBps * SPRINT_FACTOR; }

    /**
     * Swimming speed with Depth Strider: WATER_MOVEMENT_EFFICIENCY e (0..1, a third per level) moves the water speed from
     * {@value #BASE_SWIM_BPS} toward the land speed ({@code LivingEntity.travel}: {@code f5 += (getSpeed() - f5) * e}).
     * Assumed linear in blocks a second; the ledger will show how close it is.
     */
    public static double swimBps(double walkBps, double waterEfficiency) {
        double e = Math.max(0, Math.min(1, waterEfficiency));
        return BASE_SWIM_BPS + (Math.max(BASE_SWIM_BPS, walkBps) - BASE_SWIM_BPS) * e;
    }
}
