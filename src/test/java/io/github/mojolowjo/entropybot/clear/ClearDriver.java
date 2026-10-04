package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * An offline stand-in for the bridge's clear job loop (stepClear: pick, break, plan, walk) over a {@link FakeWorld},
 * with the simulator's physics: the bot falls while nothing solid is under it, a walk to a standable spot arrives
 * (teleports), any other walk fails where it stands, and a break always finishes. Tools and their durability, the
 * pickaxe crafting of noTool, torches, and a break the server takes back once ("flaky") are modelled like the sim.
 * Drops, inventory trips, fights and Baritone's own pathing are not.
 */
class ClearDriver {
    static final Set<String> NEEDS_TOOL = Set.of("stone", "iron_ore");

    static final class Item {
        final String id;
        final float speed;
        int dur;
        int count = 1;

        Item(String id, float speed, int dur) { this.id = id; this.speed = speed; this.dur = dur; }
    }

    final FakeWorld w;
    double x, y, z;
    final Item[] items = new Item[41];
    int selected;
    int torches;
    /** what crafting "id n" replies ("started: ..." adds 3 of the id) */
    Function<String, String> crafter = t -> "error: I can't craft that here";
    int craftedDur = 131;
    final Set<String> flakyOnce = new HashSet<>();
    final List<Pos> restoreAt = new ArrayList<>();
    final List<String> restoreName = new ArrayList<>();
    final OreBook.Simple ores = new OreBook.Simple();

    int toolBreaks, walks, walkFails, steps;
    double minY = Double.MAX_VALUE;
    final List<String> brokenOrder = new ArrayList<>();
    final List<String> placed = new ArrayList<>();
    final List<String> crafts = new ArrayList<>();
    String lastStatus;

    ClearDriver(FakeWorld w, Bot start) {
        this.w = w;
        moveTo(start.x(), start.y(), start.z());
    }

    /** The sim's starting inventory: two copper pickaxes, a copper shovel, a copper sword, cobblestone. */
    ClearDriver simInventory(int pickDur) {
        items[3] = new Item("leafscopperbackport:copper_pickaxe", 5, pickDur);
        items[20] = new Item("leafscopperbackport:copper_pickaxe", 5, pickDur);
        items[5] = new Item("leafscopperbackport:copper_shovel", 5, 100000);
        items[6] = new Item("leafscopperbackport:copper_sword", 1, 100000);
        Item cobble = new Item("minecraft:cobblestone", 1, -1);
        cobble.count = 64;
        items[1] = cobble;
        return this;
    }

    Bot bot() {
        return Bot.at(x, y, z);
    }

    void moveTo(double nx, double ny, double nz) {
        x = nx; y = ny; z = nz;
        minY = Math.min(minY, y);
    }

    /** The sim's physics: fall while there's nothing under the feet. */
    void fall() {
        for (int i = 0; i < 64; i++) {
            if (!FakeWorld.NONSOLID.contains(w.get((int) Math.floor(x), (int) Math.floor(y - 0.01), (int) Math.floor(z)))) break;
            moveTo(x, y - 1, z);
        }
    }

    /** The sim's Baritone GoalBlock: arrive when the spot is standable, else fail where it stands. */
    boolean walkTo(int gx, int gy, int gz) {
        walks++;
        if (w.standable(gx, gy, gz)) {
            moveTo(gx + 0.5, gy, gz + 0.5);
            return true;
        }
        walkFails++;
        return false;
    }

    void restoreFlaky() {
        for (int i = 0; i < restoreAt.size(); i++) w.set(restoreAt.get(i), restoreName.get(i));
        restoreAt.clear();
        restoreName.clear();
    }

    // ---- tools (the sim's toolSpeed / correctTool / getDestroyProgress) ----

    static float toolSpeed(Item it, String name) {
        if (it == null) return 1;
        if (it.id.endsWith("_pickaxe") && (name.equals("stone") || name.equals("iron_ore"))) return it.speed;
        if (it.id.endsWith("_shovel") && (name.equals("dirt") || name.equals("grass_block") || name.equals("gravel"))) return it.speed;
        return 1;
    }

    static boolean correctTool(Item it, String name) {
        if (it == null) return false;
        if (name.equals("stone")) return it.id.endsWith("_pickaxe");
        if (name.equals("iron_ore")) return it.id.matches(".*(stone|copper|iron|diamond)_pickaxe$");
        return false;
    }

    double progress(String name) {
        double h = FakeWorld.hardness(name);
        if (h == 0) return 1;
        if (h < 0) return 0;
        Item it = items[selected];
        boolean ok = !NEEDS_TOOL.contains(name) || correctTool(it, name);
        return toolSpeed(it, name) / h / (ok ? 30 : 100);
    }

    boolean hasPickaxe() {
        for (int i = 0; i < 36; i++) if (items[i] != null && Tools.isPickaxe(items[i].id)) return true;
        return false;
    }

