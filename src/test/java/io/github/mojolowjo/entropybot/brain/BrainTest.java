package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** B1: the brain on fake states (docs/BRAIN_PLAN.md 4.9). */
class BrainTest {

    // ---- fakes ----

    static final class MemSink implements DecisionLog.Sink {
        final Map<String, StringBuilder> files = new LinkedHashMap<>();
        boolean fail;

        @Override public void append(String day, String line) throws IOException {
            if (fail) throw new IOException("disk full");
            files.computeIfAbsent(day, d -> new StringBuilder()).append(line).append('\n');
        }

        @Override public long size(String day) { return files.containsKey(day) ? files.get(day).length() : 0; }

        @Override public List<String> days() { return new ArrayList<>(files.keySet()); }

        @Override public void delete(String day) { files.remove(day); }

        List<JsonObject> lines() {
            List<JsonObject> out = new ArrayList<>();
            for (StringBuilder b : files.values()) for (String l : b.toString().split("\n")) if (!l.isEmpty()) out.add(JsonParser.parseString(l).getAsJsonObject());
            return out;
        }
    }

    static final class Fake implements BrainEnv {
        long now = 1_790_000_000_000L, tick;
        BrainState state = new BrainState();
        Consumer<BrainState> shape = s -> {};
        final List<String> started = new ArrayList<>(), whispers = new ArrayList<>(), stopped = new ArrayList<>();
        boolean running;
        String lastEnd, startReply;
        final JsonObject store = new JsonObject();
        JsonObject written;
        final MemSink sink = new MemSink();
        final DecisionLog log = new DecisionLog(sink, ZoneId.of("UTC"));

        @Override public long now() { return now; }
        @Override public long tick() { return tick; }

        @Override public BrainState sense() {
            BrainState s = state;
            state = copyOf(s);
            shape.accept(s);
            return s;
        }

        @Override public String start(String chain) {
            started.add(chain);
            if (startReply != null) return startReply;
            running = true;
            return "started: brain - " + chain;
        }

        @Override public boolean jobRunning() { return running; }
        @Override public String lastEnd() { return lastEnd; }
        @Override public void stopJob(String why) { running = false; stopped.add(why); }
        @Override public void whisper(String text) { whispers.add(text); }
        @Override public void log(String line) {}
        @Override public JsonObject store() { return store; }
        @Override public void saved() {}
        @Override public void writeState(JsonObject o) { written = o; }
        @Override public DecisionLog decisions() { return log; }

        /** The job ends with this message. */
        void end(String msg) {
            running = false;
            lastEnd = msg;
        }

        void loop() {
            now += 2000;
            tick += 40;
        }
    }

    /** A fresh state each loop with the same fields (the fake senses "the same world"). */
    static BrainState copyOf(BrainState a) {
        BrainState s = new BrainState();
        s.inWorld = a.inWorld; s.menuOpen = a.menuOpen; s.parked = a.parked; s.health = a.health; s.maxHealth = a.maxHealth;
        s.food = a.food; s.foodItems = a.foodItems; s.torches = a.torches; s.freeSlots = a.freeSlots; s.stage = a.stage;
        s.danger = a.danger; s.dangerWhy = a.dangerWhy; s.night = a.night; s.sleepAuto = a.sleepAuto; s.othersSleeping = a.othersSleeping;
        s.litHere = a.litHere; s.botPos = a.botPos; s.ownerPos = a.ownerPos; s.basePos = a.basePos; s.ownerOnline = a.ownerOnline; s.released = a.released;
        s.ownerJob = a.ownerJob; s.passive = a.passive; s.pickaxes = a.pickaxes; s.pickPct = a.pickPct; s.pickDurability = a.pickDurability;
        s.toolBroke = a.toolBroke; s.needs.addAll(a.needs); s.goals.addAll(a.goals); s.copy = a.copy; s.upkeep = a.upkeep;
        return s;
    }

    static Fake on() {
        Fake f = new Fake();
        f.state.upkeep.now = f.now;
        f.state.upkeep.mine = new int[]{10, 40, 10};
        f.state.upkeep.mineReady = true;
        assertTrue(new Brain(f).command("on").startsWith("ok: brain on"));
        return f;
    }

    static BrainTree.Decision decide(BrainState s) { return decide(s, null); }

    static BrainTree.Decision decide(BrainState s, BrainTree.Running r) {
        BrainConfig c = BrainConfig.defaults();
        Needs.Scored sc = Needs.score(s, c, IdleList.DEFAULT, Map.of());
        return new BrainTree().run(new BrainTree.Ctx(s, c, sc, r, false));
    }

    static BrainState calm() {
        BrainState s = new BrainState();
        s.upkeep.mine = new int[]{10, 40, 10};
        s.upkeep.mineReady = true;
        return s;
    }

    // ---- the tree's order ----

