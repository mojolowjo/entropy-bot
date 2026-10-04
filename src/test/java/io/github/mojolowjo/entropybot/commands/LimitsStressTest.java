package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.mojolowjo.entropybot.cave.Caves;
import io.github.mojolowjo.entropybot.events.EventRing;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import io.github.mojolowjo.entropybot.io.BotFiles;
import io.github.mojolowjo.entropybot.memory.Knowledge;
import io.github.mojolowjo.entropybot.poi.Pois;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Package H (2026-10-03): the measurements behind {@code memory/Limits}. Each test pushes one store well past its
 * cap and prints how long the work the game does with it takes (per tick, per file write, per command) at each size,
 * so the caps can be set just below the point where it gets slow. Measurement only (no asserts); tagged "stress" and
 * left out of the default run: {@code gradlew test -Pstress --tests "*LimitsStress*"}. The caps themselves stop the
 * stores from reaching these sizes, so the stress fills them through the raw paths where it has to.
 *
 * <p>Budgets used to read the numbers (the client tick is 50 ms; a frame at 60 fps is 16 ms): work done every tick or
 * every few ticks under 0.5 ms; a file written on the tick thread under 5 ms; a command's answer under 10 ms;
 * state.json (written every second, read by bridge.ps1 and the dashboard) under 64 KB.
 */
@Tag("stress")
class LimitsStressTest {
    static final String OW = "minecraft:overworld";

    @TempDir
    Path dir;

    /** Median of {@code runs} timings in ms, after 3 warm-ups. */
    static double ms(int runs, Runnable r) {
        for (int i = 0; i < 3; i++) r.run();
        double[] t = new double[runs];
        for (int i = 0; i < runs; i++) {
            long a = System.nanoTime();
            r.run();
            t[i] = (System.nanoTime() - a) / 1e6;
        }
        Arrays.sort(t);
        return t[runs / 2];
    }

    static void row(String item, int n, String what, double ms) {
        System.out.printf("H| %-10s | %7d | %-34s | %9.3f ms%n", item, n, what, ms);
    }

    static void rowKb(String item, int n, String what, int bytes) {
        System.out.printf("H| %-10s | %7d | %-34s | %9.1f KB%n", item, n, what, bytes / 1024.0);
    }

    static JsonObject chestNote(int i, int kinds) {
        JsonObject items = new JsonObject();
        for (int k = 0; k < kinds; k++) items.addProperty("minecraft:item_number_" + k, 1 + (i + k) % 64);
        JsonObject o = new JsonObject();
        o.addProperty("dim", OW);
        o.add("items", items);
        o.addProperty("seen", 1_700_000_000_000L + i);
        return o;
    }

    @Test
    void knowledgePlacesAndChests() {
        // Knowledge keeps at most Limits.CHESTS notes now, so the raw cost is measured on the same JSON it writes:
        // chests.json + places.json (n of each) as flush() writes them, and the whole set as one JSON text
        BotFiles f = new BotFiles(dir);
        for (int n : new int[]{100, 500, 1000, 2000, 5000, 10000}) {
            JsonObject pl = new JsonObject(), cs = new JsonObject();
            for (int i = 0; i < n; i++) {
                JsonObject p = new JsonObject();
                p.addProperty("x", i);
                p.addProperty("y", 64);
                p.addProperty("z", -i);
                p.addProperty("dim", OW);
                pl.add("place_" + i, p);
                cs.add(i + " 64 " + (-i), chestNote(i, 12));
            }
            JsonObject all = new JsonObject();
            all.add("places", pl);
            all.add("chests", cs);
            row("chests", n, "flush (places + chests files)", ms(7, () -> {
                f.writeJson("places.json", pl.toString());
                f.writeJson("chests.json", cs.toString());
            }));
            row("chests", n, "toJson (the whole set)", ms(7, all::toString));
            rowKb("chests", n, "chests.json + places.json size", all.toString().length());
        }
        // and the store itself at its cap: a put past it prunes the oldest
        Knowledge k = new Knowledge();
        k.load(new BotFiles(dir.resolve("kn")));
        JsonObject cs = new JsonObject();
        for (int i = 0; i < 2 * io.github.mojolowjo.entropybot.memory.Limits.CHESTS; i++) cs.add(i + " 64 0", chestNote(i, 12));
        JsonObject ch = new JsonObject();
        ch.add("chests", cs);
        k.put(ch.toString(), 1);
        final int[] tick = {10};
        row("chests", k.chests().size(), "put one note at the cap (prunes)", ms(15, () -> k.put("{\"chests\":{\"x " + tick[0] + "\":" + chestNote(1_000_000 + tick[0]++, 12) + "}}", tick[0])));
        row("chests", k.chests().size(), "flush at the cap (3 files)", ms(7, k::flush));
    }

