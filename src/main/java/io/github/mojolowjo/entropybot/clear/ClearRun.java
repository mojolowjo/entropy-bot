package io.github.mojolowjo.entropybot.clear;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static io.github.mojolowjo.entropybot.clear.ClearRules.CLEAR_REACH;

/**
 * B7d D1: one careful clear, tick by tick (the bridge's stepClear with clearTorch, beginBreak, noTool, stockStonePicks,
 * clearBreak, clearCollect, clearMaybeDeposit, clearPlan, clearWalk and the settle pause). Game-free: what it does to
 * the world goes through {@link Body}, what it knows of the world through {@link ClearWorld}; the decisions are the
 * engine's ({@link ClearEngine}, {@link ClearGrid}, {@link Tools}). JUnit drives it over the sim's worlds.
 *
 * <p>A tick answers with an {@link Out}: RUN (carry on), END (the report, {@link ClearEngine#finishMessage}), or a trip
 * the caller has to run first: CRAFT (pickaxes: try the craft texts in order, then {@link #tripStarted} or
 * {@link #craftNotStarted}), STONE (3 stone pickaxes so the iron one is kept for ores: {@link #tripStarted}, or carry
 * on), DEPOSIT (the bag is full: {@link #tripStarted} or {@link #depositNotStarted}). When the trip is over the caller
 * calls {@link #resumed} with how it ended. A gap of more than 5 ticks between two ticks (a fight or a meal held the
 * job) makes it start over from where the bot stands, as the bridge did after "defending".
 */
public final class ClearRun {
    /** What the clear does to the game (the Minecraft side, or a test's fake). */
    public interface Body {
        Bot bot();

        long now();

        /**
         * holdBestTool: the cheapest tool that does the job in hand (the tool policy: "tools ores iron"); ok false when
         * the block needs a tool and none can harvest it. id: the item now held for it (null: nothing fits, fine without).
         * Sets job.lastPick when it chose a pickaxe.
         */
        Held hold(ClearJob job, Pos t);

        boolean hasPickaxe();

        /** A stone pickaxe could break the block as well (package B). */
        boolean stoneCanBreak(Pos t);

        /** "iron" (the default) or "cheapest". */
        String toolOres();

        /** Break progress per tick with what is held now. */
        double progress(Pos t);

        /** Look at the sight point and hit the block: the first hit starts breaking it, later ones carry on. */
        void hit(Pos t, ClearEngine.Sight s, boolean first);

        void stopBreaking();

        boolean haveTorches();

        /** Places a torch at t (with its place lease): "ok..." or why not. */
        String placeTorch(Pos t);

        void walkBlock(int x, int y, int z);

        void walkNear(int x, int y, int z, int r);

        boolean walkIdle();

        void cancelWalk();

        /** Item drops near the box (within 2 of it, 1.2..8 from the bot, never below minStandY), nearest first. */
        List<Drop> drops(ClearJob job);

        boolean dropAlive(Drop d);

        /** The bag has room for the drop (a free slot, or a stack of it with room). */
        boolean roomFor(Drop d);

        /** No free slot at all. */
        boolean bagFull();

        void status(String s);

        /** To whoever asked (nobody: nothing). */
        void whisper(String s);

        /** To whoever asked, else the owner. */
        void warnFull(String s);

        void log(String s);
    }

    public record Held(boolean ok, String id) {}

    public record Drop(String id, int x, int y, int z, double d) {}

    public enum Kind { RUN, END, CRAFT, STONE, DEPOSIT }

    /** A tick's answer: END has the report in text; CRAFT/STONE the craft texts to try in order and the whisper. */
    public record Out(Kind kind, String text, List<String> crafts) {
        public static final Out RUN = new Out(Kind.RUN, null, null);

        static Out end(String msg) { return new Out(Kind.END, msg, null); }
    }

    public static final String FULL_WARNING = "my inventory is full - the rest of the drops stay on the ground. PM \"stop\" if you want me to empty it first.";
    public static final String DEPOSIT_WHISPER = "inventory full - taking stuff to the base chests, then back to clearing";

