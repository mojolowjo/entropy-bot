package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Package A (the overnight fixes, 2026-10-03): the autominer keeps an iron pickaxe in its supplies, "why" shows the
 * latest decision whole on a line of its own, a strip mine that gave up means caving (not a 30-minute pause), a death
 * mid-run waits for the corpse trip, and a 10-round night is never idle longer than one decision.
 */
class AutominerNightTest {

    /** The jobs, played by the test: every step starts at once and ends `dur` ticks later with outcome(text). */
    static final class Bridge {
        final CommandsTest.Fake f;
        final List<String> ran = new ArrayList<>();
        long id = -1, endAt = -1;
        String text;
        java.util.function.Function<String, String> outcome;

        Bridge(CommandsTest.Fake f, java.util.function.Function<String, String> outcome) {
            this.f = f;
            this.outcome = outcome;
        }

        void tick(long dur) {
            if (f.taken < f.opened.size()) {
                var r = f.takeNext();
                id = r.id;
                text = r.text;
                ran.add(text);
                endAt = f.tick + dur;
            }
            if (id >= 0 && f.tick >= endAt) {
                long done = id;
                id = -1;
                f.link.done(done, outcome.apply(text));
            }
        }
    }

    /** One game tick of the command core: chains every 5, the death policy every 20, the autominer every 100. */
    static void tick(CommandsTest.Fake f, Bridge b, long dur) {
        f.tick++;
        f.now += 50;
        b.tick(dur);
        if (f.tick % 5 == 3) f.chains.stepChain();
        if (f.tick % 20 == 5) f.chains.deathTick(false);
        if (f.tick % 100 == 55) f.chains.autominerTick();
    }

