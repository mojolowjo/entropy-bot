package io.github.mojolowjo.entropybot.farm;

import io.github.mojolowjo.entropybot.craft.Crafter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives a farm round the way commands/Seq would (a step every 2 ticks, "ok: done label; note" / "error: why (while
 * label)"), with a tiny game around the FakeWorld: walks teleport, Squat Grow ages crops next to a crouching bot,
 * a right-click on a ripe crop replants it and drops essence, the bot picks up what it touches, crafting goes through
 * the stub Crafter and a deposit empties the bag of everything but swords.
 */
final class FarmSim {
    final FakeWorld w;
    final Crafter crafter;
    long tick = 1000;
    boolean sneak;
    int twerkTicks, twerkOut, crafts, lateDrops;
    /** Every right-click: {age before, held item id}. */
    final List<Object[]> clicks = new ArrayList<>();
    final List<String> placed = new ArrayList<>();
    final List<Object[]> pendingDrops = new ArrayList<>();    // {due tick, x, y, z, count}

    FarmSim(FakeWorld w, Crafter crafter) {
        this.w = w;
        this.crafter = crafter;
    }

    void worldTick() {
        tick++;
        int[] me = w.here();
        if (sneak) {
            twerkTicks++;
            boolean out = false;
            for (Map.Entry<String, int[]> e : w.ages.entrySet()) {
                String[] c = e.getKey().split(" ");
                int cx = Integer.parseInt(c[0]), cy = Integer.parseInt(c[1]), cz = Integer.parseInt(c[2]);
                boolean in = Math.abs(cx - me[0]) <= 3 && Math.abs(cy - me[1]) <= 3 && Math.abs(cz - me[2]) <= 3;
                if (!in) out = true;
                else if (tick % 5 == 0 && e.getValue()[0] < e.getValue()[1]) e.getValue()[0]++;
            }
            if (out) twerkOut++;
        }
        for (int i = pendingDrops.size() - 1; i >= 0; i--) {
            Object[] d = pendingDrops.get(i);
            if ((long) d[0] <= tick) {
                w.drop(FarmRules.FARM_ESS, (double) d[1], (double) d[2], (double) d[3], (int) d[4]);
                pendingDrops.remove(i);
            }
        }
        for (FakeWorld.LiveDrop d : w.live) {
            if (!d.alive) continue;
            if (Math.abs(d.x - w.x) <= 1.3 && Math.abs(d.z - w.z) <= 1.3 && d.y - w.y >= -1 && d.y - w.y <= 2.3
                    && w.roomFor(new FarmWorld.Drop(d.key, d.item, d.x, d.y, d.z))) {
                w.add(d.item, d.count);
                d.alive = false;
            }
        }
    }

    void teleport(FarmRound.Walk g) {
        if (g.range() == 0) {
            if (w.standable(g.x(), g.y(), g.z())) w.at(g.x() + 0.5, g.y(), g.z() + 0.5);
            return;
        }
        int[] best = null;
        long bestD = 0;
        int r = g.range();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            if (dx * dx + dy * dy + dz * dz > r * r) continue;
            int[] c = {g.x() + dx, g.y() + dy, g.z() + dz};
            if (!w.standable(c[0], c[1], c[2])) continue;
            long d = FarmRules.distSq(c, w.here());
            if (best == null || d < bestD) {
                best = c;
                bestD = d;
            }
        }
        if (best != null) w.at(best[0] + 0.5, best[1], best[2] + 0.5);
    }

    void use(FarmRound.Use u) {
        FarmRules.Hand h = u.hand();
        if (h.kind().equals("select")) w.selected = h.slot();
        else if (h.kind().equals("swap")) {
            String id = w.ids[h.slot()];
            int n = w.counts[h.slot()];
            w.ids[h.slot()] = w.ids[w.selected];
            w.counts[h.slot()] = w.counts[w.selected];
            w.ids[w.selected] = id;
            w.counts[w.selected] = n;
        }
        String held = w.ids[w.selected];
        int[] a = w.ages.get(FakeWorld.k(u.x(), u.y(), u.z()));
        clicks.add(new Object[]{a == null ? -1 : a[0], held.isEmpty() ? null : held});
        if (!FarmRules.harmless(held)) {
            placed.add(held + " at " + u.x() + " " + u.y() + " " + u.z());
            return;
        }
        if (a != null && a[0] >= a[1]) {
            a[0] = 0;
            pendingDrops.add(new Object[]{tick + lateDrops, u.x() + 0.5, u.y() + 0.2, u.z() + 0.5, 1 + Math.abs(u.x() + u.z()) % 2});
        }
    }

    /** Runs a round to its end; the job's final status line ("ok: done farming; ..." or "error: ..."). */
    String run(FarmCommand.Start start, int maxTicks) {
        FarmRound round = start.round();
        List<PlanStep> steps = new ArrayList<>(start.steps());
        String label = start.label(), note = null;
        int idx = 0;
        long stepStart = tick;
        for (int t = 0; t < maxTicks; t++) {
            worldTick();
            if (tick % 2 != 0) continue;
            if (idx >= steps.size()) return "ok: done " + label + (note != null ? "; " + note : "");
            PlanStep st = steps.get(idx);
            String r;
            switch (st.type()) {
                case "walk" -> {
                    teleport(new FarmRound.Walk(st.pos()[0], st.pos()[1], st.pos()[2], st.near() ? 2 : 0));
                    r = "next";
                }
                case "craftitem" -> {
                    Crafter.Plan plan = crafter.plan(st.item(), st.n(), w.inventory());
                    if (!plan.ok()) r = st.optional() ? "next" : plan.error();
                    else {
                        for (Crafter.Step s : plan.steps()) {
                            Crafter.Craft c = (Crafter.Craft) s;
                            Crafter.Recipe rec = crafter.craftingRecipesFor(c.item()).get(0);
                            for (Crafter.Need need : rec.needs()) w.remove(need.alts().get(0), need.amount() * c.times());
                            w.add(c.item(), c.times() * rec.outCount());
                        }
                        crafts++;
                        r = "next";
                    }
                }
                case "deposit" -> {
                    int moved = 0;
                    for (int i = 0; i < 36; i++) {
                        if (w.ids[i].isEmpty() || w.ids[i].endsWith("_sword")) continue;
                        moved += w.counts[i];
                        w.give(i, "", 0);
                    }
                    note = "put away " + moved + " items";
                    r = "next";
                }
                case "farmnote" -> {
                    note = round.noteAfterDeposit(note);
                    r = "next";
                }
                default -> {
                    FarmRound.Tick tk = round.step(st.type(), w, tick - stepStart, tick);
                    for (FarmRound.Effect fx : tk.effects()) {
                        if (fx instanceof FarmRound.Walk g) teleport(g);
                        else if (fx instanceof FarmRound.Sneak s) sneak = s.down();
                        else if (fx instanceof FarmRound.Use u) use(u);
                        else if (fx instanceof FarmRound.Craft c) steps.add(idx + 1, PlanStep.craft(c.item(), c.n(), c.optional()));
                        else if (fx instanceof FarmRound.Deposit) {
                            steps.add(idx + 1, PlanStep.of("farmnote"));
                            steps.add(idx + 1, PlanStep.of("deposit"));
                        }
                    }
                    if (tk.note() != null) note = tk.note();
                    r = tk.result();
                }
            }
            if (r.equals("wait")) continue;
            if (r.equals("next")) {
                idx++;
                stepStart = tick;
                continue;
            }
            sneak = false;
            return "error: " + r + " (while " + label + ")";
        }
        return "timeout at step " + idx + " " + steps;
    }
}