    public final ClearWorld w;
    public final ClearJob job;
    private String phase = "pick";
    private Pos target;
    private ClearEngine.Sight sight;
    private boolean breaking, tFalling;
    private String tOreId;
    private int breakTicks, breakLimit;
    private Long lastBreakTick;
    private long phaseStart, lastTick = -1;
    // collect
    private Drop collectItem;
    private int collectCount;
    private final Set<String> triedDrops = new HashSet<>();
    // plan / walk
    private int approachTries;
    private Double approachDist;
    private ClearGrid.Spot spot;
    private double mx, my, mz;
    private long moveTick;
    // trips
    private String trip;
    private boolean noDeposit, fullWarned;
    private Integer lastDeposit;
    private String ended;

    public ClearRun(ClearWorld w, ClearJob job) {
        this.w = w;
        this.job = job;
    }

    public String phase() { return phase; }

    public String ended() { return ended; }

    /** "craft", "stone", "deposit" while a trip runs, else null. */
    public String trip() { return trip; }

    public boolean breaking() { return breaking; }

    /** One tick. */
    public Out tick(Body b) {
        if (ended != null) return Out.end(ended);
        long now = b.now();
        // a reflex (a fight, a meal) held the job: start over from wherever the bot is now
        if (lastTick >= 0 && now - lastTick > 5 && trip == null) interrupted(b);
        lastTick = now;
        switch (phase) {
            case "pick": return pick(b, now);
            case "break": return breakStep(b, now);
            case "collect": return collect(b, now);
            case "plan": return plan(b, now);
            case "walk": return walk(b, now);
            case "settle":
                if (now - phaseStart >= 30) phase = "pick";
                return Out.RUN;
            default:
                phase = "pick";
                return Out.RUN;
        }
    }

    /** A hold, or the job stopped: stop breaking, choose again from where it stands. */
    public void interrupted(Body b) {
        endBreak(b);
        if ("walk".equals(phase) || "collect".equals(phase)) b.cancelWalk();      // the walk it was on is stale now
        target = null;
        collectItem = null;
        phase = "pick";
    }

    // ---- trips ----

    /** The trip asked for has started (the caller spliced it in). */
    public void tripStarted(Kind k) {
        trip = k == Kind.DEPOSIT ? "deposit" : k == Kind.STONE ? "stone" : "craft";
    }

    /** No craft text started: the clear ends ({@link Tools#cantCraftMessage}). */
    public Out craftNotStarted(Body b, String lastError) {
        return end(b, ClearEngine.finishMessage(w, job, Tools.cantCraftMessage(lastError == null ? "" : lastError)));
    }

    /** The deposit could not start (no chests): never again for this clear. */
    public void depositNotStarted() {
        noDeposit = true;
    }

    /**
     * Back from a trip. how: null when it went fine, else how it ended (the craft's or the deposit's error). A trip
     * that ended "stopped..." ends the clear with that; a pickaxe craft that left it without a pickaxe ends it with
     * {@link Tools#craftFailedMessage}; a deposit that left the bag full turns the deposits off.
     */
    public Out resumed(Body b, String how) {
        String t = trip;
        trip = null;
        lastTick = b.now();
        target = null;
        phase = "pick";
        if (how != null && how.startsWith("stopped")) return end(b, how);
        if (("craft".equals(t) || "stone".equals(t)) && !b.hasPickaxe()) {
            return end(b, ClearEngine.finishMessage(w, job, Tools.craftFailedMessage(how)));
        }
        if ("deposit".equals(t) && b.bagFull()) noDeposit = true;
        return Out.RUN;
    }

    // ---- pick ----

    private Out pick(Body b, long now) {
        if (!job.torches.isEmpty()) {
            Pos t = ClearEngine.nextTorch(w, b.bot(), job, b.haveTorches());
            if (t != null && b.placeTorch(t).startsWith("ok")) {
                b.status(job.status("placing a torch"));
                return Out.RUN;
            }
        }
        ClearEngine.Target t = ClearEngine.pickReachable(w, b.bot(), job);
        if (t != null) return beginBreak(b, t.pos(), t.sight(), now);
        phase = "collect";
        collectCount = 0;
        collectItem = null;
        phaseStart = now;
        return Out.RUN;
    }