    @Test
    void eachBranchIsReachedByTheStatesMeantForIt() {
        record Case(String branch, Consumer<BrainState> set, BrainTree.Running running) {}
        BrainTree.Running r = new BrainTree.Running("upkeep", "mine strip any 32", 15);
        List<Case> cases = List.of(
                new Case("off", s -> s.inWorld = false, null),
                new Case("off", s -> s.parked = true, null),
                new Case("off", s -> { s.menuOpen = true; s.danger = true; }, null),
                new Case("danger", s -> s.danger = true, null),
                new Case("danger", s -> { s.danger = true; s.ownerJob = "dig"; }, r),
                new Case("danger", s -> s.health = 5, null),
                new Case("owner", s -> s.ownerJob = "dig", null),
                new Case("owner", s -> { s.ownerJob = "dig"; s.passive = "escorting x"; }, null),
                new Case("passive", s -> s.passive = "escorting mojo", null),
                new Case("passive", s -> { s.passive = "defending"; s.freeSlots = 0; }, null),
                new Case("job.keep", s -> {}, r),
                new Case("job.interrupt", s -> s.food = 5, r),
                new Case("job.interrupt", s -> s.freeSlots = 0, r),
                new Case("job.interrupt", s -> { s.toolBroke = true; s.pickaxes = 0; }, r),
                new Case("job.keep", s -> s.night = true, r),
                new Case("job.switch", s -> s.needs.add(new BrainState.NeedItem("minecraft:torch", 32, 0, 0)), r),
                new Case("night.sleep", s -> { s.night = true; s.othersSleeping = true; }, null),
                new Case("night.light", s -> { s.night = true; s.litHere = false; }, null),
                new Case("night.light", s -> { s.night = true; s.litHere = false; s.sleepAuto = false; s.othersSleeping = true; }, null),
                new Case("pick", s -> s.night = true, null),                        // night, lit, nobody sleeps: work on
                new Case("pick", s -> {}, null),
                new Case("pick", s -> s.freeSlots = 3, null),
                new Case("pick", s -> s.foodItems = 0, null),
                new Case("pick", s -> s.upkeep = noUpkeep(), null));               // caving is always runnable (idle: the next test)
        for (Case c : cases) {
            BrainState s = calm();
            c.set().accept(s);
            assertEquals(c.branch(), decide(s, c.running()).branch(), "case " + c.branch() + " " + cases.indexOf(c));
        }
    }

    static IdleList.Facts noUpkeep() {
        IdleList.Facts f = new IdleList.Facts();
        f.mine = null;
        return f;
    }

    @Test
    void idleWhenTheIdleListHasNothingRunnable() {
        BrainState s = calm();
        BrainConfig c = BrainConfig.defaults();
        Needs.Scored sc = Needs.score(s, c, List.of("farm"), Map.of());
        BrainTree.Decision d = new BrainTree().run(new BrainTree.Ctx(s, c, sc, null, false));
        assertEquals("idle", d.branch());
        BrainState far = calm();
        far.ownerOnline = true;
        far.ownerPos = new int[]{100, 64, 0};
        sc = Needs.score(far, c, List.of("farm"), Map.of());
        assertEquals("idle.near", new BrainTree().run(new BrainTree.Ctx(far, c, sc, null, false)).branch());
        assertEquals("come", new BrainTree().run(new BrainTree.Ctx(far, c, sc, null, false)).chain());
    }

    @Test
    void theTreeDescribesEveryNodeForTheVisiblePage() {
        List<String[]> d = new BrainTree().describe();
        List<String> ids = new ArrayList<>();
        for (String[] n : d) ids.add(n[1]);
        for (String id : List.of("root", "off", "danger", "owner", "passive", "job", "job.interrupt", "job.switch", "job.keep", "night", "night.sleep", "night.light", "pick", "idle.near", "idle"))
            assertTrue(ids.contains(id), id);
        assertTrue(ids.indexOf("danger") < ids.indexOf("owner") && ids.indexOf("owner") < ids.indexOf("passive") && ids.indexOf("passive") < ids.indexOf("job")
                && ids.indexOf("job") < ids.indexOf("night") && ids.indexOf("night") < ids.indexOf("pick"), "the selector's order");
    }

    // ---- needs ----

