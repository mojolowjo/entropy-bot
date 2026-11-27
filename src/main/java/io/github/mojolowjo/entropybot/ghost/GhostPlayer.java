package io.github.mojolowjo.entropybot.ghost;

import com.mojang.authlib.GameProfile;
import io.github.mojolowjo.entropybot.mixin.GhostMixinEntityAccess;
import io.github.mojolowjo.entropybot.mixin.GhostMixinLivingAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneOreBlock;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.fluids.FluidType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dry-run package 1 (plan "Dry-run checker plan (ghost body)", m0j 2026-11-26): a throwaway copy of the player that is
 * stepped through the game's own movement code, one tick at a time, to see where a move would really end. It is a
 * client-side {@link Player} that is never added to the world: nothing ticks, renders, saves or sends it, only
 * {@link #step} moves it, and the real player and the world never feel it.
 *
 * <p><b>What it copies</b> ({@link #copyFrom}): position, speed, on-ground and the supporting block, fall distance, facing,
 * pose, sneak, sprint and swim flags, air, the collision flags of the last tick, the jump delay, abilities (flying), the
 * active effects, and every live attribute with its modifiers ({@code AttributeMap.assignAllValues}), so a modpack's
 * changes to step height, jump strength, speed, gravity or safe fall distance are in it.
 *
 * <p><b>Its tick</b> ({@link #step}) is the real player's tick cut down to movement, in vanilla order:
 * {@code Player.tick}'s {@code updateIsUnderwater}; {@code Entity.baseTick}'s powder-snow reset, water push
 * ({@code updateInWaterStateAndDoFluidPushing}), eyes-in-fluid and swimming, lava's fall scale; the air supply of
 * {@code LivingEntity.baseTick}; from {@code LocalPlayer.aiStep} the input, the sneak sink in a fluid; from
 * {@code LivingEntity.aiStep} the jump delay, the tiny-speed cut, the jump (ground or fluid), the 0.98 input scale and
 * {@code travel}; and {@code Player.tick}'s {@code updatePlayerPose}. Left out on purpose: portals, sprint particles,
 * item pickup ({@code Player.touch}), pushing other players ({@code LivingEntity.pushEntities}, it would push the real
 * one), freezing, food, stats, and the NeoForge player-tick events. Not copied yet (open): the cobweb/berry stuck
 * multiplier carried into the next tick, elytra flight, riding, auto-jump, and {@code LocalPlayer.moveTowardsClosestSpace}
 * (the push out of a block the player is stuck in).
 *
 * <p><b>Leak guards</b> (source facts 2026-11-26 section 3, all without an injection):
 * <ul>
 *   <li>silent ({@link #playSound} and the swim splash do nothing; {@code setSilent});</li>
 *   <li>redstone ore: {@code RedStoneOreBlock.stepOn} lights the ore with {@code level.setBlock} on the client copy
 *       unless the entity {@code isSteppingCarefully()}; the ghost says it is, only while the block it stands on is a
 *       redstone ore block, so slime's and magma's {@code stepOn} (they ask the same) still see the true sneak state;</li>
 *   <li>NeoForge {@code LivingFallEvent}: {@link #causeFallDamage} is overridden, keeps the fall (distance, the block's
 *       multiplier, the damage by the game's own {@code calculateFallDamage}) and fires no event and no hurt;</li>
 *   <li>NeoForge {@code LivingJumpEvent}: {@link #jumpFromGround} is vanilla's body without the event;</li>
 *   <li>NeoForge {@code LivingBreatheEvent}: the air supply is the vanilla rule, run here instead of
 *       {@code CommonHooks.onLivingBreathe}.</li>
 * </ul>
 * Still seen by other mods: NeoForge's {@code EntityEvent.EntityConstructing} when a ghost is built (once per ghost, not per
 * tick). On a client world {@code hurt()} is false, game events do nothing and plates, tripwires and berry damage act on
 * the server only. A few client particles can still come from blocks' {@code entityInside} (powder snow flakes).
 *
 * <p>The facing is the ghost's own ({@link #aim}): {@link #getYRot}/{@link #getXRot} are overridden, so the game's
 * {@code moveRelative} and the sprint jump read it; nothing sets the real player's camera (MoversTest).
 *
 * <p>Pure: no (the game side; its plain pieces are {@link GhostTickRules}, JUnit GhostTickRulesTest). Loader notes:
 * vanilla {@code Player}/{@code LivingEntity}/{@code Entity}; NeoForge fluid types ({@code getMaxHeightFluidType},
 * {@code jumpInFluid}, {@code sinkInFluid}, {@code canSwimInFluidType}, {@code getFluidFallDistanceModifier}) and the
 * NeoForge copy of {@code jumpFromGround}; two accessor mixins ({@code GhostMixinEntityAccess}, {@code GhostMixinLivingAccess}).
 * On Fabric: the same class with the vanilla water/lava branches, the accessors as access wideners.
 */
public final class GhostPlayer extends Player {
    /** One landing: the fall distance, the landing block's damage multiplier, and the damage the game would deal. */
    public record Fall(int tick, float distance, float multiplier, int damage) {}

    private final boolean spectator;
    private final boolean creative;
    private float yaw;
    private float pitch;
    private int jumpDelay;
    private int steps;
    private int jumps;
    private boolean drowned;
    private final List<Fall> falls = new ArrayList<>();

    private GhostPlayer(Level level, BlockPos at, GameProfile profile, boolean spectator, boolean creative) {
        super(level, at, 0f, profile);
        this.spectator = spectator;
        this.creative = creative;
        this.setSilent(true);
    }

    /** True when both accessor mixins applied (else the ghost cannot run; the log has the mixin error). */
    public static boolean ready() {
        return GhostMixinEntityAccess.class.isAssignableFrom(Entity.class) && GhostMixinLivingAccess.class.isAssignableFrom(LivingEntity.class);
    }

    /** A ghost of {@code real} in its level, with the real player's state copied in. */
    public static GhostPlayer of(Player real) {
        if (!ready()) throw new IllegalStateException("the ghost's accessor mixins did not apply (see the game log)");
        GhostPlayer g = new GhostPlayer(real.level(), real.blockPosition(), new GameProfile(UUID.randomUUID(), "ghost"), real.isSpectator(), real.isCreative());
        g.copyFrom(real);
        return g;
    }

    /** Copies the real player's movement state into this ghost (see the class javadoc for the list). */
    public void copyFrom(Player real) {
        this.getAttributes().assignAllValues(real.getAttributes());
        Map<Holder<MobEffect>, MobEffectInstance> fx = this.getActiveEffectsMap();
        fx.clear();
        // straight into the map: the effects' attribute modifiers came with the attributes, and addEffect would fire
        // NeoForge's MobEffectEvent for the ghost
        real.getActiveEffectsMap().forEach((k, v) -> fx.put(k, new MobEffectInstance(v)));
        Abilities a = real.getAbilities(), b = this.getAbilities();
        b.flying = a.flying;
        b.mayfly = a.mayfly;
        b.invulnerable = a.invulnerable;
        b.instabuild = a.instabuild;
        b.setFlyingSpeed(a.getFlyingSpeed());
        b.setWalkingSpeed(a.getWalkingSpeed());
        this.noPhysics = real.noPhysics;
        this.setPose(real.getPose());
        this.refreshDimensions();
        this.aim(real.getYRot(), real.getXRot());
        this.setShiftKeyDown(real.isShiftKeyDown());
        this.setSprinting(real.isSprinting());
        this.setSwimming(real.isSwimming());
        this.setPos(real.getX(), real.getY(), real.getZ());
        this.setOldPosAndRot();
        this.setDeltaMovement(real.getDeltaMovement());
        this.setOnGround(real.onGround());
        this.mainSupportingBlockPos = real.mainSupportingBlockPos;
        this.fallDistance = real.fallDistance;
        this.horizontalCollision = real.horizontalCollision;
        this.minorHorizontalCollision = real.minorHorizontalCollision;
        this.verticalCollision = real.verticalCollision;
        this.verticalCollisionBelow = real.verticalCollisionBelow;
        this.wasTouchingWater = real.isInWater();
        this.isInPowderSnow = real.isInPowderSnow;
        this.wasInPowderSnow = real.wasInPowderSnow;
        this.setAirSupply(real.getAirSupply());
        this.tickCount = real.tickCount;
        this.firstTick = false;
        this.jumpDelay = ((GhostMixinLivingAccess) (Object) real).entropybot$noJumpDelay();
        ((GhostMixinEntityAccess) (Object) this).entropybot$updateFluidOnEyes();
        this.wasEyeInWater = this.isEyeInFluid(FluidTags.WATER);
        this.wasUnderwater = real.isUnderWater();
        this.steps = 0;
        this.jumps = 0;
        this.drowned = false;
        this.falls.clear();
    }

    /** Sets the ghost's own facing (degrees). The real player's camera is never touched. */
    public void aim(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override public float getYRot() { return yaw; }
    @Override public float getXRot() { return pitch; }

    /** One game tick of movement with this input (see the class javadoc for what it runs). */
    public void step(GhostInput in) {
        steps++;
        tickCount++;
        this.setOldPosAndRot();
        // Player.tick (head)
        this.updateIsUnderwater();
        // Entity.baseTick, the movement pieces
        this.wasInPowderSnow = this.isInPowderSnow;
        this.isInPowderSnow = false;
        this.updateInWaterStateAndDoFluidPushing();
        ((GhostMixinEntityAccess) (Object) this).entropybot$updateFluidOnEyes();
        this.updateSwimming();
        if (this.isInLava()) this.fallDistance *= this.getFluidFallDistanceModifier(NeoForgeMod.LAVA_TYPE.value());
        // LivingEntity.baseTick: the air supply (vanilla's rule; NeoForge's LivingBreatheEvent is not fired for a ghost)
        breathe();
        // LocalPlayer.aiStep: the input and the sneak sink
        this.aim(in.yaw(), in.pitch());
        this.setShiftKeyDown(in.shift());
        this.setSprinting(in.sprint());
        this.xxa = in.left();
        this.zza = in.forward();
        this.jumping = in.jump();
        FluidType top = this.getMaxHeightFluidType();
        if ((this.isInWater() || (!top.isAir() && this.canSwimInFluidType(top))) && in.shift() && this.isAffectedByFluids())
            this.sinkInFluid(this.isInWater() ? NeoForgeMod.WATER_TYPE.value() : top);
        // LivingEntity.aiStep
        jumpDelay = GhostTickRules.decayDelay(jumpDelay);
        Vec3 v = this.getDeltaMovement();
        this.setDeltaMovement(GhostTickRules.zeroTiny(v.x), GhostTickRules.zeroTiny(v.y), GhostTickRules.zeroTiny(v.z));
        if (this.isImmobile()) {
            this.jumping = false;
            this.xxa = 0f;
            this.zza = 0f;
        }
        jumpBlock();
        this.xxa *= GhostTickRules.INPUT_SCALE;
        this.zza *= GhostTickRules.INPUT_SCALE;
        if (this.hasEffect(MobEffects.SLOW_FALLING) || this.hasEffect(MobEffects.LEVITATION)) this.resetFallDistance();
        this.travel(new Vec3(this.xxa, this.yya, this.zza));
        // Player.tick (tail)
        this.updatePlayerPose();
    }

    private void jumpBlock() {
        FluidType top = this.getMaxHeightFluidType();
        double h = !top.isAir() ? this.getFluidTypeHeight(top) : this.isInLava() ? this.getFluidHeight(FluidTags.LAVA) : this.getFluidHeight(FluidTags.WATER);
        GhostTickRules.JumpStep js = GhostTickRules.jump(this.jumping, this.isAffectedByFluids(), top.isAir(), this.isInWater(), this.isInLava(),
                this.onGround(), h, this.getFluidJumpThreshold(), jumpDelay);
        jumpDelay = js.delay();
        switch (js.act()) {
            case FROM_GROUND -> this.jumpFromGround();
            case IN_WATER -> this.jumpInFluid(NeoForgeMod.WATER_TYPE.value());
            case IN_LAVA -> this.jumpInFluid(NeoForgeMod.LAVA_TYPE.value());
            case IN_OTHER_FLUID -> this.jumpInFluid(top);
            case NONE -> { }
        }
    }

    private void breathe() {
        int air = this.getAirSupply();
        boolean eyesWet = this.isEyeInFluid(FluidTags.WATER)
                && !this.level().getBlockState(BlockPos.containing(this.getX(), this.getEyeY(), this.getZ())).is(Blocks.BUBBLE_COLUMN);
        if (eyesWet) {
            if (!this.canBreatheUnderwater() && !MobEffectUtil.hasWaterBreathing(this) && !this.getAbilities().invulnerable) {
                air = this.decreaseAirSupply(air);
                if (air == -20) {               // structural: vanilla LivingEntity.baseTick (a drowning hit, then air 0)
                    air = 0;
                    drowned = true;
                }
            }
        } else if (air < this.getMaxAirSupply()) {
            air = this.increaseAirSupply(air);
        }
        this.setAirSupply(air);
    }

    /** Vanilla {@code LivingEntity.jumpFromGround} (NeoForge 21.1.249) without {@code CommonHooks.onLivingJump}. */
    @Override
    public void jumpFromGround() {
        float f = this.getJumpPower();
        if (f > 1.0E-5F) {                       // structural: vanilla
            Vec3 v = this.getDeltaMovement();
            this.setDeltaMovement(v.x, f, v.z);
            if (this.isSprinting()) {
                float r = this.getYRot() * (float) (Math.PI / 180.0);
                this.addDeltaMovement(new Vec3(-Mth.sin(r) * 0.2, 0.0, Mth.cos(r) * 0.2));   // structural: vanilla sprint-jump boost
            }
            this.hasImpulse = true;
            jumps++;
        }
    }

    /** Keeps the landing and answers like the game would, without {@code LivingFallEvent} and without {@code hurt}. */
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier, DamageSource source) {
        int damage = this.mayFly() ? 0 : this.calculateFallDamage(fallDistance, multiplier);
        falls.add(new Fall(steps, fallDistance, multiplier, damage));
        return damage > 0;
    }

    /** True on a redstone ore block, so its {@code stepOn} does not light it (see the class javadoc). */
    @Override
    public boolean isSteppingCarefully() {
        return super.isSteppingCarefully() || this.level().getBlockState(this.getOnPosLegacy()).getBlock() instanceof RedStoneOreBlock;
    }

    @Override public boolean isControlledByLocalInstance() { return true; }
    @Override public boolean isSpectator() { return spectator; }
    @Override public boolean isCreative() { return creative; }
    @Override public boolean canSpawnSprintParticle() { return false; }
    @Override public void playSound(SoundEvent sound, float volume, float pitch) { /* the ghost makes no sound */ }
    @Override protected void doWaterSplashEffect() { /* sound and particles only: nothing for a ghost */ }

    public int steps() { return steps; }
    public int jumps() { return jumps; }
    public boolean drowned() { return drowned; }
    public List<Fall> falls() { return List.copyOf(falls); }
    public int jumpDelay() { return jumpDelay; }
}
