package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** 0.2.0 (C1 + C3): the pure parts. No Minecraft types. */
class CompanionV2Test {

    // ---- PointRules: every row of COMPANION_PLAN 2.2

    static PointRules.Result d(PointRules.Hit h) { return PointRules.decide(h, 8, 16); }

    @Test
    void pointRules() {
        assertEquals("mine iron_ore 8", d(PointRules.Hit.block("minecraft:iron_ore", 1, 2, 3, true, false, false)).command());
        assertEquals("mine deepslate_iron_ore 8", d(PointRules.Hit.block("minecraft:deepslate_iron_ore", 1, 2, 3, false, false, false)).command(), "_ore without the tag");
        assertEquals("mine oritech:nickel_ore 8", d(PointRules.Hit.block("oritech:nickel_ore", 1, 2, 3, true, false, false)).command(), "modded keeps its prefix");
        assertEquals("chop 16 oak_log", d(PointRules.Hit.block("minecraft:oak_log", 1, 2, 3, false, true, false)).command());
        assertEquals("chop 16 biomesoplenty:fir_log", d(PointRules.Hit.block("biomesoplenty:fir_log", 1, 2, 3, false, true, false)).command());
        assertEquals("open -28 54 189", d(PointRules.Hit.block("minecraft:chest", -28, 54, 189, false, false, true)).command());
        assertEquals("open 1 2 3", d(PointRules.Hit.block("minecraft:barrel", 1, 2, 3, false, false, true)).command());
        assertEquals("goto 10 65 -5", d(PointRules.Hit.block("minecraft:grass_block", 10, 64, -5, false, false, false)).command(), "ground: the block above");
        assertEquals("attack 4711", d(PointRules.Hit.entity("minecraft:zombie", 4711, 1, 2, 3, true, false, false, false, false)).command());
        assertEquals("goto 1 2 3", d(PointRules.Hit.entity("minecraft:item", 5, 1, 2, 3, false, false, false, false, true)).command(), "item: goto for now");
        assertTrue(d(PointRules.Hit.entity("minecraft:player", 1, 0, 0, 0, false, true, false, false, false)).refusal().contains("players or pets"));
        assertTrue(d(PointRules.Hit.entity("minecraft:wolf", 1, 0, 0, 0, false, false, true, false, false)).refusal().contains("players or pets"));
        assertTrue(d(PointRules.Hit.entity("minecraft:villager", 1, 0, 0, 0, false, false, false, true, false)).refusal().contains("villagers"));
        PointRules.Result pig = d(PointRules.Hit.entity("minecraft:pig", 1, 0, 0, 0, false, false, false, false, false));
        assertFalse(pig.ok());
        assertTrue(pig.refusal().contains("pig is no monster"));
        assertEquals("nothing under the crosshair", d(PointRules.Hit.miss()).refusal());
        assertEquals("nothing under the crosshair", d(null).refusal());
        // a chest that is also tagged weirdly still opens
        assertEquals("open 1 2 3", d(PointRules.Hit.block("x:ore_chest", 1, 2, 3, true, false, true)).command());
    }

    // ---- OwnerState

    static OwnerState.State state(List<OwnerState.Stack> stacks) {
        return new OwnerState.State("mojolowjo", 1.234, 64, -3.5, "minecraft:overworld", 18.5f, 20, 17, 3.25f, 12, 91.27f, -10.04f,
                "minecraft:iron_pickaxe", stacks);
    }