    @Test
    void pointsOfInterest() {
        for (int n : new int[]{500, 1000, 2000, 5000, 20000}) {
            Pois p = new Pois();
            p.load(new BotFiles(dir.resolve("poi" + n)));
            // raw fill: Pois.MAX drops the oldest past its cap, so fill with distinct kinds far apart and read the size
            for (int i = 0; i < n; i++) p.saw("kind" + (i % 7), i * 100, 0, 0, OW, i, i, null);
            int size = p.size();
            final int[] i = {0};
            row("pois", size, "saw() a known point (scan, per chunk)", ms(15, () -> p.saw("kind0", 0, 0, 0, OW, 1, 1, null)));
            row("pois", size, "saw() a new point (whole list)", ms(15, () -> p.saw("new", -999999 - i[0]++ * 100, 0, 0, OW, 1, 1, null)));
            row("pois", size, "write pois.json", ms(7, () -> new BotFiles(dir).writeJson("pois.json", p.toJson().toString())));
            rowKb("pois", size, "pois.json size", p.toJson().toString().length());
        }
    }

    @Test
    void caves() {
        // one cave with more and more cells (the per-cave cap), then many caves (the file holds every cave's cells).
        // The cells are added to the sets directly (visit() stops at the caps, pick() prunes whole caves).
        for (int cells : new int[]{5000, 10000, 20000, 40000, 80000}) {
            Caves c = new Caves();
            c.load(new BotFiles(dir.resolve("cave" + cells)));
            Caves.Cave cv = c.pick(null, OW, 0, 0, 0, 1, 1);
            for (long i = 0; i < cells; i++) cv.visited.add(i * 7919);
            row("cave cells", cells, "write caves.json (every 5 s caving)", ms(7, () -> new BotFiles(dir).writeJson("caves.json", c.toJson(true).toString())));
            rowKb("cave cells", cells, "caves.json size", c.toJson(true).toString().length());
        }
        // many caves of 2000 cells: caves.json in the shape toJson(true) writes (the store prunes past its caps, so the
        // file is built here)
        for (int n : new int[]{10, 20, 50, 100, 200}) {
            JsonObject cs = new JsonObject();
            for (int k = 0; k < n; k++) {
                JsonObject cv = new JsonObject();
                cv.addProperty("dim", OW);
                JsonArray v = new JsonArray();
                for (long i = 0; i < 2000; i++) v.add(i * 7919 + k);
                cv.add("visited", v);
                cs.add("cave_" + k, cv);
            }
            JsonObject file = new JsonObject();
            file.add("caves", cs);
            row("caves", n, "write caves.json, 2000 cells each", ms(5, () -> new BotFiles(dir).writeJson("caves.json", file.toString())));
            rowKb("caves", n, "caves.json size, 2000 cells each", file.toString().length());
        }
        // the store: caves keep coming, each visited for 2000 cells; it stays at its caps
        Caves c = new Caves();
        c.load(new BotFiles(dir.resolve("capped")));
        long t0 = System.nanoTime();
        for (int k = 0; k < 200; k++) {
            Caves.Cave cv = c.pick(null, OW, k * 1000, 0, 0, k, k);
            for (int s = 0; s < 80; s++) c.visit(cv, k * 1000 + (s % 20) * 9, (s / 20) * 9, 0, k, k);
            for (long i = 0; cv.visited.size() < 2000; i++) cv.visited.add(i * 7919 + k * 13L);
            c.visit(cv, k * 1000, 0, 0, k, k);
        }
        row("caves", 200, "200 caves picked + visited (prunes)", (System.nanoTime() - t0) / 1e6);
        int[] cells = {0};
        int caves = c.toJson(false).getAsJsonObject("caves").size();
        c.toJson(false).getAsJsonObject("caves").entrySet().forEach(e -> cells[0] += e.getValue().getAsJsonObject().get("explored").getAsInt());
        rowKb("caves", caves, "caves.json at the caps (" + cells[0] + " cells)", c.toJson(true).toString().length());
        row("caves", caves, "write caves.json at the caps", ms(5, () -> new BotFiles(dir).writeJson("caves.json", c.toJson(true).toString())));
        row("caves", caves, "pick() at the caps", ms(15, () -> c.pick(null, OW, 5, 0, 5, 1, 1)));
    }

