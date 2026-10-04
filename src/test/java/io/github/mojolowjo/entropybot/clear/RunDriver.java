package io.github.mojolowjo.entropybot.clear;

import java.util.ArrayList;
import java.util.List;

/**
 * B7d D1: drives the real tick loop ({@link ClearRun}) over a {@link FakeWorld} with ClearDriver's physics (the bot
 * falls while nothing solid is under it, a walk to a standable spot arrives at once, any other walk fails where it
 * stands, a hit always breaks the block, tools wear out, a crafter answers the pickaxe crafts). A break the server takes
 * back ("flaky") comes back two ticks later, after the clear saw it gone, as in the sim. Drops, fights and deposits are
 * not modelled (no drops, the bag never fills).
 */
class RunDriver extends ClearDriver implements ClearRun.Body {
    long tick;
    final List<String> whispers = new ArrayList<>();
    final List<String> statuses = new ArrayList<>();
    private final List<Object[]> due = new ArrayList<>();
    /** what the clear's trips did: "craft minecraft:stone_pickaxe 3", "stone ...", "deposit" */
    final List<String> trips = new ArrayList<>();
    ClearRun run;
    /** called once per tick before the clear (e.g. a test that stops the job mid-way); true = stop now */
    java.util.function.LongPredicate stopAt = t -> false;
    String stoppedWith;

    RunDriver(FakeWorld w, Bot start) {
        super(w, start);
    }

    @Override
    RunDriver simInventory(int pickDur) {
        super.simInventory(pickDur);
        return this;
    }

    @Override
    String run(ClearJob job) {
        run = new ClearRun(w, job);
        for (int n = 0; n < 3_000_000; n++, tick++) {
            for (int i = due.size() - 1; i >= 0; i--) {
                if (tick >= (Long) due.get(i)[0]) {
                    w.set((Pos) due.get(i)[1], (String) due.get(i)[2]);
                    due.remove(i);
                }
            }
            fall();
            if (stopAt.test(tick)) {
                stoppedWith = run.stop(this);
                return stoppedWith;
            }
            ClearRun.Out o = run.tick(this);
            switch (o.kind()) {
                case END:
                    return o.text();
                case CRAFT:
                case STONE: {
                    String r = "";
                    boolean started = false;
                    for (String craft : o.crafts()) {
                        r = crafter.apply(craft);
                        crafts.add(craft + " -> " + r);
                        if (r.startsWith("started")) {
                            String id = craft.replaceFirst(" \\d+$", "");
                            for (int k = 0; k < 3; k++) {
                                for (int i = 0; i < 36; i++) if (items[i] == null) { items[i] = new Item(id, 4, craftedDur); break; }
                            }
                            started = true;
                            break;
                        }
                    }
                    trips.add((o.kind() == ClearRun.Kind.STONE ? "stone " : "craft ") + String.join(",", o.crafts()) + (started ? "" : " (failed)"));
                    if (started) {
                        run.tripStarted(o.kind());
                        tick++;
                        ClearRun.Out back = run.resumed(this, null);
                        if (back.kind() == ClearRun.Kind.END) return back.text();
                    } else if (o.kind() == ClearRun.Kind.CRAFT) {
                        return run.craftNotStarted(this, r).text();
                    }
                    break;
                }
                case DEPOSIT:
                    trips.add("deposit (failed)");
                    run.depositNotStarted();
                    break;
                default:
                    break;
            }
        }
        throw new IllegalStateException("the clear never ended: " + lastStatus);
    }

    // ---- ClearRun.Body ----

    @Override public Bot bot() { return Bot.at(x, y, z); }

    @Override public long now() { return tick; }

    @Override public ClearRun.Held hold(ClearJob job, Pos t) {
        String name = w.get(t);
        boolean need = NEEDS_TOOL.contains(name);
        List<Tools.Slot> slots = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            Item it = items[i];
            if (it == null) continue;
            slots.add(new Tools.Slot(i, it.id, correctTool(it, name), toolSpeed(it, name)));
        }
        int best = Tools.choose(slots, need, w.ore(t.x(), t.y(), t.z()), toolOres());
        if (best < 0) return new ClearRun.Held(Tools.okWithout(need), null);
        String id = items[best].id;
        if (Tools.isPickaxe(id)) job.lastPick = id;
        if (best < 9) selected = best;
        else {
            Item tmp = items[selected];
            items[selected] = items[best];
            items[best] = tmp;
        }
        return new ClearRun.Held(true, id);
    }

    @Override public boolean hasPickaxe() { return super.hasPickaxe(); }

    @Override public boolean stoneCanBreak(Pos t) {
        String n = w.get(t);
        return n.equals("stone") || n.equals("iron_ore");
    }

    @Override public String toolOres() { return "cheapest"; }

    @Override public double progress(Pos t) { return progress(w.get(t)); }

    @Override public void hit(Pos t, ClearEngine.Sight s, boolean first) {
        String name = w.get(t);
        if (name.equals("air")) return;
        if (flakyOnce.remove(t.key())) due.add(new Object[]{tick + 2, t, name});
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
    }

    @Override public void stopBreaking() {}

    @Override public boolean haveTorches() { return torches > 0; }

    @Override public String placeTorch(Pos t) {
        w.set(t, "torch");
        torches--;
        placed.add("torch@" + t.key());
        return "ok: SUCCESS";
    }

    @Override public void walkBlock(int gx, int gy, int gz) { walkTo(gx, gy, gz); }

    @Override public void walkNear(int gx, int gy, int gz, int r) {
        walks++;
        walkFails++;
    }

    @Override public boolean walkIdle() { return true; }

    @Override public void cancelWalk() {}

    @Override public List<ClearRun.Drop> drops(ClearJob job) { return List.of(); }

    @Override public boolean dropAlive(ClearRun.Drop d) { return false; }

    @Override public boolean roomFor(ClearRun.Drop d) { return true; }

    @Override public boolean bagFull() { return false; }

    @Override public void status(String s) {
        lastStatus = s;
        if (statuses.size() < 50) statuses.add(s);
    }

    @Override public void whisper(String s) { whispers.add(s); }

    @Override public void warnFull(String s) { whispers.add(s); }

    @Override public void log(String s) {}
}
