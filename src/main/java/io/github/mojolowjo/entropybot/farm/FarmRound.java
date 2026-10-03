package io.github.mojolowjo.entropybot.farm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One farm round as a game-free state machine: the bridge's farmStep (farmgrow, farmharvest, farmgather, farmcompact,
 * farmdone, farmdeposit, farmnote). The step engine calls {@link #step} every time it steps the job (every 2 ticks)
 * with the current step's type, the ticks since that step started ({@code elapsed}) and the game tick
 * ({@code tick}); the returned {@link Tick} says "wait", "next" or why the job fails, which effects to apply first,
 * and the job's new status / note when they change.
 *
 * <p>The round: walk to the farm (a plain walk step before these), twerk where Squat Grow reaches every crop until
 * they are ripe (at most {@link FarmRules#FARM_GROW_S} s), right-click each ripe crop with a sword or an empty hand
 * (Harvest with Ease replants), walk over the drops, craft the inferium essence into blocks (or prudentium, or not),
 * report, and put things away at the base when the bag is nearly full.
 */
public final class FarmRound {

    /** The round's steps after the walk, in order (the bridge's startFarm). */
    public static final List<String> STEPS = List.of("farmgrow", "farmharvest", "farmgather", "farmcompact", "farmdone", "farmdeposit");

    /** Something the step engine does for the round before it acts on the result. */
    public sealed interface Effect permits Walk, CancelWalk, Sneak, Use, Craft, Deposit {}

    /** Path there with Baritone: range 0 = onto that block (GoalBlock), else within range of it (GoalNear). */
    public record Walk(int x, int y, int z, int range) implements Effect {}

    /** Cancel Baritone's path (cancelEverything). */
    public record CancelWalk() implements Effect {}

    /** Hold the sneak key down or let it go. */
    public record Sneak(boolean down) implements Effect {}

    /**
     * Get a harmless hand ({@code hand}: "held" = as it is, "select" = select that hotbar slot, "swap" = a SWAP
     * click of that bag slot with the selected hotbar slot), then right-click the block (look at its center, useItemOn
     * with the main hand, swing) - Jobs.useBlock.
     */
    public record Use(int x, int y, int z, FarmRules.Hand hand) implements Effect {}

    /** Splice a "craftitem" step (n of item, optional) right after the current step. */
    public record Craft(String item, int n, boolean optional) implements Effect {}

    /**
     * Splice the deposit steps (Storage.depositSteps(p, "", true, null): everything depositable, to the base chests)
     * and a "farmnote" step after the current step; if depositSteps fails, set the note to
     * {@link #depositFailed}(note, err) instead.
     */
    public record Deposit() implements Effect {}

    /** A step's answer: "wait", "next" or a failure; effects to apply; the job's new status and note (null: unchanged). */
    public record Tick(String result, List<Effect> effects, String status, String note) {
        static Tick of(String r) { return new Tick(r, List.of(), null, null); }
    }

    public final FarmSpot farm;
    public final String label;
    // the round's state (the bridge's job fields)
    List<int[]> cropSpots;
    int harvested, essBefore, blocksBefore, prudBefore, unripe, grewS, dropsLeft, gatherWalks;
    final Map<String, Long> clicked = new HashMap<>();
    final Map<String, Integer> clicks = new HashMap<>();
    final Map<String, Integer> gatherTries = new HashMap<>();
    Long growStart;
    long walkStart, gatherRecheck;
    boolean outOfRange, gatherFull;
    FarmWorld.Drop gatherItem;
    long gatherT;
    String stage, lastType, farmNote;

    /** {@code have}: what the bot carries as the round starts (for the "+N inferium essence" count). */
    public FarmRound(FarmSpot farm, String label, Map<String, Integer> have) {
        this.farm = farm;
        this.label = label;
        essBefore = have.getOrDefault(FarmRules.FARM_ESS, 0);
        blocksBefore = have.getOrDefault(FarmRules.FARM_BLOCK, 0);
        prudBefore = have.getOrDefault(FarmRules.FARM_PRUD, 0);
    }

    public int harvested() { return harvested; }

    public Tick step(String type, FarmWorld w, long elapsed, long tick) {
        if (!type.equals(lastType)) {
            stage = null;            // Seq starts every step with stage null
            lastType = type;
        }
        return switch (type) {
            case "farmgrow" -> grow(w, elapsed, tick);
            case "farmharvest" -> harvest(w, elapsed, tick);
            case "farmgather" -> gather(w, elapsed);
            case "farmcompact" -> compact(w);
            case "farmdone" -> done(w);
            case "farmdeposit" -> deposit(w);
            default -> Tick.of("unknown farm step " + type);
        };
    }

    private Tick grow(FarmWorld w, long elapsed, long tick) {
        List<Effect> fx = new ArrayList<>();
        if (stage == null) {
            cropSpots = FarmRules.positions(FarmRules.farmCrops(w, farm.pos()));
            if (cropSpots.isEmpty()) return Tick.of("no crops at the farm (" + farm.fmt() + ") - PM \"farm here\" next to them");
            stage = "twerk";
            // crouch where Squat Grow reaches every crop
            if (!FarmRules.cropsNow(w, cropSpots, false).isEmpty() && !FarmRules.inSquatRange(w.here(), cropSpots)) {
                FarmRules.Stand spot = FarmRules.farmStandSpot(w, cropSpots);
                if (spot != null && spot.n() > FarmRules.squatReach(w.here(), cropSpots)) {
                    stage = "tostand";
                    walkStart = elapsed;
                    return new Tick("wait", List.of(new Walk(spot.x(), spot.y(), spot.z(), 0)), label + " - going to " + spot.fmt() + " to twerk", null);
                }
            }
        }
        if ("tostand".equals(stage)) {
            if (elapsed - walkStart < 15 || (w.pathing() && elapsed - walkStart < 400)) return Tick.of("wait");
            fx.add(new CancelWalk());
            stage = "twerk";
            growStart = elapsed;
        }
        if (growStart == null) growStart = elapsed;
        if (!FarmRules.inSquatRange(w.here(), cropSpots)) outOfRange = true;
        int left = FarmRules.cropsNow(w, cropSpots, false).size();
        if (left == 0 || elapsed - growStart >= FarmRules.FARM_GROW_S * 20L) {
            fx.add(new Sneak(false));
            grewS = (int) Math.round((elapsed - growStart) / 20.0);
            unripe = left;
            return new Tick("next", fx, null, null);
        }
        fx.add(new Sneak(Math.floorDiv(tick, 4) % 2 == 0));
        return new Tick("wait", fx, label + " - twerking so they grow (" + (cropSpots.size() - left) + "/" + cropSpots.size() + " ripe)", null);
    }

    private Tick harvest(FarmWorld w, long elapsed, long tick) {
        // one right-click per call; a crouching right-click doesn't harvest
        List<Effect> fx = new ArrayList<>();
        fx.add(new Sneak(false));
        if ("walking".equals(stage)) {
            if (elapsed - walkStart < 15 || (w.pathing() && elapsed - walkStart < 200)) return new Tick("wait", fx, null, null);
            stage = null;
        }
        List<FarmRules.Crop> ripe = new ArrayList<>();
        for (FarmRules.Crop c : FarmRules.cropsNow(w, cropSpots, true)) if (clicks.getOrDefault(c.key(), 0) < 3) ripe.add(c);
        if (ripe.isEmpty() || elapsed > 20 * 60) return new Tick("next", fx, null, null);
        double[] eye = w.eye();
        FarmRules.Crop near = null;
        double nearD = 0;
        for (FarmRules.Crop c : ripe) {
            String key = c.key();
            Long at = clicked.get(key);
            if (at != null && tick - at < 20) continue;             // the server hasn't answered yet
            if (clicks.getOrDefault(key, 0) >= 3) continue;         // still ripe after 3 clicks: not harvestable
            double dx = c.x() + 0.5 - eye[0], dy = c.y() + 0.5 - eye[1], dz = c.z() + 0.5 - eye[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= FarmRules.REACH) {
                FarmRules.Hand hand = FarmRules.harmlessHand(w.slots(), w.selected());
                if (hand == null) return new Tick("I can't free my hand for the harvest (no sword, no empty slot) - give me a sword", fx, null, null);
                fx.add(new Use(c.x(), c.y(), c.z(), hand));
                if (at == null) harvested++;
                clicked.put(key, tick);
                clicks.merge(key, 1, Integer::sum);
                return new Tick("wait", fx, label + " - harvesting (" + harvested + ")", null);
            }
            if (near == null || d < nearD) {
                near = c;
                nearD = d;
            }
        }
        if (near == null) return new Tick("wait", fx, null, null);
        // none within reach: walk next to the nearest
        fx.add(new Walk(near.x(), near.y(), near.z(), 1));
        stage = "walking";
        walkStart = elapsed;
        return new Tick("wait", fx, null, null);
    }

    private Tick gather(FarmWorld w, long elapsed) {
        // Walk over the drops lying on the farm. The last crops' drops need a moment to show up, so it waits a second
        // first and looks once more before it finishes. A drop on a block the bot can't stand on (the water in the
        // middle) is picked up from a free spot next to it. Each drop gets 2 tries of 5 s.
        if (elapsed < 20) return Tick.of("wait");
        if (gatherItem != null) {
            if (w.dropAlive(gatherItem.key()) && elapsed - gatherT < 100) return Tick.of("wait");
            gatherItem = null;
        }
        List<FarmWorld.Drop> drops = new ArrayList<>();
        for (FarmWorld.Drop d : FarmRules.farmDrops(w, farm)) if (gatherTries.getOrDefault(d.key(), 0) < 2) drops.add(d);
        if (drops.isEmpty() || gatherWalks >= 30) {
            // nothing (more) to fetch: look once more a moment later, a drop may still be landing
            if (!drops.isEmpty() || (gatherRecheck != 0 && elapsed - gatherRecheck >= 15)) {
                dropsLeft = FarmRules.farmDrops(w, farm).size();
                return Tick.of("next");
            }
            if (gatherRecheck == 0) gatherRecheck = elapsed;
            return Tick.of("wait");
        }
        gatherRecheck = 0;
        drops.sort((a, b) -> Double.compare(FarmRules.dropDist(w, a), FarmRules.dropDist(w, b)));
        FarmWorld.Drop best = drops.get(0);
        if (!w.roomFor(best)) {
            gatherFull = true;
            return Tick.of("next");
        }
        int tries = gatherTries.merge(best.key(), 1, Integer::sum);
        int[] spot = FarmRules.dropStandSpot(w, best, tries > 1);
        Walk walk = spot != null ? new Walk(spot[0], spot[1], spot[2], 0)
                : new Walk((int) Math.floor(best.x()), (int) Math.floor(best.y() + 0.5), (int) Math.floor(best.z()), 2);
        gatherItem = best;
        gatherT = elapsed;
        gatherWalks++;
        return new Tick("wait", List.of(walk), label + " - picking up the drops", null);
    }

    private Tick compact(FarmWorld w) {
        String mode = farm.mode();
        int ess = w.inventory().getOrDefault(FarmRules.FARM_ESS, 0), n = 0;
        String item = null;
        if (mode.equals("block")) {
            n = ess / 9;
            item = FarmRules.FARM_BLOCK;
        } else if (mode.equals("prudentium")) {
            n = ess / 4;
            item = FarmRules.FARM_PRUD;
        }
        return new Tick("next", n >= 1 ? List.of(new Craft(item, n, true)) : List.of(), null, null);
    }

    private Tick done(FarmWorld w) {
        Map<String, Integer> have = w.inventory();
        int blocks = have.getOrDefault(FarmRules.FARM_BLOCK, 0) - blocksBefore;
        int prud = have.getOrDefault(FarmRules.FARM_PRUD, 0) - prudBefore;
        int gained = have.getOrDefault(FarmRules.FARM_ESS, 0) + 9 * blocks + 4 * prud - essBefore;
        farmNote = report(harvested, grewS, unripe, outOfRange, gained, blocks, prud, gatherFull, dropsLeft);
        return new Tick("next", List.of(), null, farmNote);
    }

    /** The round's report, word for word the bridge's. */
    public static String report(int harvested, int grewS, int unripe, boolean outOfRange, int gained, int blocks, int prud,
                                boolean gatherFull, int dropsLeft) {
        return "harvested " + harvested + " crops" + (grewS != 0 ? " (twerked " + grewS + " s)" : "")
                + (unripe != 0 ? ", " + unripe + " still growing" + (outOfRange ? " (I couldn't stand in reach of them all)" : "") : "")
                + ", +" + gained + " inferium essence"
                + (blocks > 0 ? "; made " + blocks + " inferium block" + (blocks > 1 ? "s" : "") : "")
                + (prud > 0 ? "; made " + prud + " prudentium essence" : "")
                + (gatherFull ? "; my inventory is full, so some drops stay on the ground" : dropsLeft != 0 ? "; " + dropsLeft + " drops I couldn't reach" : "");
    }

    private Tick deposit(FarmWorld w) {
        // nearly full (FARM_FREE free slots or fewer)? Put things away at the base chests (the walk teleports home
        // when that's far), so "repeat forever farm" never fills up. The next round walks back.
        if (FarmRules.freeSlots(w.slots()) > FarmRules.FARM_FREE) return Tick.of("next");
        return new Tick("next", List.of(new Deposit()), null, null);
    }

    /** The note when the bag is nearly full but the deposit can't be planned (the bridge's dep.err). */
    public static String depositFailed(String note, String err) {
        return (note == null ? "" : note) + "; my bag is nearly full, but " + err.replaceFirst("^error: ", "");
    }

    /** The "farmnote" step: the deposit's own note ("put away N items") goes after the farm's. */
    public String noteAfterDeposit(String noteNow) {
        String f = farmNote == null ? "" : farmNote;
        return f + (noteNow != null && !noteNow.isEmpty() && !noteNow.equals(farmNote) ? "; " + noteNow : "");
    }
}