    @Test
    void areasAndProtectBoxes() {
        // Baritone's A* asks checkBoxes for every node it considers (thousands per path): the per-call cost matters
        for (int n : new int[]{1, 8, 32, 64, 128, 512, 2048}) {
            List<Box> areas = new ArrayList<>(), protect = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                areas.add(new Box("a" + i, OW, i * 3000, -64, 0, i * 3000 + 500, 320, 500));
                protect.add(new Box("p" + i, OW, i * 3000 + 100, -64, 100, i * 3000 + 120, 100, 120));
            }
            GuardCore g = new GuardCore();
            g.setPolicy(new Policy(areas, protect));
            // the worst case: a spot in the last area (every box looked at)
            int x = (n - 1) * 3000 + 300;
            row("areas+prot", n, "100k checkBoxes go (one long path)", ms(7, () -> {
                for (int i = 0; i < 100_000; i++) g.checkBoxes(OW, x, 64, i % 500, "go");
            }));
            JsonObject a = new JsonObject();
            a.add("areas", g.policy().toJson().getAsJsonArray("areas"));
            a.add("protect", g.policy().toJson().getAsJsonArray("protect"));
            rowKb("areas+prot", n, "state.json settings areas+protect", SettingsBlock.build(new JsonObject(), a, live()).toString().length());
        }
    }

    static SettingsBlock.Live live() {
        return new SettingsBlock.Live(true, "strict", true, true, null, null, null);
    }

    @Test
    void routinesRulesAndChainSteps() {
        for (int n : new int[]{10, 50, 100, 200, 500, 2000}) {
            JsonObject mem = new JsonObject();
            CommandsTest.Fake f = CommandsTest.fake(mem);
            JsonObject routines = f.chains.routines();
            // raw fill (the cap refuses past it): n routines of 8 steps each
            for (int i = 0; i < n; i++) routines.addProperty("r" + i, "say a then wait 1 then deposit then restock then farm then say b then wait 2 then base");
            JsonArray rules = f.chains.rules();
            for (int i = 0; i < n; i++) {
                JsonObject r = new JsonObject();
                r.addProperty("kind", "every");
                r.addProperty("arg", (1000 + i) + "m");
                r.addProperty("text", "farm then deposit");
                r.addProperty("last", f.now);
                rules.add(r);
            }
            row("routines", n, "routines list (PM reply)", ms(15, () -> f.chains.routineCommand("")));
            System.out.printf("H| %-10s | %7d | %-34s | %9d parts%n", "routines", n, "whisper parts of \"routines\"", Texts.whisperParts(f.chains.routineCommand("")).size());
            System.out.printf("H| %-10s | %7d | %-34s | %9d parts%n", "rules", n, "whisper parts of \"rules\"", Texts.whisperParts(f.chains.ruleCommand("")).size());
            row("rules", n, "rulesTick (every 100 ticks)", ms(15, f.chains::rulesTick));
            rowKb("routines", n, "state.json settings block", SettingsBlock.build(mem, new JsonObject(), live()).toString().length());
            row("routines", n, "settings block build (every 1 s)", ms(15, () -> SettingsBlock.build(mem, new JsonObject(), live()).toString()));
            JsonStore s = new JsonStore("commands" + n + ".json");
            BotFiles bf = new BotFiles(dir);
            s.load(bf);
            s.data().add("routines", routines);
            s.data().add("rules", rules);
            row("routines", n, "write commands.json", ms(7, s::flush));
            rowKb("routines", n, "commands.json size", s.data().toString().length());
        }
        // a chain: steps after expansion. Nested routines (depth < 3) multiply: 3 levels of k steps = k^3 steps
        for (int k : new int[]{4, 5, 10, 20, 40}) {
            JsonObject mem = new JsonObject();
            CommandsTest.Fake f = CommandsTest.fake(mem);
            JsonObject routines = f.chains.routines();
            routines.addProperty("c", String.join(" then ", java.util.Collections.nCopies(k, "say x")));
            routines.addProperty("b", String.join(" then ", java.util.Collections.nCopies(k, "c")));
            routines.addProperty("a", String.join(" then ", java.util.Collections.nCopies(k, "b")));
            int steps = f.chains.expandSteps(List.of("a"), 0).size();
            row("steps", steps, "expand the routines", ms(7, () -> f.chains.expandSteps(List.of("a"), 0)));
            rowKb("steps", steps, "the steps joined (old \"started\")", String.join(" > ", f.chains.expandSteps(List.of("a"), 0)).length());
            rowKb("steps", steps, "\"started\" reply now", f.chains.startChain("owner", "a", "a", 1).length());
        }
    }

    @Test
    void ringsAndLogs() {
        EventRing ring = new EventRing();
        for (int i = 0; i < 10_000; i++) ring.push("job", "an event with some text " + i, null);
        row("events", EventRing.CAPACITY, "since(0, all) (a full read)", ms(15, () -> ring.since(0, 0)));
        rowKb("events", EventRing.CAPACITY, "since(0, all) size", ring.since(0, 0).length());
        // the autominer log: 20 decisions with a 1000-char result each, in commands.json
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        JsonObject a = new JsonObject();
        JsonArray log = new JsonArray();
        for (int i = 0; i < 20; i++) {
            JsonObject e = new JsonObject();
            e.addProperty("at", f.now);
            e.addProperty("what", "mine strip any 32");
            e.addProperty("why", "my mine is ready");
            e.addProperty("result", "x".repeat(1000));
            log.add(e);
        }
        a.add("log", log);
        a.addProperty("on", true);
        mem.add("autominer", a);
        row("autominer", 20, "why (5 decisions)", ms(15, f.chains::whyCommand));
        System.out.printf("H| %-10s | %7d | %-34s | %9d parts%n", "autominer", 20, "whisper parts of \"why\"", Texts.whisperParts(f.chains.whyCommand()).size());
    }
}