    private Out beginBreak(Body b, Pos t, ClearEngine.Sight s, long now) {
        phase = "pick";
        target = null;
        Held h = b.hold(job, t);
        if (!h.ok()) return noTool(b, t);
        // package B: stone pickaxes for stone, the iron one kept for ores (once per job)
        if (Tools.stonePicksFirst(h.id(), w.ore(t.x(), t.y(), t.z()), b.toolOres(), b.stoneCanBreak(t), job.stoneTried)) {
            job.stoneTried = true;
            b.cancelWalk();
            return new Out(Kind.STONE, Tools.stonePicksWhisper(h.id()), List.of(Tools.STONE_PICKS));
        }
        double pr = b.progress(t);
        if (ClearEngine.tooSlowToBreak(pr)) {
            job.skip.put(t.key(), ClearEngine.TOO_SLOW);
            return Out.RUN;
        }
        target = t;
        sight = s;
        phase = "break";
        breakTicks = 0;
        breakLimit = ClearEngine.breakLimit(pr);
        String name = w.name(t.x(), t.y(), t.z());
        tFalling = ClearRules.falling(name);
        tOreId = job.collect && w.ore(t.x(), t.y(), t.z()) ? w.id(t.x(), t.y(), t.z()) : null;
        b.status(job.status("breaking " + name + " at " + t.key()));
        return Out.RUN;
    }

    /** No tool can harvest it: skip it while a pickaxe is left, else craft pickaxes (twice), else stop. */
    private Out noTool(Body b, Pos t) {
        Tools.NoTool nt = Tools.noTool(job, b.hasPickaxe(), w.name(t.x(), t.y(), t.z()));
        if (nt.skip() != null) {
            job.skip.put(t.key(), nt.skip());
            return Out.RUN;
        }
        if (nt.stop() != null) return end(b, ClearEngine.finishMessage(w, job, nt.stop()));
        b.cancelWalk();
        return new Out(Kind.CRAFT, null, nt.craft());
    }

    // ---- break ----

    private Out breakStep(Body b, long now) {
        Pos t = target;
        if (!ClearEngine.clearable(w, job, t.x(), t.y(), t.z())) {
            breaking = false;
            lastBreakTick = now;
            if (ClearEngine.onBroken(job, t, tFalling, tOreId)) b.whisper(ClearEngine.milestoneText(job));
            target = null;
            phase = "pick";
            return Out.RUN;
        }
        if (breakTicks > breakLimit) {
            endBreak(b);
            ClearEngine.failTarget(job, t, ClearEngine.NEVER_FINISHED);
            target = null;
            phase = "pick";
            return Out.RUN;
        }
        if (breakTicks % 10 == 9) {
            // knocked back, or something moved in the way: choose again
            Bot bot = b.bot();
            double r = CLEAR_REACH + 0.5;
            ClearEngine.Sight s = ClearEngine.eyeDistSq(bot.x(), bot.eyeY(), bot.z(), t.x(), t.y(), t.z()) <= r * r ? ClearEngine.sightOf(w, bot, t.x(), t.y(), t.z()) : null;
            if (s == null) {
                endBreak(b);
                target = null;
                phase = "pick";
                return Out.RUN;
            }
            sight = s;
        }
        // like a player, pause 6 ticks after each block before starting the next (a lagging server may put it back)
        if (breakTicks == 0 && now - (lastBreakTick == null ? -100 : lastBreakTick) < 6) return Out.RUN;
        b.hit(t, sight, breakTicks == 0);
        breaking = true;
        breakTicks++;
        return Out.RUN;
    }

    private void endBreak(Body b) {
        if (breaking) {
            b.stopBreaking();
            breaking = false;
        }
    }

    // ---- collect: walk over the drops lying within 8 blocks (at most 10 per stop) ----