    @Test
    void needScoresFollowTheirCurves() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = calm();
        s.freeSlots = 2;
        assertEquals(90, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("bag"));
        s.freeSlots = 4;
        assertEquals(60, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("bag"));
        s.freeSlots = 9;
        assertEquals(0, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("bag"));
        s.foodItems = 0;
        assertEquals(80, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("food"));
        s.foodItems = 4;
        assertEquals(40, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("food"));
        s.foodItems = 8;
        assertEquals(0, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("food"), "met: drops to 0");
        s.needs.add(new BrainState.NeedItem("minecraft:torch", 32, 0, 0));
        assertEquals(80, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("need:torch"));
        s.needs.set(0, new BrainState.NeedItem("minecraft:torch", 32, 16, 0));
        assertEquals(65, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("need:torch"));
        s.now = 1_000_000_000L;
        s.needs.set(0, new BrainState.NeedItem("minecraft:torch", 32, 16, s.now - 20 * 60000));
        assertEquals(75, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("need:torch"), "rises while unmet: +1 per 2 min");
        s.needs.set(0, new BrainState.NeedItem("minecraft:torch", 32, 40, 0));
        assertEquals(0, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("need:torch"), "met");
        assertEquals("gather torch 16", Needs.score(withNeed(16), c, IdleList.DEFAULT, Map.of()).best(10).chain());
        assertEquals(15, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("upkeep"));
    }

    static BrainState withNeed(int have) {
        BrainState s = calm();
        s.needs.add(new BrainState.NeedItem("minecraft:torch", 32, have, 0));
        return s;
    }

    @Test
    void tiesGoToTheNeedTheOwnerAskedForLast() {
        BrainState s = calm();
        s.needs.add(new BrainState.NeedItem("minecraft:torch", 10, 0, 100));
        s.needs.add(new BrainState.NeedItem("minecraft:bread", 10, 0, 200));
        assertEquals("need:bread", Needs.score(s, BrainConfig.defaults(), IdleList.DEFAULT, Map.of()).best(10).need());
    }

    @Test
    void theStageGatesTheToolNeedAndLowersTheFoodBar() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = calm();
        s.stage = "nothing";
        s.pickaxes = 0;
        assertEquals(0, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("tools"), "no tool need at stage nothing");
        s.stage = "stone";
        Needs.Option t = Needs.score(s, c, IdleList.DEFAULT, Map.of()).options().stream().filter(o -> o.need().equals("tools")).findFirst().orElseThrow();
        assertEquals(70, t.score());
        assertEquals("craft stone_pickaxe 1", t.chain());
        s.pickaxes = 1;
        s.pickPct = 9;
        assertEquals(40, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("tools"));
        s.foodItems = 4;
        assertEquals(40, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("food"));
        s.stage = "wood";
        assertEquals(0, Needs.score(s, c, IdleList.DEFAULT, Map.of()).scoreOf("food"), "4 is enough early on");
    }

    @Test
    void notReleasedKeepsTheBrainWithin32OfTheOwner() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = calm();
        s.ownerOnline = true;
        s.ownerPos = new int[]{0, 64, 0};
        s.upkeep.mine = new int[]{200, 40, 0};
        Needs.Scored sc = Needs.score(s, c, IdleList.DEFAULT, Map.of());
        assertEquals("mine cave any 32 20m", sc.best(10).chain(), "the far mine is skipped, caving here instead");
        assertTrue(sc.skipped().get(0).startsWith("upkeep strip: 200 blocks from you"), sc.skipped().toString());
        s.released = true;
        assertEquals("mine strip any 32", Needs.score(s, c, IdleList.DEFAULT, Map.of()).best(10).chain(), "done: it may go");
        s.released = false;
        s.ownerOnline = false;
        assertEquals("mine strip any 32", Needs.score(s, c, IdleList.DEFAULT, Map.of()).best(10).chain(), "the owner offline: it may go");
        // the deposit at a far base is skipped while bound
        s.ownerOnline = true;
        s.freeSlots = 2;
        s.basePos = new int[]{0, 64, 300};
        sc = Needs.score(s, c, IdleList.DEFAULT, Map.of());
        assertNotEquals("deposit", sc.best(10).chain());
        assertTrue(String.join(";", sc.skipped()).contains("bag: 300 blocks from you"));
    }

    @Test
    void parkedNeedsAreLeftOut() {
        BrainState s = withNeed(0);
        Needs.Scored sc = Needs.score(s, BrainConfig.defaults(), IdleList.DEFAULT, Map.of("need:torch", "no source, 20 min left"));
        assertEquals("upkeep", sc.best(10).need());
        assertTrue(sc.skipped().contains("need:torch: parked (no source, 20 min left)"));
    }

    // ---- momentum ----

    @Test
    void aRunningJobIsOutscoredOnlyByTheMargin() {
        BrainConfig c = BrainConfig.defaults();
        // the running job's need scores 60 (bag); a need of 79 must not switch, 80 must (>= 20 more)
        BrainState s = calm();
        s.freeSlots = 4;
        BrainTree.Running r = new BrainTree.Running("bag", "deposit", 60);
        s.needs.add(new BrainState.NeedItem("minecraft:torch", 30, 1, 0));    // 50 + 30*29/30 = 79
        Needs.Scored sc = Needs.score(s, c, IdleList.DEFAULT, Map.of());
        assertEquals(79, sc.scoreOf("need:torch"));
        assertEquals("job.keep", new BrainTree().run(new BrainTree.Ctx(s, c, sc, r, false)).branch(), "+19: no flip");
        s.needs.set(0, new BrainState.NeedItem("minecraft:torch", 30, 0, 0));   // 80
        sc = Needs.score(s, c, IdleList.DEFAULT, Map.of());
        c.set("switchMargin", 21);
        assertEquals("job.keep", new BrainTree().run(new BrainTree.Ctx(s, c, sc, r, false)).branch());
        c.set("switchMargin", 20);
        // +21: flips
        s.freeSlots = 4;
        s.needs.set(0, new BrainState.NeedItem("minecraft:torch", 30, 0, 0));
        s.foodItems = 8;
        BrainTree.Running r59 = new BrainTree.Running("food", "get food 8", 59);
        BrainState s2 = calm();
        s2.foodItems = 1;                      // food 70
        s2.needs.add(new BrainState.NeedItem("minecraft:torch", 30, 1, 0));   // 79: +9 -> keep
        sc = Needs.score(s2, c, IdleList.DEFAULT, Map.of());
        assertEquals("job.keep", new BrainTree().run(new BrainTree.Ctx(s2, c, sc, r59, false)).branch());
        s2.foodItems = 5;                      // food 30, torch 79: +49 -> switch
        sc = Needs.score(s2, c, IdleList.DEFAULT, Map.of());
        BrainTree.Decision d = new BrainTree().run(new BrainTree.Ctx(s2, c, sc, r59, false));
        assertEquals("job.switch", d.branch());
        assertEquals("gather torch 29", d.chain());
    }

    @Test
    void marginBoundary() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = calm();
        s.foodItems = 6;                       // food 20
        s.needs.add(new BrainState.NeedItem("minecraft:torch", 100, 0, 0));    // 80? no: 50+30 = 80
        BrainTree.Running r = new BrainTree.Running("food", "get food 2", 20);
        // food 20 vs torch 80: +60 flips; make the gap 19 and 21 with the margin
        Needs.Scored sc = Needs.score(s, c, IdleList.DEFAULT, Map.of());
        int gap = sc.scoreOf("need:torch") - sc.scoreOf("food");
        c.set("switchMargin", gap + 1);
        assertEquals("job.keep", new BrainTree().run(new BrainTree.Ctx(s, c, sc, r, false)).branch(), "gap = margin - 1: no flip");
        c.set("switchMargin", gap - 1);
        assertEquals("job.switch", new BrainTree().run(new BrainTree.Ctx(s, c, sc, r, false)).branch(), "gap = margin + 1: flip");
    }

    // ---- interrupts ----

    @Test
    void eventsAndTheirRanking() {
        BrainConfig c = BrainConfig.defaults();
        BrainState a = calm(), b = calm();
        b.danger = true;
        b.ownerJob = "dig";
        b.food = 10;
        b.freeSlots = 3;
        b.night = true;
        b.ownerPos = new int[]{1, 2, 3};
        a.brainJob = "mine strip any 32";
        b.toolBroke = true;
        List<Interrupts.Event> e = Interrupts.events(a, b, c);
        assertEquals(List.of(Interrupts.Event.DANGER, Interrupts.Event.OWNER, Interrupts.Event.BROKEN, Interrupts.Event.HUNGRY, Interrupts.Event.FULL,
                Interrupts.Event.NIGHT, Interrupts.Event.SEEN, Interrupts.Event.DONE), e);
        assertTrue(Interrupts.outranks(Interrupts.Event.DANGER, Interrupts.Event.OWNER.rank));
        assertTrue(Interrupts.outranks(Interrupts.Event.OWNER, Interrupts.Event.FULL.rank));
        assertTrue(Interrupts.outranks(Interrupts.Event.HUNGRY, Interrupts.BRAIN_JOB_RANK));
        assertFalse(Interrupts.outranks(Interrupts.Event.NIGHT, Interrupts.BRAIN_JOB_RANK));
        assertFalse(Interrupts.outranks(Interrupts.Event.FULL, Interrupts.Event.OWNER.rank), "the owner's order outranks a full bag");
        // no event twice: the same night is not new
        assertFalse(Interrupts.events(b, b, c).contains(Interrupts.Event.NIGHT));
    }

    @Test
    void midJobInterruptsHandleThenResume() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = calm();
        s.toolBroke = true;
        s.pickaxes = 0;
        s.food = 3;
        s.freeSlots = 0;
        assertArrayEquals(new String[]{"BROKEN", "craft stone_pickaxe 1", "my pickaxe broke"}, Interrupts.midJob(s, c), "broken outranks hungry and full");
        s.toolBroke = false;
        assertEquals("eat", Interrupts.midJob(s, c)[1]);
        s.food = 20;
        assertEquals("deposit", Interrupts.midJob(s, c)[1]);
        s.freeSlots = 5;
        assertNull(Interrupts.midJob(s, c));
        s.food = 6;
        BrainTree.Decision d = decide(s, new BrainTree.Running("upkeep", "mine strip any 32", 15));
        assertEquals("eat then mine strip any 32", d.chain(), "handle, then the same job again");
    }

    // ---- the supply check ----

    @Test
    void aBigDigGetsPickaxesFirst() {
        BrainState s = calm();
        s.pickDurability = 131;
        SupplyCheck.Supply sup = SupplyCheck.before("dig 0 60 0 9 71 9 ores", s, 0, 0);       // 10 x 12 x 10 = 1200
        assertNotNull(sup);
        assertEquals("craft stone_pickaxe 9", sup.chain());                                       // (1200-131)/131 = 8.2 -> 9
        assertEquals("getting 9 stone pickaxes first (dig of 1200 blocks)", sup.why());
        s.pickDurability = 2000;
        assertNull(SupplyCheck.before("dig 0 60 0 9 71 9", s, 0, 0));
        s.pickDurability = 100;
        assertEquals("craft stone_pickaxe 7 then craft stone_shovel 1", SupplyCheck.before("dig 0 60 0 9 71 9", s, 200, 100).chain(), "dirt counts on the shovel");
        s.stage = "nothing";
        assertNull(SupplyCheck.before("dig 0 60 0 9 71 9", s, 0, 0), "stage nothing: nothing to make pickaxes from");
        s.stage = "iron";
        assertEquals("craft iron_pickaxe 5", SupplyCheck.before("dig 0 60 0 9 71 9", s, 0, 0).chain());
    }

    @Test
    void miningAndExploringAreSizedByTime() {
        BrainState s = calm();
        s.foodItems = 1;
        s.pickaxes = 1;
        s.torches = 0;
        SupplyCheck.Supply sup = SupplyCheck.before("mine cave any 32 40m", s, 0, 0);
        assertEquals("get food 7 then craft stone_pickaxe 1 then craft torch 32", sup.chain());
        assertTrue(sup.why().endsWith("(40 min of mining)"), sup.why());
        assertEquals("get food 1", SupplyCheck.before("explore 10", s, 0, 0).chain(), "exploring: food only");
        assertNull(SupplyCheck.before("mine strip status", s, 0, 0));
        assertNull(SupplyCheck.before("explore status", s, 0, 0));
        assertNull(SupplyCheck.before("craft torch 4", s, 0, 0));
        s.foodItems = 16;
        s.torches = 64;
        assertNull(SupplyCheck.before("mine strip any 32", s, 0, 0));
    }

    // ---- the idle list ----

    @Test
    void theIdleListParsesAndRunsInOrder() {
        assertEquals(List.of("strip", "cave"), IdleList.parse("strip, cave"));
        assertNull(IdleList.parse("strip, dance"));
        assertNull(IdleList.parse("cave cave"));
        assertNull(IdleList.parse(""));
        IdleList.Facts f = new IdleList.Facts();
        f.now = 10_000_000;
        f.farm = new int[]{1, 2, 3};
        assertEquals("mine cave any 32 20m", IdleList.candidates(IdleList.DEFAULT, f).get(0).chain());
        assertEquals("farm", IdleList.candidates(List.of("farm", "cave"), f).get(0).chain());
        f.shortSupply = "torch";
        assertEquals("restock", IdleList.candidates(IdleList.DEFAULT, f).get(0).chain());
        f.restockAt = f.now - 60_000;
        assertEquals("mine cave any 32 20m", IdleList.candidates(IdleList.DEFAULT, f).get(0).chain(), "restock at most every 10 min");
        Fake fake = on();
        Brain b = new Brain(fake);
        assertTrue(b.idle("list").startsWith("idle list: restock, strip, cave, farm"));
        assertTrue(b.idle("list set cave, farm").startsWith("ok: idle list: cave, farm"));
        assertEquals(List.of("cave", "farm"), b.idleList());
        assertTrue(b.idle("list set nope").startsWith("error"));
        assertTrue(b.idle("list reset").startsWith("ok: idle list: restock, strip, cave, farm"));
    }

    // ---- copy ----

    @Test
    void copyFollowsTheOwnersReports() {
        CopyRules.Tracker t = new CopyRules.Tracker();
        long now = 1_000_000;
        assertEquals("no owner data (the companion mod isn't reporting)", t.update(null, "mojo", now, 60).why());
        CopyRules.Report old = CopyRules.parse("{\"name\":\"mojo\",\"x\":1,\"y\":64,\"z\":1,\"dim\":\"minecraft:overworld\",\"received\":" + (now - 20_000) + "}");
        assertEquals(CopyRules.Kind.NONE, t.update(old, "mojo", now, 60).kind(), "stale");
        CopyRules.Report other = CopyRules.parse("{\"name\":\"bob\",\"x\":1,\"y\":64,\"z\":1,\"dim\":\"d\",\"received\":" + now + "}");
        assertEquals(CopyRules.Kind.NONE, t.update(other, "mojo", now, 60).kind());
        CopyRules.Report axe = CopyRules.parse("{\"name\":\"mojo\",\"x\":1,\"y\":64,\"z\":1,\"dim\":\"d\",\"received\":" + now + ",\"held\":\"minecraft:iron_axe\"}");
        CopyRules.Activity a = t.update(axe, "mojo", now, 60);
        assertEquals(CopyRules.Kind.CHOP, a.kind());
        assertEquals("cut 16", a.chain());
        CopyRules.Report log = CopyRules.parse("{\"name\":\"mojo\",\"x\":1,\"y\":64,\"z\":1,\"dim\":\"d\",\"received\":" + now + ",\"held\":\"minecraft:iron_axe\","
                + "\"broke\":{\"id\":\"minecraft:birch_log\",\"x\":2,\"y\":65,\"z\":1,\"at\":" + now + "}}");
        assertEquals("cut 16 birch_log", t.update(log, "mojo", now, 60).chain());
        CopyRules.Report ore = CopyRules.parse("{\"name\":\"mojo\",\"x\":1,\"y\":64,\"z\":1,\"dim\":\"d\",\"received\":" + now + ",\"held\":\"minecraft:iron_pickaxe\","
                + "\"broke\":{\"id\":\"minecraft:iron_ore\",\"x\":2,\"y\":65,\"z\":1,\"at\":" + (now + 1) + "}}");
        assertEquals("mine iron_ore 8", t.update(ore, "mojo", now, 60).chain());
        CopyRules.Report crop = CopyRules.parse("{\"name\":\"mojo\",\"x\":1,\"y\":64,\"z\":1,\"dim\":\"d\",\"received\":" + now + ",\"held\":\"\","
                + "\"broke\":{\"id\":\"minecraft:wheat\",\"x\":2,\"y\":65,\"z\":1,\"at\":" + (now + 2) + "}}");
        assertEquals("farm", t.update(crop, "mojo", now, 60).chain());
        CopyRules.Report build = CopyRules.parse("{\"name\":\"mojo\",\"x\":1,\"y\":64,\"z\":1,\"dim\":\"d\",\"received\":" + now + ",\"held\":\"minecraft:oak_planks\"}");
        CopyRules.Tracker t2 = new CopyRules.Tracker();
        CopyRules.Activity bu = t2.update(build, "mojo", now, 60);
        assertEquals(CopyRules.Kind.BUILD, bu.kind());
        assertNull(bu.chain(), "building is not copied");
        // still for 60 s: stops
        CopyRules.Report same = CopyRules.parse("{\"name\":\"mojo\",\"x\":1,\"y\":64,\"z\":1,\"dim\":\"d\",\"received\":" + (now + 61_000) + ",\"held\":\"minecraft:iron_axe\"}");
        CopyRules.Tracker t3 = new CopyRules.Tracker();
        t3.update(axe, "mojo", now, 60);
        assertEquals("you have been still for 60 s", t3.update(same, "mojo", now + 61_000, 60).why());
        assertNull(CopyRules.parse("not json"));
        // a copy option near the owner
        BrainState s = calm();
        s.ownerOnline = true;
        s.ownerPos = new int[]{0, 64, 0};
        s.copy = a;
        assertEquals("cut 16", Needs.score(s, BrainConfig.defaults(), IdleList.DEFAULT, Map.of()).best(10).chain());
    }

    // ---- the decision log ----

    @Test
    void theDecisionLogWritesLinesThatRoundTrip() {
        MemSink sink = new MemSink();
        DecisionLog log = new DecisionLog(sink, ZoneId.of("UTC"));
        long t = 1_790_000_000_000L;
        Map<String, Integer> sc = new LinkedHashMap<>();
        sc.put("bag", 60);
        sc.put("upkeep", 15);
        BrainState s = calm();
        long ref = log.decision(t, 400, "pick", sc, "deposit", "4 free slots", s.summary());
        long ref2 = log.decision(t, 401, "pick", sc, "deposit", "again", s.summary());
        assertEquals(ref + 1, ref2, "refs are unique");
        log.outcome(t + 5000, ref, "finished");
        List<JsonObject> lines = sink.lines();
        assertEquals(3, lines.size());
        JsonObject d = lines.get(0);
        for (String k : List.of("t", "tick", "branch", "scores", "chosen", "reason", "state", "outcome")) assertTrue(d.has(k), k);
        assertEquals(60, d.getAsJsonObject("scores").get("bag").getAsInt());
        assertTrue(d.get("outcome").isJsonNull());
        for (String k : List.of("hp", "maxHp", "food", "freeSlots", "stage", "threats", "light", "night", "ownerDist", "released", "job")) assertTrue(d.getAsJsonObject("state").has(k), k);
        assertEquals(ref, lines.get(2).get("ref").getAsLong());
        assertEquals("finished", lines.get(2).get("outcome").getAsString());
        assertEquals(List.of("2026-09-21"), sink.days());
    }

    @Test
    void theDecisionLogKeeps14DaysAnd5MbADay() throws IOException {
        MemSink sink = new MemSink();
        long t = 1_790_000_000_000L;
        sink.append("2026-09-01", "{}");
        sink.append("2026-09-08", "{}");
        sink.append("2026-09-20", "{}");
        sink.append("notes", "{}");
        DecisionLog log = new DecisionLog(sink, ZoneId.of("UTC"));
        log.decision(t, 1, "pick", Map.of(), "x", "y", new JsonObject());
        assertEquals(List.of("2026-09-08", "2026-09-20", "notes", "2026-09-21"), sink.days(), "older than 14 days deleted (today and the 13 before stay)");
        sink.files.get("2026-09-21").setLength(0);
        sink.files.get("2026-09-21").append("x".repeat((int) DecisionLog.DAY_CAP - 10));
        log.decision(t, 2, "pick", Map.of(), "x", "y", new JsonObject());
        assertEquals(1, log.dropped());
        sink.files.get("2026-09-21").setLength(0);
        sink.fail = true;
        log.outcome(t, 1, "finished");
        assertEquals(1, log.errors(), "a write error is counted, never thrown");
    }

    // ---- the loop: brain on/off, whispers, parking ----

    @Test
    void theBrainStartsTheBestJobWithOneWhisperAndLogsIt() {
        Fake f = on();
        Brain b = new Brain(f);
        f.state.needs.add(new BrainState.NeedItem("minecraft:torch", 32, 0, 0));
        f.loop();
        b.tick();
        assertEquals(List.of("gather torch 32"), f.started);
        assertEquals(List.of("brain: gather torch 32 (need torch 32: have 0)"), f.whispers);
        assertEquals("gather torch 32", b.jobChain());
        // still running: no new whisper, no new job
        f.loop();
        b.tick();
        assertEquals(1, f.started.size());
        assertEquals(1, f.whispers.size());
        assertTrue(b.why().contains("latest: ") && b.why().contains("need:torch 80"), b.why());
        assertEquals("job.keep", f.written.get("branch").getAsString());
        // done: the outcome line, the need met, upkeep next
        f.end("done - ok: gathered 32 torch");
        f.state.needs.clear();
        f.state.needs.add(new BrainState.NeedItem("minecraft:torch", 32, 32, 0));
        f.loop();
        b.tick();
        assertEquals("mine strip any 32", f.started.get(1));
        List<JsonObject> lines = f.sink.lines();
        assertTrue(lines.stream().anyMatch(l -> l.has("outcome") && "finished".equals(l.get("outcome").isJsonNull() ? null : l.get("outcome").getAsString())));
        assertTrue(b.statusPart().startsWith("brain: on (upkeep: mine strip)"), b.statusPart());
    }

    @Test
    void aNeedThatFailsThreeTimesIsParked() {
        Fake f = on();
        Brain b = new Brain(f);
        f.state.needs.add(new BrainState.NeedItem("minecraft:diamond", 4, 0, 0));
        for (int i = 0; i < 3; i++) {
            f.loop();
            b.tick();
            assertEquals("gather diamond 4", f.started.get(f.started.size() - 1));
            f.end("stopped at step 1 (gather diamond 4): error: no source for diamond in my areas");
            f.loop();
            b.tick();
        }
        assertTrue(f.whispers.stream().anyMatch(w -> w.startsWith("brain: can't gather diamond 4 - it failed 3 times")), f.whispers.toString());
        assertEquals("mine strip any 32", f.started.get(f.started.size() - 1), "the parked need is skipped");
        assertTrue(b.findings().stream().anyMatch(x -> x[0].equals("brain:need:diamond")));
        assertTrue(b.why().contains("need:diamond: parked"), b.why());
        // 30 minutes later it may try again
        f.end("done");
        f.now += 31 * 60_000L;
        f.loop();
        b.tick();
        assertEquals("gather diamond 4", f.started.get(f.started.size() - 1));
    }

    @Test
    void aStartRefusedCountsAsAFailure() {
        Fake f = on();
        Brain b = new Brain(f);
        f.startReply = "error: busy";
        for (int i = 0; i < 3; i++) {
            f.loop();
            b.tick();
        }
        assertTrue(f.whispers.stream().anyMatch(w -> w.startsWith("brain: can't mine strip any 32 - error: busy")), f.whispers.toString());
    }

    @Test
    void brainOffStopsOnlyItsOwnJob() {
        Fake f = on();
        Brain b = new Brain(f);
        f.loop();
        b.tick();
        assertTrue(f.running);
        String r = b.command("off");
        assertEquals("ok: brain off - stopped my job mine strip any 32; your jobs run on", r);
        assertFalse(f.running);
        assertEquals(List.of("brain off"), f.stopped);
        assertTrue(f.sink.lines().stream().anyMatch(l -> l.has("ref") && l.get("outcome").getAsString().equals("interrupted:brain off")));
        int n = f.started.size();
        f.loop();
        b.tick();
        assertEquals(n, f.started.size(), "off: no loop");
        // off with an owner's job running: nothing stopped
        Fake g = on();
        Brain b2 = new Brain(g);
        g.state.ownerJob = "dig";
        g.loop();
        b2.tick();
        assertEquals("ok: brain off; your jobs run on", b2.command("off"));
        assertTrue(g.stopped.isEmpty());
    }

    @Test
    void theOwnersOrderOverridesAndIsLabelled() {
        Fake f = on();
        Brain b = new Brain(f);
        f.loop();
        b.tick();
        b.yieldTo("come");
        assertFalse(f.running);
        assertTrue(f.sink.lines().stream().anyMatch(l -> l.has("ref") && l.get("outcome").getAsString().equals("overridden_by_owner:come")));
        // a stop holds the brain 10 minutes
        assertTrue(b.hold().contains("the brain waits 10 min"));
        int n = f.started.size();
        f.loop();
        b.tick();
        assertEquals(n, f.started.size());
        assertTrue(f.written.get("held").getAsBoolean());
        assertTrue(b.command("on").startsWith("ok"), "on lifts the hold");
        f.loop();
        b.tick();
        assertEquals(n + 1, f.started.size());
    }

    @Test
    void dangerPausesAndTheJobResumesWhenItIsOver() {
        Fake f = on();
        Brain b = new Brain(f);
        f.loop();
        b.tick();
        f.state.danger = true;
        f.state.dangerWhy = "fighting";
        f.loop();
        b.tick();
        assertEquals("danger", b.lastDecision().branch());
        assertTrue(f.running, "the chain is not stopped: it retries after the fight");
        f.state.danger = false;
        f.loop();
        b.tick();
        assertEquals("job.keep", b.lastDecision().branch());
        assertEquals(1, f.started.size());
    }

    @Test
    void theAutominerBecomesTheBrainAndItsHistoryIsCopied() {
        Fake f = new Fake();
        f.store.add("autominer", JsonParser.parseString("{\"on\":true,\"log\":[{\"at\":1790000000000,\"what\":\"deposit\",\"why\":\"bag\",\"result\":\"done\"}]}").getAsJsonObject());
        Brain b = new Brain(f);
        assertTrue(b.on());
        assertFalse(f.store.getAsJsonObject("autominer").get("on").getAsBoolean());
        List<JsonObject> lines = f.sink.lines();
        assertEquals(1, lines.size());
        assertEquals("autominer", lines.get(0).get("branch").getAsString());
        assertTrue(lines.get(0).get("history").getAsBoolean());
        assertTrue(new Brain(f).on());
        assertEquals(1, f.sink.lines().size(), "copied once");
    }

    @Test
    void brainCopyAndStatusVerbs() {
        Fake f = on();
        Brain b = new Brain(f);
        assertTrue(b.command("copy on").startsWith("ok: brain copy on"));
        assertTrue(b.copyOn());
        f.state.copy = new CopyRules.Tracker().update(null, "mojo", f.now, 60);
        f.loop();
        b.tick();
        assertEquals("brain copy is on - no owner data (the companion mod isn't reporting)", b.command("copy"));
        assertTrue(b.status().startsWith("brain is on - 1 loops/min"), b.status());
        assertEquals("usage: brain on|off|status | brain copy on|off", b.command("dance"));
        assertTrue(b.command("copy off").startsWith("ok: brain copy off"));
    }

    @Test
    void aBadConfigValueIsNamedAndIgnored() {
        BrainConfig c = BrainConfig.defaults();
        assertEquals("nope, floor", c.load(JsonParser.parseString("{\"nope\":1,\"floor\":\"x\",\"upkeep\":12}").getAsJsonObject()));
        assertEquals(12, c.i("upkeep"));
        assertEquals(10, c.i("floor"));
    }

    @Test
    void theLoopNeverThrows() {
        Fake f = on();
        Brain b = new Brain(f);
        f.shape = s -> { throw new IllegalStateException("boom"); };
        f.loop();
        assertDoesNotThrow(b::tick);
        assertTrue(b.status().contains("errors 1"), b.status());
    }

    @Test
    void nightLightsOnceAtASpotThenWorks() {
        Fake f = on();
        Brain b = new Brain(f);
        f.state.night = true;
        f.state.litHere = false;
        f.loop();
        b.tick();
        assertEquals("light here 8", f.started.get(0));
        f.end("done");
        f.loop();
        b.tick();
        assertEquals("mine strip any 32", f.started.get(1), "lit once here: work on");
    }
}
