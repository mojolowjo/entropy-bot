package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * B7e F: {@link RunDriver} with walks that only arrive where the bot can really walk (ClearGrid.walkDistances from where
 * it stands; any other walk fails where it stands and is counted: a "wild" walk, Baritone's partial path in the game),
 * plus the floor fill's body: a bag of blocks and placements that need a clickable neighbour in reach and never go into
 * the bot's own body.
 */
class FillDriver extends RunDriver implements FloorFill.Run.Body {
    final Map<String, Integer> bag = new HashMap<>();
    final List<Pos> placedCells = new ArrayList<>();
    int wildWalks;
    /** review 2: time each fill tick (the decision, without the driver's walk physics) */
    boolean timed;
    long worstTickNanos, walkNanos;
    String worstWhat;
    double maxZ = -Double.MAX_VALUE, minZ = Double.MAX_VALUE;

    FillDriver(FakeWorld w, Bot start) {
        super(w, start);
    }

    @Override
    FillDriver simInventory(int pickDur) {
        super.simInventory(pickDur);
        return this;
    }

    @Override
    void moveTo(double nx, double ny, double nz) {
        super.moveTo(nx, ny, nz);
        maxZ = Math.max(maxZ, nz);
        minZ = Math.min(minZ, nz);
    }

    /** A walk arrives only when the bot can walk there without breaking or placing. */
    @Override public void walkBlock(int gx, int gy, int gz) {
        long t0 = System.nanoTime();
        try {
            walkPhysics(gx, gy, gz);
        } finally {
            walkNanos += System.nanoTime() - t0;
        }
    }

    private void walkPhysics(int gx, int gy, int gz) {
        walks++;
        Bot b = bot();
        ClearBox around = ClearBox.of((int) Math.floor(b.x()), (int) Math.floor(b.y()), (int) Math.floor(b.z()), gx, gy, gz);
        ClearGrid g = ClearGrid.build(w, around, b, null);
        int[] d = g.walkDistances(b);
        int i = g.idx(gx, gy, gz);
        if (i >= 0 && d[i] >= 0) {
            moveTo(gx + 0.5, gy, gz + 0.5);
            return;
        }
        walkFails++;
        wildWalks++;
    }

    @Override public Map<String, Integer> inventory() { return bag; }

    @Override public String place(Pos c, String id) {
        if (bag.getOrDefault(id, 0) <= 0) return "error: I have no " + id;
        if (!FloorFill.fillable(w, c.x(), c.y(), c.z())) return "error: something is in the way at " + c.key();
        if (FloorFill.bodyHits(x, y, z, c)) return "error: I'm standing in it";
        if (PlaceRules.placeSide(w, c.x(), c.y(), c.z(), x, y + Bot.EYE, z) == null) return "error: nothing in reach to place against at " + c.key();
        w.set(c, ClearRules.blockName(id));
        bag.merge(id, -1, Integer::sum);
        placedCells.add(c);
        return "ok: SUCCESS";
    }

    /** Runs a fill to its end. */
    FloorFill.Run fill(FloorFill.Run r) {
        for (int n = 0; n < 400_000; n++, tick++) {
            fall();
            walkNanos = 0;
            String before = r.phase() + " at " + bot();
            long t0 = timed ? System.nanoTime() : 0;
            boolean end = r.tick(this);
            // the decision alone: the driver's own walk physics (its path check) doesn't count
            long dt = System.nanoTime() - t0 - walkNanos;
            if (timed && dt > worstTickNanos) {
                worstTickNanos = dt;
                worstWhat = before + " -> " + r.phase();
            }
            if (end) return r;
        }
        throw new IllegalStateException("the fill never ended: " + lastStatus + " / " + r.phase());
    }
}