    /** holdBestTool: false when the block needs a tool it doesn't have. */
    boolean holdBestTool(ClearJob job, String name) {
        boolean need = NEEDS_TOOL.contains(name);
        List<Tools.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            Item it = items[i];
            if (it == null) continue;
            slots.add(new Tools.Slot(i, it.id, correctTool(it, name), toolSpeed(it, name)));
        }
        int best = Tools.choose(slots, need);
        if (best < 0) return Tools.okWithout(need);
        if (Tools.isPickaxe(items[best].id)) job.lastPick = items[best].id;
        if (best < 9) selected = best;
        else {
            Item t = items[selected];
            items[selected] = items[best];
            items[best] = t;
        }
        return true;
    }

    // ---- the job loop ----

    /** Runs a clear to its end and returns the report (finishClear's message). */
    String run(ClearJob job) {
        boolean brokeSinceSettle = false;
        for (steps = 0; steps < 200000; steps++) {
            restoreFlaky();
            fall();
            // pick
            if (!job.torches.isEmpty()) {
                Pos t = ClearEngine.nextTorch(w, bot(), job, torches > 0);
                if (t != null) {
                    w.set(t, "torch");
                    torches--;
                    placed.add("torch@" + t.key());
                    lastStatus = job.status("placing a torch");
                    continue;
                }
            }
            ClearEngine.Target c = ClearEngine.pickReachable(w, bot(), job);
            if (c != null) {
                String end = breakIt(job, c.pos());
                if (end != null) return end;
                brokeSinceSettle = true;
                continue;
            }
            // plan
            if (job.box.distTo(x, y, z) > 24) throw new IllegalStateException("the driver doesn't walk to far boxes");
            if (job.consecFails >= ClearEngine.MAX_CONSEC_FAILS) return ClearEngine.finishMessage(w, job, ClearEngine.STUCK);
            ClearGrid.Plan p = ClearGrid.planWalk(w, bot(), job);
            if (p == null && ClearEngine.retryRound(job)) p = ClearGrid.planWalk(w, bot(), job);
            if (p == null && brokeSinceSettle) {
                // "checking the blocks stayed broken": the server may put back a break it refused
                brokeSinceSettle = false;
                continue;
            }
            if (p == null) return ClearEngine.finishMessage(w, job, null);
            lastStatus = job.status("walking to " + p.spot().key() + " to reach " + p.target().key());
            // walk
            walkTo(p.spot().x(), p.spot().y(), p.spot().z());
            fall();
            ClearGrid.Spot sp = p.spot();
            if (Math.abs(x - sp.x() - 0.5) + Math.abs(z - sp.z() - 0.5) + Math.abs(y - sp.y()) > 1.5) job.badSpots.put(sp.key(), job.broken);
            // arrived (or gave up walking): break it if it's in sight, else skip it for a while
            Pos t = p.target();
            Bot b = bot();
            ClearEngine.Sight s = ClearEngine.eyeDistSq(b.x(), b.eyeY(), b.z(), t.x(), t.y(), t.z()) <= ClearRules.CLEAR_REACH * ClearRules.CLEAR_REACH
                    ? ClearEngine.sightOf(w, b, t.x(), t.y(), t.z()) : null;
            if (s != null && ClearEngine.breakHazard(w, b, job, t.x(), t.y(), t.z()) == null) {
                String end = breakIt(job, t);
                if (end != null) return end;
                brokeSinceSettle = true;
            } else {
                ClearEngine.failTarget(job, t, ClearEngine.COULD_NOT_REACH);
            }
        }
        throw new IllegalStateException("the clear never ended: " + lastStatus);
    }

    /** beginBreak + clearBreak (the break always finishes). Returns the job's report when it ends here. */
    String breakIt(ClearJob job, Pos t) {
        String name = w.get(t);
        if (!holdBestTool(job, name)) {
            Tools.NoTool nt = Tools.noTool(job, hasPickaxe(), name);
            if (nt.skip() != null) {
                job.skip.put(t.key(), nt.skip());
                return null;
            }
            if (nt.stop() != null) return ClearEngine.finishMessage(w, job, nt.stop());
            String r = "";
            for (String craft : nt.craft()) {
                r = crafter.apply(craft);
                crafts.add(craft + " -> " + r);
                if (r.startsWith("started")) {
                    String id = craft.replaceFirst(" \\d+$", "");
                    for (int n = 0; n < 3; n++) {
                        for (int i = 0; i < 36; i++) if (items[i] == null) { items[i] = new Item(id, 4, craftedDur); break; }
                    }
                    if (!hasPickaxe()) return ClearEngine.finishMessage(w, job, Tools.craftFailedMessage(r));
                    return null;
                }
            }
            return ClearEngine.finishMessage(w, job, Tools.cantCraftMessage(r));
        }
        double pr = progress(name);
        if (ClearEngine.tooSlowToBreak(pr)) {
            job.skip.put(t.key(), ClearEngine.TOO_SLOW);
            return null;
        }
        boolean falling = ClearRules.falling(name);
        String oreId = job.collect && w.ore(t.x(), t.y(), t.z()) ? w.id(t.x(), t.y(), t.z()) : null;
        lastStatus = job.status("breaking " + name + " at " + t.key());
        // the block goes (the sim's breakBlockAt)
        if (flakyOnce.remove(t.key())) {
            restoreAt.add(t);
            restoreName.add(name);
        }
        w.set(t, "air");
        Item it = items[selected];
        if (it != null && it.dur >= 0 && it.id.matches(".*_(pickaxe|shovel)$")) {
            it.dur--;
            if (it.dur <= 0) {
                items[selected] = null;
                toolBreaks++;
            }
        }
        brokenOrder.add(t.key());
        ClearEngine.onBroken(job, t, falling, oreId);
        return null;
    }

    /** startClear + the run, with the driver's ore book. */
    String clear(ClearJob.Options o) {
        return run(ClearJob.start(o, SimWorlds.ZONE, ores));
    }

    /** The seq "place" step: stand at from (when given), place when the cell is free; optional steps fail quietly. */
    boolean place(String block, Pos pos, Pos from) {
        if (w.get(pos).equals(block)) return true;
        if (from != null) walkTo(from.x(), from.y(), from.z());
        if (!FakeWorld.NONSOLID.contains(w.get(pos))) return false;
        w.set(pos, block);
        placed.add(block + "@" + pos.key());
        return true;
    }
}