    @Test
    void ownerStateFieldsAndInvOnlyOnChange() {
        OwnerState os = new OwnerState();
        List<OwnerState.Stack> st = new ArrayList<>(List.of(
                new OwnerState.Stack("minecraft:torch", 30, false, true),
                new OwnerState.Stack("minecraft:torch", 4, false, true),
                new OwnerState.Stack("minecraft:bread", 12, true, false),
                new OwnerState.Stack("minecraft:cobblestone", 64, false, true)));
        JsonObject o = JsonParser.parseString(os.json(state(st), 1000)).getAsJsonObject();
        assertEquals(2, o.get("v").getAsInt());
        assertEquals("mojolowjo", o.get("name").getAsString());
        assertEquals(18.5, o.get("health").getAsDouble());
        assertEquals(17, o.get("food").getAsInt());
        assertEquals(91.3, o.get("yaw").getAsDouble());
        assertEquals("minecraft:iron_pickaxe", o.get("held").getAsString());
        assertEquals(34, o.getAsJsonObject("counts").get("torch").getAsInt());
        assertEquals(12, o.getAsJsonObject("counts").get("food").getAsInt());
        assertEquals(98, o.getAsJsonObject("counts").get("blocks").getAsInt());
        assertEquals(3, o.getAsJsonArray("inv").size(), "stacks of one id summed");
        assertEquals("[\"minecraft:bread\",12]", o.getAsJsonArray("inv").get(0).toString());
        // same bag: no inv; changed but within 10 s: no inv; after 10 s: inv
        assertFalse(JsonParser.parseString(os.json(state(st), 20_000)).getAsJsonObject().has("inv"));
        st.add(new OwnerState.Stack("minecraft:dirt", 1, false, true));
        assertTrue(JsonParser.parseString(os.json(state(st), 25_000)).getAsJsonObject().has("inv"));
        st.add(new OwnerState.Stack("minecraft:stone", 1, false, true));
        assertFalse(JsonParser.parseString(os.json(state(st), 30_000)).getAsJsonObject().has("inv"), "within 10 s of the last inv");
        assertTrue(JsonParser.parseString(os.json(state(st), 35_001)).getAsJsonObject().has("inv"));
    }

    @Test
    void ownerStateFullBagUnder8k() {
        List<OwnerState.Stack> st = new ArrayList<>();
        for (int i = 0; i < 41; i++)
            st.add(new OwnerState.Stack("averyverylongmodname:a_really_long_modded_item_name_number_" + i, 64, i % 2 == 0, i % 3 == 0));
        String body = new OwnerState().json(state(st), 1);
        assertTrue(body.length() < OwnerState.BODY_MAX, "size " + body.length());
        assertEquals(41, JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("inv").size());
        assertNull(new OwnerState().json(new OwnerState.State("a", Double.NaN, 0, 0, "minecraft:overworld", 1, 1, 1, 1, 1, 1, 1, "", List.of()), 1));
        // a bad id is left out of inv
        String b2 = new OwnerState().json(state(List.of(new OwnerState.Stack("Bad Id", 1, false, false))), 1);
        assertEquals(0, JsonParser.parseString(b2).getAsJsonObject().getAsJsonArray("inv").size());
    }

    // ---- CmdClient

    static CompanionConfig cfg() {
        CompanionConfig c = new CompanionConfig();
        c.url = "http://192.168.1.20:8765";
        c.key = "sekrit-key";
        return c;
    }

    @Test
    void cmdClientRepliesAndMasksTheKey() {
        AtomicLong t = new AtomicLong(10_000);
        List<String> seen = new ArrayList<>();
        CmdClient.Http http = (m, uri, key, body, to) -> {
            seen.add(m + " " + uri + " " + body);
            assertEquals("sekrit-key", key);
            return new CmdClient.Resp(200, "{\"result\":\"ok: coming (key sekrit-key)\",\"via\":\"fast\"}");
        };
        CmdClient c = new CmdClient(http, new CmdClient.Link(), t::get);
        assertNull(c.admit());
        assertEquals("ok: coming (key ***)", c.run(cfg(), "come"));
        assertEquals("POST http://192.168.1.20:8765/api/cmd come", seen.get(0));
        assertFalse(seen.get(0).contains("sekrit"), "the key is never in the URL");
        assertEquals(1, c.sent());
    }