    private Out collect(Body b, long now) {
        long el = now - phaseStart;
        if (collectItem != null) {
            boolean gone = !b.dropAlive(collectItem);
            if (!gone && el < 80 && !(el > 15 && b.walkIdle())) return Out.RUN;
            if (!gone) b.cancelWalk();
            collectItem = null;
        }
        if (collectCount >= 10) {
            phase = "plan";
            return Out.RUN;
        }
        ClearGrid dg = null;
        int[] dd = null;
        for (Drop d : b.drops(job)) {
            if (triedDrops.contains(d.id())) continue;
            if (job.standOk != null) {
                // F: with the fence on, only drops it can walk to (a walk toward one it can't reach wanders off)
                if (dg == null) {
                    // only the part of the box near the bot (drops lie within 8 of it): a long box is never mapped whole
                    Bot bot = b.bot();
                    int bx = (int) Math.floor(bot.x()), bz = (int) Math.floor(bot.z());
                    ClearBox jb = job.box;
                    int x1 = Math.max(jb.x1(), bx - 10), x2 = Math.min(jb.x2(), bx + 10), z1 = Math.max(jb.z1(), bz - 10), z2 = Math.min(jb.z2(), bz + 10);
                    ClearBox near = x1 <= x2 && z1 <= z2 ? new ClearBox(x1, jb.y1(), z1, x2, jb.y2(), z2) : ClearBox.of(bx, jb.y1(), bz, bx, jb.y2(), bz);
                    dg = ClearGrid.build(w, near, bot, null);
                    dd = dg.walkDistances(bot);
                }
                int i = dg.idx(d.x(), d.y(), d.z());
                // not marked tried: one still falling may land where it can walk
                if (i < 0 || dd[i] < 0) continue;
            }
            if (!b.roomFor(d)) {
                Out o = maybeDeposit(b);
                if (o != null) return o;
                if (!fullWarned) {
                    fullWarned = true;
                    b.warnFull(FULL_WARNING);
                }
                continue;
            }
            triedDrops.add(d.id());
            collectCount++;
            collectItem = d;
            phaseStart = now;
            b.walkBlock(d.x(), d.y(), d.z());
            b.status(job.status("picking up drops"));
            return Out.RUN;
        }
        phase = "plan";
        return Out.RUN;
    }

    /** Full bag mid-clear: a trip to the chests, at most once per 50 blocks, never again once the chests were full too. */
    private Out maybeDeposit(Body b) {
        if (noDeposit || job.broken - (lastDeposit == null ? -1000 : lastDeposit) < 50) return null;
        lastDeposit = job.broken;
        endBreak(b);
        b.cancelWalk();
        return new Out(Kind.DEPOSIT, DEPOSIT_WHISPER, null);
    }

    // ---- plan ----

    private Out plan(Body b, long now) {
        if (b.bagFull()) {
            Out o = maybeDeposit(b);
            if (o != null) return o;
        }
        Bot bot = b.bot();
        ClearBox box = job.box;
        double far = box.distTo(bot.x(), bot.y(), bot.z());
        if (far <= 24) {
            approachTries = 0;
            approachDist = null;
        } else {
            // far away (a long tunnel, or back from a chest or table trip): walk over while each walk gets closer
            if (approachTries >= 5 || (approachDist != null && far > approachDist - 2)) {
                return end(b, ClearEngine.finishMessage(w, job, "stopped: couldn't get there (" + Math.round(far) + " blocks away)"));
            }
            approachTries++;
            approachDist = far;
            b.walkNear(Math.floorDiv(box.x1() + box.x2(), 2), box.y1(), Math.floorDiv(box.z1() + box.z2(), 2),
                    Math.max(4, Math.min(box.x2() - box.x1(), box.z2() - box.z1()) / 2));
            target = null;
            spot = null;
            startWalk(bot, now);
            b.status(job.status("walking to the zone"));
            return Out.RUN;
        }
        if (job.consecFails >= ClearEngine.MAX_CONSEC_FAILS) return end(b, ClearEngine.finishMessage(w, job, ClearEngine.STUCK + stuckWhy(bot)));
        ClearGrid.Plan p = ClearGrid.planWalk(w, bot, job);
        if (p == null && ClearEngine.retryRound(job)) p = ClearGrid.planWalk(w, bot, job);
        if (p == null && now - (lastBreakTick == null ? -1000 : lastBreakTick) < 30) {
            // just broke something: give the server a moment to put back a break it refused, then look again
            phase = "settle";
            phaseStart = now;
            b.status(job.status("checking the blocks stayed broken"));
            return Out.RUN;
        }
        if (p == null) return end(b, ClearEngine.finishMessage(w, job, null));
        b.walkBlock(p.spot().x(), p.spot().y(), p.spot().z());
        target = p.target();
        spot = p.spot();
        startWalk(bot, now);
        b.status(job.status("walking to " + p.spot().key() + " to reach " + p.target().key()));
        return Out.RUN;
    }