    @Test
    void autominerOnKeepsAnIronPickaxeAndRestocksIt() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        String r = f.chains.autominerCommand("on");
        assertTrue(r.endsWith("; I keep 1 iron_pickaxe in my supplies now"), r);
        assertEquals(1, mem.getAsJsonObject("supplies").get("minecraft:iron_pickaxe").getAsInt());
        f.supplies = mem.getAsJsonObject("supplies");
        f.tick = 1000;
        f.chains.autominerTick();
        assertEquals("autominer", f.chains.name());
        assertTrue(f.chains.whyCommand().endsWith("latest: 0s ago: restock because iron_pickaxe is short of my supplies"), f.chains.whyCommand());
        // the owner's own count stays: switching on again adds nothing
        mem.getAsJsonObject("supplies").addProperty("minecraft:iron_pickaxe", 2);
        assertFalse(f.chains.autominerCommand("on").contains("I keep"));
        assertEquals(2, mem.getAsJsonObject("supplies").get("minecraft:iron_pickaxe").getAsInt());
    }

    @Test
    void anAutominerAlreadyOnGetsTheDefaultOnce() {
        JsonObject mem = JsonParser.parseString("{\"autominer\":{\"on\":true,\"log\":[],\"pausedUntil\":0},\"supplies\":{\"minecraft:torch\":64}}").getAsJsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        f.tick = 1000;
        f.chains.autominerTick();
        assertEquals(1, mem.getAsJsonObject("supplies").get("minecraft:iron_pickaxe").getAsInt());
        mem.getAsJsonObject("supplies").remove("minecraft:iron_pickaxe");      // the owner takes it out again
        f.chains.clear();
        f.tick += 300;
        f.chains.autominerTick();
        assertFalse(mem.getAsJsonObject("supplies").has("minecraft:iron_pickaxe"), "added once only");
    }

    @Test
    void whyShowsTheLatestWholeOnItsOwnLine() {
        String longResult = "stopped at step 1 (mine strip any 32): stopped: the mine corridor is blocked - blocked:tool deepslate_redstone_ore at -32 -54 194 needs iron"
                + " - I have no iron pickaxe, none in my chests and I couldn't make one (missing 3 iron_ingot) (while strip mining branch 57)";
        JsonObject mem = JsonParser.parseString("{\"autominer\":{\"on\":true,\"pausedUntil\":0,\"log\":["
                + "{\"at\":999000000,\"what\":\"deposit\",\"why\":\"my bag is nearly full (2 free slots)\",\"result\":\"done\"},"
                + "{\"at\":999900000,\"what\":\"mine strip any 32\",\"why\":\"my mine at 1 2 3 is ready\",\"result\":\"" + longResult + "\"}]}}").getAsJsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        String why = f.chains.whyCommand();
        String[] lines = why.split("\n");
        assertEquals(2, lines.length, why);
        assertTrue(lines[0].startsWith("17m ago: deposit because"), lines[0]);
        assertEquals("latest: 2m ago: mine strip any 32 because my mine at 1 2 3 is ready -> " + longResult, lines[1]);
        List<String> parts = Texts.whisperParts(why);
        assertTrue(parts.get(1).startsWith("latest: "), "the latest line starts a whisper of its own: " + parts);
        assertEquals(lines[1], String.join(" ", parts.subList(1, parts.size())), "and nothing of it is lost");
        assertTrue(f.chains.autominerCommand("status").startsWith("autominer is on - last: 2m ago: mine strip any 32 because"), f.chains.autominerCommand("status"));
        assertEquals("autominer is on - last: 2m ago: mine strip any 32 because my mine at 1 2 3 is ready -> " + longResult, f.chains.autominerCommand(""));
        JsonObject st = f.chains.autominerState();
        assertTrue(st.get("on").getAsBoolean() && st.get("last").getAsString().endsWith(longResult), st.toString());
        assertNull(Texts.guestRefusal("autominer", "status", "autominer status", "owner"), "a guest may ask");
    }

    @Test
    void aMineThatGaveUpMeansCavingNotAPause() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        f.chains.autominerCommand("on");
        f.mine = JsonParser.parseString("{\"x\":5,\"y\":-54,\"z\":5,\"dir\":\"west\"}").getAsJsonObject();
        Bridge b = new Bridge(f, t -> t.startsWith("mine strip")
                ? "stopped: the mine corridor is blocked - blocked:area at 1 -54 5 (that box is not inside one of my areas) - and the mine can't turn - south at ..."
                : "ok: done caving in cave_1");
        for (int i = 0; i < 2000; i++) tick(f, b, 50);
        assertEquals("mine strip any 32", b.ran.get(0));
        assertEquals("mine cave any 32 20m", b.ran.get(1), "after the mine gave up: caving " + b.ran);
        assertTrue(f.chains.whyCommand().contains("because my mine could not go on (stopped: the mine corridor is blocked - blocked:area"), f.chains.whyCommand());
        assertEquals(0, mem.getAsJsonObject("autominer").get("pausedUntil").getAsLong(), "no pause for the mine");
        // a decision that keeps failing for another reason still pauses
        assertTrue(Chains.stripGaveUp(JsonParser.parseString("{\"what\":\"mine strip any 32\",\"result\":\"stopped at step 1 (x): stuck - 8 tries\"}").getAsJsonObject()));
        assertFalse(Chains.stripGaveUp(JsonParser.parseString("{\"what\":\"mine strip any 32\",\"result\":\"done - branch 7 right skipped (blocked:area ...)\"}").getAsJsonObject()),
                "a run that went fine (a skipped branch) is no give-up");
    }

    @Test
    void aNightOfTenRoundsIsNeverIdleLongerThanOneDecision() {
        JsonObject mem = new JsonObject();
        CommandsTest.Fake f = CommandsTest.fake(mem);
        f.chains.autominerCommand("on");
        f.supplies = mem.getAsJsonObject("supplies");
        f.mine = JsonParser.parseString("{\"x\":5,\"y\":-54,\"z\":5,\"dir\":\"west\"}").getAsJsonObject();
        mem.add("lastDeath", JsonParser.parseString("{\"x\":5,\"y\":-54,\"z\":5,\"dim\":\"minecraft:overworld\"}"));
        int[] strips = {0};
        boolean[] died = {false};
        Bridge b = new Bridge(f, t -> {
            if (t.equals("restock")) {
                f.inv.put("minecraft:iron_pickaxe", 1);
                return "ok: done restocking iron_pickaxe; supplies now: iron_pickaxe 1/1";
            }
            if (t.equals("deposit")) {
                f.free = 20;
                return "ok: done putting away cobblestone (2 chests)";
            }
            if (t.equals("death")) return "ok: got my things back";
            if (t.startsWith("mine strip")) {
                strips[0]++;
                if (strips[0] == 1) return "ok: done strip mining for any (32) at mine; the corridor was blocked (blocked:area at 5 -54 2 (...)): mine turned north at 5 -54 5";
                if (strips[0] == 3) return "ok: done strip mining for any (32) at mine; fetched an iron pickaxe for the deepslate_redstone_ore at 1 -54 5";
                if (strips[0] == 4) return "ok: done strip mining for any (32) at mine; mined 3 ores in 2 runs";
                return "ok: done strip mining for any (32) at mine; mined 2 ores in 2 runs";
            }
            return "ok: done " + t;
        });
        long idle = 0, maxIdle = 0;
        for (int i = 0; i < 40000 && autominerLog(mem) < 11; i++) {
            tick(f, b, 300);
            // the second strip run: the bot dies half way (the chain is set aside, the job ends)
            if (!died[0] && strips[0] == 1 && b.id >= 0 && b.text.startsWith("mine strip") && f.tick >= b.endAt - 150) {
                died[0] = true;
                f.inv.remove("minecraft:iron_pickaxe");
                f.chains.noteDeath();
                long d = b.id;
                b.id = -1;
                f.link.done(d, "stopped: the bot died");
            }
            if (autominerLog(mem) == 6 && f.free == 20 && !f.chains.running()) f.free = 2;     // the bag fills up once
            boolean busy = f.chains.running() || f.busy() || autominerLog(mem) == 0;
            idle = busy ? 0 : idle + 1;
            maxIdle = Math.max(maxIdle, idle);
        }
        List<String> what = new ArrayList<>();
        mem.getAsJsonObject("autominer").getAsJsonArray("log").forEach(e -> what.add(e.getAsJsonObject().get("what").getAsString()));
        assertTrue(what.size() >= 10, what.toString());
        assertEquals("restock", what.get(0), "the iron pickaxe first: " + what);
        assertTrue(what.contains("deposit"), "a full bag is put away: " + what);
        assertTrue(maxIdle <= Chains.AUTOMINER_TICKS + 100, "idle at most one decision, was " + maxIdle);
        assertEquals(0, mem.getAsJsonObject("autominer").get("pausedUntil").getAsLong(), "never paused");
        assertTrue(f.whispers.contains("back from my corpse - carrying on with autominer"), f.whispers.toString());
        assertTrue(b.ran.contains("death"), "the corpse trip: " + b.ran);
        int iDeath = b.ran.indexOf("death");
        assertTrue(b.ran.get(iDeath + 1).startsWith("mine strip"), "after the corpse the cut-short run goes on: " + b.ran);
        assertFalse(f.whispers.stream().anyMatch(w -> w.contains("failed twice")), f.whispers.toString());
    }

    static int autominerLog(JsonObject mem) {
        JsonObject a = mem.getAsJsonObject("autominer");
        return a == null || !a.has("log") ? 0 : a.getAsJsonArray("log").size();
    }

    @Test
    void aMineStartsWhereTheBotCanStand() {
        MineSpot.Cell air = new MineSpot.Cell("air", false, false), stone = new MineSpot.Cell("stone", true, false), water = new MineSpot.Cell("water", false, true);
        assertNull(MineSpot.standReason(air, air, true, "1 2 3"));
        assertEquals("I can't stand at -118 -54 260 (deepslate)", MineSpot.standReason(new MineSpot.Cell("deepslate", true, false), air, true, "-118 -54 260"));
        assertEquals("I can't stand at 1 2 3 (stone above it)", MineSpot.standReason(air, stone, true, "1 2 3"));
        assertEquals("I can't stand at 1 2 3 (water)", MineSpot.standReason(water, air, true, "1 2 3"));
        assertEquals("there is nothing to stand on at 1 2 3", MineSpot.standReason(air, air, false, "1 2 3"));
        assertEquals("error: I won't start a mine at -118 -54 260: I can't stand at -118 -54 260 (deepslate) - mark it where I can stand and walk to",
                MineSpot.refusal("-118 -54 260", "I can't stand at -118 -54 260 (deepslate)"));
        assertEquals("stone", MineSpot.blockName("block.minecraft.stone"));
        assertEquals("oritech:nickel_ore", MineSpot.blockName("block.oritech.nickel_ore"));
    }
}
