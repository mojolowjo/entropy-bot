package io.github.mojolowjo.entropybot.farm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One farm round as a game-free state machine: the bridge's farmStep (farmscan, farmharvest, farmgather, farmcompact,
 * farmdone, farmdeposit, farmnote). The step engine calls {@link #step} every time it steps the job (every 2 ticks)
 * with the current step's type, the ticks since that step started ({@code elapsed}) and the game tick
 * ({@code tick}); the returned {@link Tick} says "wait", "next" or why the job fails, which effects to apply first,
 * and the job's new status / note when they change.
 *
 * <p>The round: walk to the farm (a plain walk step before these), count the crops, right-click each ripe crop with a sword or an empty hand
 * (Harvest with Ease replants), walk over the drops, craft the inferium essence into blocks (or prudentium, or not),
 * report, and put things away at the base when the bag is nearly full.
 */
public final class FarmRound {

    /** The round's steps after the walk, in order (the bridge's startFarm). */
    public static final List<String> STEPS = List.of("farmscan", "farmharvest", "farmgather", "farmcompact", "farmdone", "farmdeposit");

    /** Something the step engine does for the round before it acts on the result. */
    public sealed interface Effect permits Walk, CancelWalk, Use, Craft, Deposit, Break, Plant {}

    /** P5 vanilla mode: break the ripe crop at x y z (one click: crops break at once). Needs a break lease. */
    public record Break(int x, int y, int z) implements Effect {}

    /** P5 vanilla mode: hold {@code item} (a seed) and right-click the top of the farmland at x y z. Needs a place lease. */
    public record Plant(int x, int y, int z, String item) implements Effect {}

    /** The vanilla round's extra step, after "farmgather": the broken crops get a seed again. */
    public static final String REPLANT = "farmreplant";

    /** Path there with Baritone: range 0 = onto that block (GoalBlock), else within range of it (GoalNear). */
    public record Walk(int x, int y, int z, int range) implements Effect {}

    /** Cancel Baritone's path (cancelEverything). */
    public record CancelWalk() implements Effect {}

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
    int harvested, essBefore, blocksBefore, prudBefore, unripe, dropsLeft, gatherWalks;
    final Map<String, Long> clicked = new HashMap<>();
    final Map<String, Integer> clicks = new HashMap<>();
    final Map<String, Integer> gatherTries = new HashMap<>();
    long walkStart, gatherRecheck;
    boolean gatherFull;
    FarmWorld.Drop gatherItem;
    long gatherT;
    String stage, lastType, farmNote;

    /** P5: right-click harvest (modded) or break and replant (vanilla). */
    public final boolean rightClick;
    /** Vanilla: the crops broken this round ("x y z" -> {x, y, z}) and the seed each one takes back. */
    final Map<String, int[]> broken = new java.util.LinkedHashMap<>();
    final Map<String, String> seeds = new HashMap<>();
    final java.util.Set<String> settled = new java.util.HashSet<>();
    final Map<String, Integer> plantTries = new HashMap<>();
    final Map<String, Long> plantAt = new HashMap<>();
    int replanted, noSeed, replantFailed;
    final java.util.Set<String> missingSeeds = new java.util.TreeSet<>();

    /** {@code have}: what the bot carries as the round starts (for the "+N inferium essence" count). */
    public FarmRound(FarmSpot farm, String label, Map<String, Integer> have) {
        this(farm, label, have, true);
    }

    public FarmRound(FarmSpot farm, String label, Map<String, Integer> have, boolean rightClick) {
        this.farm = farm;
        this.label = label;
        this.rightClick = rightClick;
        essBefore = have.getOrDefault(FarmRules.FARM_ESS, 0);
        blocksBefore = have.getOrDefault(FarmRules.FARM_BLOCK, 0);
        prudBefore = have.getOrDefault(FarmRules.FARM_PRUD, 0);
    }

    /** The round's steps after the walk: STEPS, and in vanilla mode the replant after the gathering. */
    public List<String> steps() {
        if (rightClick) return STEPS;
        List<String> out = new ArrayList<>(STEPS);
        out.add(out.indexOf("farmgather") + 1, REPLANT);
        return out;
    }

    public int harvested() { return harvested; }

    public int replanted() { return replanted; }

    public Tick step(String type, FarmWorld w, long elapsed, long tick) {
        if (!type.equals(lastType)) {
            stage = null;            // Seq starts every step with stage null
            lastType = type;
        }
        return switch (type) {
            case "farmscan" -> scan(w);
            case "farmharvest" -> harvest(w, elapsed, tick);
            case "farmgather" -> gather(w, elapsed);
            case REPLANT -> replant(w, elapsed, tick);
            case "farmcompact" -> compact(w);
            case "farmdone" -> done(w);
            case "farmdeposit" -> deposit(w);
            default -> Tick.of("unknown farm step " + type);
        };
    }

    private Tick scan(FarmWorld w) {
        cropSpots = FarmRules.positions(FarmRules.farmCrops(w, farm.pos()));
        if (cropSpots.isEmpty()) return Tick.of("no crops at the farm (" + farm.fmt() + ") - PM \"farm here\" next to them");
        // the ripe ones are harvested and the rest left to grow
        unripe = FarmRules.cropsNow(w, cropSpots, false).size();
        return Tick.of("next");
    }
    private Tick harvest(FarmWorld w, long elapsed, long tick) {
        // one request to Baritone's hands per call (it walks, looks and clicks itself); the next waits until it ends
        List<Effect> fx = new ArrayList<>();
        boolean timedOut = elapsed > 20 * 60; // game fact: 20 ticks a second, one minute for the whole harvest
        if (!timedOut && w.handsBusy()) return new Tick("wait", fx, null, null);
        List<FarmRules.Crop> ripe = new ArrayList<>();
        for (FarmRules.Crop c : FarmRules.cropsNow(w, cropSpots, true)) if (clicks.getOrDefault(c.key(), 0) < 3) ripe.add(c);
        if (ripe.isEmpty() || timedOut) {
            if (!rightClick) {
                // vanilla: only the crops that are really gone count (a refused or missed break leaves a ripe crop)
                broken.entrySet().removeIf(e -> {
                    FarmRules.Crop c = FarmRules.cropAt(w, e.getValue()[0], e.getValue()[1], e.getValue()[2]);
                    return c != null && c.ripe();
                });
                harvested = broken.size();
            }
            return new Tick("next", fx, null, null);
        }
        double[] eye = w.eye();
        FarmRules.Crop near = null;
        double nearD = 0;
        for (FarmRules.Crop c : ripe) {
            Long at = clicked.get(c.key());
            if (at != null && tick - at < 20) continue;             // the server hasn't answered yet
            double dx = c.x() + 0.5 - eye[0], dy = c.y() + 0.5 - eye[1], dz = c.z() + 0.5 - eye[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (near == null || d < nearD) {
                near = c;
                nearD = d;
            }
        }
        if (near == null) return new Tick("wait", fx, null, null);
        String key = near.key();
        boolean first = clicked.get(key) == null;
        if (!rightClick) {
            // P5 vanilla: break it; the seed to put back is noted before the block is gone
            broken.put(key, near.pos());
            String seed = w.seedOf(near.x(), near.y(), near.z());
            if (seed != null) seeds.put(key, seed);
            fx.add(new Break(near.x(), near.y(), near.z()));
        } else {
            FarmRules.Hand hand = FarmRules.harmlessHand(w.slots(), w.selected());
            if (hand == null) return new Tick("I can't free my hand for the harvest (no sword, no empty slot) - give me a sword", fx, null, null);
            fx.add(new Use(near.x(), near.y(), near.z(), hand));
        }
        if (first) harvested++;
        clicked.put(key, tick);
        clicks.merge(key, 1, Integer::sum);
        return new Tick("wait", fx, label + " - harvesting (" + harvested + ")", null);
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

    /**
     * P5 vanilla: a seed back on each crop it broke (the drops were picked up in "farmgather"). A cell is settled when a
     * crop stands there again, its farmland is gone, it took 3 clicks, or the bot has no such seed. At most 60 s.
     */
    private Tick replant(FarmWorld w, long elapsed, long tick) {
        List<Effect> fx = new ArrayList<>();
        if (elapsed > 20 * 60) return Tick.of("next");   // game fact: 20 ticks a second, one minute
        if (w.handsBusy()) return Tick.of("wait");      // Baritone's hands have a request running: one at a time
        Map<String, Integer> inv = w.inventory();
        double[] eye = w.eye();
        int[] near = null;
        String nearKey = null, nearSeed = null;
        double nearD = 0;
        boolean waiting = false;
        for (Map.Entry<String, int[]> e : broken.entrySet()) {
            String key = e.getKey();
            if (settled.contains(key)) continue;
            int[] c = e.getValue();
            boolean clickedOnce = plantTries.containsKey(key);
            if (FarmRules.cropAt(w, c[0], c[1], c[2]) != null) {
                settled.add(key);
                if (clickedOnce) replanted++;
                continue;
            }
            if (!String.valueOf(w.blockId(c[0], c[1] - 1, c[2])).contains("farmland") || !w.isAir(c[0], c[1], c[2])) {
                settled.add(key);
                replantFailed++;
                continue;
            }
            Long at = plantAt.get(key);
            if (at != null && tick - at < 20) {
                waiting = true;           // the server hasn't answered yet
                continue;
            }
            if (plantTries.getOrDefault(key, 0) >= 3) {
                settled.add(key);
                replantFailed++;
                continue;
            }
            String seed = seeds.get(key);
            if (seed == null || inv.getOrDefault(seed, 0) <= 0) {
                settled.add(key);
                noSeed++;
                missingSeeds.add(seed == null ? "seed" : FarmPlant.cropName(seed) + " seeds");
                continue;
            }
            double dx = c[0] + 0.5 - eye[0], dy = c[1] - 0.5 - eye[1], dz = c[2] + 0.5 - eye[2];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (near == null || d < nearD) {
                near = c;
                nearKey = key;
                nearSeed = seed;
                nearD = d;
            }
        }
        if (near != null) {
            fx.add(new Plant(near[0], near[1] - 1, near[2], nearSeed));
            plantTries.merge(nearKey, 1, Integer::sum);
            plantAt.put(nearKey, tick);
            return new Tick("wait", fx, label + " - replanting", null);
        }
        return waiting ? Tick.of("wait") : Tick.of("next");
    }

    /** The vanilla round's words after the report: "; replanted 8" and what it lacked. */
    String replantNote() {
        if (rightClick) return "";
        return "; replanted " + replanted + (noSeed > 0 ? ", " + noSeed + " not (no " + String.join(", ", missingSeeds) + ")" : "")
                + (replantFailed > 0 ? ", " + replantFailed + " couldn't be replanted" : "");
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
        farmNote = report(harvested, unripe, gained, blocks, prud, gatherFull, dropsLeft) + replantNote();
        return new Tick("next", List.of(), null, farmNote);
    }

    /** The round's report, word for word the bridge's. */
    public static String report(int harvested, int unripe, int gained, int blocks, int prud, boolean gatherFull, int dropsLeft) {
        return "harvested " + harvested + " crops"
                + (unripe != 0 ? ", " + unripe + " still growing" : "")
                + ", +" + gained + " inferium essence"
                + (blocks > 0 ? "; made " + blocks + " inferium block" + (blocks > 1 ? "s" : "") : "")
                + (prud > 0 ? "; made " + prud + " prudentium essence" : "")
                + (gatherFull ? "; my inventory is full, so some drops stay on the ground" : dropsLeft != 0 ? "; " + dropsLeft + " drops I couldn't reach" : "");
    }

    private Tick deposit(FarmWorld w) {
        // nearly full (FARM_FREE free slots or fewer)? Put things away at the base chests (the walk teleports home
        // when that's far), so "repeat forever farm" never fills up. The next round walks back.
        if (FarmRules.freeSlots(w.slots()) > FarmRules.farmFree()) return Tick.of("next");
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