    /** S1: the stuck end names the cause when it is that no walkable spot sees into the box ("" otherwise, or on any trouble). */
    private String stuckWhy(Bot bot) {
        try {
            return ClearGrid.entryProblem(w, bot, job);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void startWalk(Bot bot, long now) {
        phase = "walk";
        phaseStart = now;
        mx = bot.x();
        my = bot.y();
        mz = bot.z();
        moveTick = now;
    }

    // ---- walk: break the chosen block as soon as it is in sight; give up on it if the walk fails ----

    private Out walk(Body b, long now) {
        long el = now - phaseStart;
        Pos t = target;
        if (t != null && !ClearEngine.clearable(w, job, t.x(), t.y(), t.z())) {
            b.cancelWalk();
            target = null;
            phase = "pick";
            return Out.RUN;
        }
        if (t != null && el > 0 && el % 10 == 0) {
            ClearEngine.Sight s = inSight(b.bot(), t);
            if (s != null && ClearEngine.breakHazard(w, b.bot(), job, t.x(), t.y(), t.z()) == null) {
                b.cancelWalk();
                return beginBreak(b, t, s, now);
            }
        }
        if (el < 20) return Out.RUN;
        boolean idle = b.walkIdle(), stuck = false;
        Bot bot = b.bot();
        if (!idle && now - moveTick >= 160) {
            // hasn't moved half a block in 8 seconds
            stuck = Math.abs(bot.x() - mx) + Math.abs(bot.y() - my) + Math.abs(bot.z() - mz) < 0.5;
            mx = bot.x();
            my = bot.y();
            mz = bot.z();
            moveTick = now;
        }
        // long walks (to a far box) get more time than walks to a spot next to a block
        if (!idle && !stuck && el < 20L * (t != null ? 30 : 120)) return Out.RUN;
        if (!idle) b.cancelWalk();
        phase = "pick";
        target = null;
        if (t == null) return Out.RUN;
        ClearGrid.Spot sp = spot;
        if (sp != null && Math.abs(bot.x() - sp.x() - 0.5) + Math.abs(bot.z() - sp.z() - 0.5) + Math.abs(bot.y() - sp.y()) > 1.5) {
            job.badSpots.put(sp.key(), job.broken);       // didn't get there: not that spot again for a while
        }
        ClearEngine.Sight s = inSight(bot, t);
        if (s != null && ClearEngine.breakHazard(w, bot, job, t.x(), t.y(), t.z()) == null) return beginBreak(b, t, s, now);
        ClearEngine.failTarget(job, t, ClearEngine.COULD_NOT_REACH);
        return Out.RUN;
    }

    private ClearEngine.Sight inSight(Bot bot, Pos t) {
        return ClearEngine.eyeDistSq(bot.x(), bot.eyeY(), bot.z(), t.x(), t.y(), t.z()) <= CLEAR_REACH * CLEAR_REACH ? ClearEngine.sightOf(w, bot, t.x(), t.y(), t.z()) : null;
    }

    // ---- the end ----

    private Out end(Body b, String msg) {
        endBreak(b);
        ended = msg;
        return Out.end(msg);
    }

    /** The job was stopped from outside (stop, an error): the report as it stands, prefix e.g. "stopped". */
    public String stop(Body b) {
        endBreak(b);
        if (ended == null) ended = "stopped";
        return ended;
    }
}