    @Test
    void cmdClientRateLimitAndOneInFlight() {
        AtomicLong t = new AtomicLong(10_000);
        CmdClient c = new CmdClient((m, u, k, b, to) -> new CmdClient.Resp(200, "{\"result\":\"ok\"}"), new CmdClient.Link(), t::get);
        assertNull(c.admit());
        assertEquals(CmdClient.WAITING, c.admit(), "one in flight");
        c.run(cfg(), "a");
        assertEquals(CmdClient.TOO_FAST, c.admit(), "2 a second");
        t.addAndGet(500);
        assertNull(c.admit());
    }

    @Test
    void cmdClientErrors() throws Exception {
        AtomicLong t = new AtomicLong(10_000);
        CmdClient.Link link = new CmdClient.Link();
        CmdClient.Http down = (m, u, k, b, to) -> { throw new ConnectException("refused"); };
        CmdClient c = new CmdClient(down, link, t::get);
        assertNull(c.admit());
        assertEquals(CmdClient.UNREACHABLE, c.run(cfg(), "come"));
        assertTrue(link.down());
        t.addAndGet(600);
        assertEquals(CmdClient.UNREACHABLE, c.admit(), "answers at once while down");
        t.addAndGet(CmdClient.DOWN_QUIET_MS);
        assertNull(c.admit(), "tries again after the quiet time");
        c.run(cfg(), "come");

        CmdClient k403 = new CmdClient((m, u, k, b, to) -> new CmdClient.Resp(403, "Missing or wrong key"), new CmdClient.Link(), t::get);
        k403.admit();
        assertEquals(CmdClient.KEY_REFUSED, k403.run(cfg(), "come"));
        CmdClient slow = new CmdClient((m, u, k, b, to) -> { throw new java.net.http.HttpTimeoutException("t"); }, new CmdClient.Link(), t::get);
        slow.admit();
        assertTrue(slow.run(cfg(), "come").contains("may still run"), "a timeout is never retried");
        CompanionConfig off = cfg();
        off.key = "";
        CmdClient any = new CmdClient((m, u, k, b, to) -> { throw new IOException("x"); }, new CmdClient.Link(), t::get);
        any.admit();
        assertTrue(any.run(off, "come").contains("fill in url and key"));
        assertEquals("plain", CmdClient.result("plain"));
    }

    @Test
    void configDefaultsHaveNoAddress() {
        CompanionConfig c = new CompanionConfig().normalised();
        assertEquals("", c.url, "the repo is public: no LAN address in the defaults");
        assertEquals("b", c.commandAlias);
        assertTrue(c.accountOk("anyone"));
        c.ownerName = "MojoLowjo";
        assertTrue(c.accountOk("mojolowjo"));
        assertFalse(c.accountOk("someoneelse"));
        c.commandAlias = "bot";
        assertEquals("b", c.normalised().commandAlias);
        c.commandAlias = "";
        assertEquals("", c.normalised().commandAlias, "empty = no alias");
        assertTrue(c.toString().contains("key=(empty)"));
    }

    // ---- ReplyQueue

    @Test
    void replyQueue() {
        ReplyQueue q = new ReplyQueue();
        q.add("one", 0, 6000);
        q.add("two", 1000, 6000);
        q.add("three", 2000, 6000);
        q.add("four", 3000, 6000);
        assertEquals(List.of("four", "three", "two"), q.visible(3000), "3 lines, newest on top");
        assertEquals(List.of("four", "three"), q.visible(7500));
        assertEquals(List.of(), q.visible(9001));
        q.sticky("[bot] dashboard unreachable - commands paused");
        q.add("x".repeat(500), 10_000, 6000);
        List<String> v = q.visible(10_000);
        assertEquals(2, v.size());
        assertTrue(v.get(0).contains("unreachable"), "the sticky line first");
        assertEquals(ReplyQueue.MAX_CHARS, v.get(1).length());
        q.sticky(null);
        assertEquals(1, q.visible(10_000).size());
    }

    // ---- C3: corners and boxes

    @Test
    void cornersMakeTheCommands() {
        Corners c = new Corners();
        assertTrue(c.areaCommand("farm", false).startsWith("error: set corner 1"));
        c.set(1, new Corners.Pos(10, 64, -5, "minecraft:overworld"));
        c.set(2, new Corners.Pos(-20, 70, 30, "minecraft:overworld"));
        assertEquals("area add farm -20 -5 10 30", c.areaCommand("farm", false));
        assertEquals("area add farm -20 -5 10 30 64 70", c.areaCommand("farm", true));
        assertEquals("protect alex_house -20 56 -5 10 86 30", c.protectCommand("alex_house"));
        assertTrue(c.areaCommand("Bad Name", false).startsWith("error: a name"));
        assertTrue(c.areaCommand("", false).startsWith("error"));
        c.set(2, new Corners.Pos(0, 0, 0, "minecraft:the_nether"));
        assertTrue(c.areaCommand("x", false).contains("different dimensions"));
        c.clear();
        assertNull(c.get(1));
    }

    @Test
    void boxSetParsesAndFilters() {
        String body = "{\"areas\":[{\"name\":\"map\",\"dim\":\"minecraft:overworld\",\"x1\":-272,\"z1\":-64,\"x2\":223,\"z2\":431},"
                + "{\"name\":\"far\",\"dim\":\"minecraft:overworld\",\"x1\":5000,\"z1\":5000,\"x2\":5010,\"z2\":5010},"
                + "{\"name\":\"broken\"}],"
                + "\"protect\":[{\"name\":\"base\",\"dim\":\"minecraft:overworld\",\"x1\":-10,\"z1\":200,\"x2\":-40,\"z2\":170,\"y1\":-64,\"y2\":80},"
                + "{\"name\":\"n\",\"dim\":\"minecraft:the_nether\",\"x1\":0,\"z1\":0,\"x2\":1,\"z2\":1}],\"strict\":true}";
        List<BoxSet.Box> all = BoxSet.parse(body);
        assertEquals(4, all.size(), "the broken entry is skipped");
        BoxSet.Box base = all.get(2);
        assertEquals(BoxSet.Kind.PROTECT, base.kind());
        assertEquals(-40, base.x1());
        assertEquals(-10, base.x2());
        assertEquals(170, base.z1());
        assertEquals(BoxSet.WORLD_MIN_Y, all.get(0).y1(), "no heights = the whole world");
        List<BoxSet.Box> near = BoxSet.near(all, "minecraft:overworld", -25, 185, BoxSet.RANGE);
        assertEquals(List.of("map", "base"), near.stream().map(BoxSet.Box::name).toList());
        assertEquals(1, BoxSet.near(all, "minecraft:overworld", 300, 0, BoxSet.RANGE).size(), "77 blocks outside the map area: in range");
        assertEquals(0, BoxSet.near(all, "minecraft:overworld", 400, 0, BoxSet.RANGE).size());
        assertThrows(RuntimeException.class, () -> BoxSet.parse("not json"));
    }

    // ---- the link notices once

    @Test
    void postLoopNoticesUnreachableOnce() {
        AtomicLong t = new AtomicLong(0);
        CmdClient.Link link = new CmdClient.Link();
        List<String> notices = new ArrayList<>();
        PostLoop loop = new PostLoop(CompanionV2Test::cfg, (u, k, b, to) -> { throw new ConnectException("no"); }, s -> {}, t::get);
        loop.link(link, notices::add);
        for (int i = 0; i < 10; i++) {
            loop.attemptBody(at -> "{}");
            t.addAndGet(31_000);
        }
        assertEquals(List.of(CmdClient.UNREACHABLE), notices, "one notice, not a flood");
        assertEquals(10, loop.failedCount());
        PostLoop ok = new PostLoop(CompanionV2Test::cfg, (u, k, b, to) -> 200, s -> {}, t::get);
        ok.link(link, notices::add);
        ok.attemptBody(at -> "{}");
        assertEquals("connected", notices.get(1));
        assertEquals(1, ok.okCount());
        URI u = cfg().apiUri("/api/map/boxes");
        assertEquals("/api/map/boxes", u.getPath());
    }
}
