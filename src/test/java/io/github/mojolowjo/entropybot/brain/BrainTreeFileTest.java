package io.github.mojolowjo.entropybot.brain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** B4: the tree export, brain get/set/reset with ranges and persistence, the override file's validation and fallback. */
class BrainTreeFileTest {

    static JsonObject exported(BrainTree t) {
        return BrainTreeFile.export(t, BrainConfig.defaults(), IdleList.DEFAULT, "built-in", "", 1L, "test");
    }

    static void walk(JsonObject n, List<JsonObject> out) {
        out.add(n);
        for (JsonElement k : n.getAsJsonArray("children")) walk(k.getAsJsonObject(), out);
    }

    @Test
    void everyBuiltInNodeIsExportedWithUidsTypesAndKnownKeys() {
        BrainTree t = new BrainTree();
        JsonObject o = exported(t);
        List<JsonObject> nodes = new java.util.ArrayList<>();
        walk(o.getAsJsonObject("root"), nodes);
        long needs = nodes.stream().filter(n -> n.get("type").getAsString().equals("need")).count();
        assertEquals(BrainTreeFile.NEEDS.length, needs);
        assertEquals(t.describe().size(), nodes.size() - needs, "every Java node once");
        Set<String> uids = new HashSet<>();
        for (JsonObject n : nodes) {
            assertTrue(uids.add(n.get("uid").getAsString()), "uid unique: " + n);
            assertTrue(BrainTreeFile.TYPES.contains(n.get("type").getAsString()));
            for (JsonElement k : n.getAsJsonArray("keys")) assertTrue(BrainConfig.known(k.getAsString()) || k.getAsString().equals("idle"), k.getAsString());
        }
        assertEquals("selector", o.getAsJsonObject("root").get("type").getAsString());
        assertTrue(nodes.stream().anyMatch(n -> n.get("type").getAsString().equals("interrupt")));
        assertTrue(nodes.stream().anyMatch(n -> "sleep".equals(n.has("verb") ? n.get("verb").getAsString() : null)));
        assertTrue(o.getAsJsonObject("keys").has("switchMargin"));
        assertEquals(4, o.getAsJsonArray("idle").size());
    }

    @Test
    void pathToNamesTheUidsOfTheBranch() {
        BrainTree t = new BrainTree();
        List<String> p = t.pathTo("night.light");
        assertEquals("r", p.get(0));
        assertEquals(5, p.size());
        assertTrue(p.get(4).startsWith("r/5/1/"), p.toString());
        assertEquals(List.of("r", "r/6"), t.pathTo("pick"));
        assertEquals(List.of(), t.pathTo("nope"));
        assertEquals("need.need", BrainTreeFile.needNode("need:torch"));
        assertEquals("need.goal", BrainTreeFile.needNode("goal:2"));
    }

    @Test
    void brainJsonCarriesThePathAndTheTreeIsExportedOnTheFirstLoop() {
        BrainTest.Fake f = BrainTest.on();
        Brain b = new Brain(f);
        f.loop();
        b.tick();
        assertTrue(f.files.containsKey("brain-tree.json"));
        JsonArray path = f.written.getAsJsonArray("path");
        assertFalse(path.isEmpty());
        assertEquals("r", path.get(0).getAsString());
        assertEquals("built-in", f.written.get("tree").getAsString());
    }

    @Test
    void setGetResetValidateAndPersist() {
        BrainTest.Fake f = BrainTest.on();
        Brain b = new Brain(f);
        assertTrue(b.command("set nope 3").startsWith("error: no brain setting"));
        assertTrue(b.command("set switchMargin 500").contains("0 to 100"));
        assertTrue(b.command("set switchMargin 2.5").contains("whole number"));
        assertTrue(b.command("set switchMargin x").contains("takes a number"));
        assertTrue(b.command("set need.torch.weight 9").contains("0 to 5"));
        assertEquals("ok: switchMargin = 25 (was 20)", b.command("set switchmargin 25"));
        assertTrue(b.command("set need.torch.weight 1.5").startsWith("ok: need.torch.weight = 1.5"));
        assertTrue(b.command("set parkMinutes 45").startsWith("ok"));
        assertTrue(b.command("set nearbyR 48").startsWith("ok"));
        assertTrue(b.command("set deadbandPct 25").startsWith("ok"));
        JsonObject saved = JsonParser.parseString(f.files.get("brain-config.json")).getAsJsonObject();
        assertEquals(25, saved.get("switchMargin").getAsInt());
        assertEquals(1.5, saved.get("need.torch.weight").getAsDouble());
        assertFalse(saved.has("floor"), "only what differs");
        // a new brain (a restart) reads the file
        Brain b2 = new Brain(f);
        assertTrue(b2.command("get switchMargin").startsWith("switchMargin = 25 (default 20, 0 to 100)"));
        assertTrue(b2.command("get").contains("switchMargin 25*"));
        assertTrue(b2.command("get need.torch.weight").startsWith("need.torch.weight = 1.5"));
        assertEquals(45, b2.loadConfig().i("parkMinutes"));
        // the tree export carries the new values
        assertEquals(25, JsonParser.parseString(f.files.get("brain-tree.json")).getAsJsonObject().getAsJsonObject("keys")
                .getAsJsonObject("switchMargin").get("value").getAsInt());
        assertTrue(b2.command("reset switchMargin").startsWith("ok: switchMargin = 20"));
        assertTrue(b2.command("reset all").startsWith("ok"));
        assertEquals("{}", f.files.get("brain-config.json"));
        assertTrue(b2.command("set idle strip, cave").startsWith("ok: idle list: strip, cave"));
        assertEquals(List.of("strip", "cave"), b2.idleList());
    }

    @Test
    void anUnwritableFileKeepsTheSettingInCommandsJson() {
        BrainTest.Fake f = BrainTest.on();
        f.filesBroken = true;
        Brain b = new Brain(f);
        assertTrue(b.command("set floor 12").contains("saved in commands.json"));
        assertEquals(12, b.loadConfig().i("floor"));
    }

    @Test
    void weightsAndTheDeadbandChangeTheScores() {
        BrainConfig c = BrainConfig.defaults();
        BrainState s = new BrainState();
        s.needs.add(new BrainState.NeedItem("minecraft:torch", 32, 10, 0));
        s.now = 0;
        int plain = Needs.score(s, c, IdleList.DEFAULT, java.util.Map.of()).scoreOf("need:torch");
        assertNull(c.set("need.torch.weight", "1.5"));
        int heavy = Needs.score(s, c, IdleList.DEFAULT, java.util.Map.of()).scoreOf("need:torch");
        assertEquals(Math.min(100, Math.round(plain * 1.5f)), heavy);
        assertEquals(8, Needs.refire(32, 25));
        assertEquals(16, Needs.refire(32, 50));
        assertNull(c.set("deadbandPct", "25"));
        assertFalse(Needs.score(s, c, IdleList.DEFAULT, java.util.Map.of()).has("need:torch"), "10 of 32 is above 25 %");
    }

    @Test
    void theExportParsesBackAndAReorderedTreeLoads() {
        JsonObject o = exported(new BrainTree());
        assertTrue(BrainTreeFile.parse(o.toString()).ok(), BrainTreeFile.parse(o.toString()).errors().toString());
        // move night before the brain's running job
        JsonArray kids = o.getAsJsonObject("root").getAsJsonArray("children");
        JsonElement job = kids.get(4), night = kids.get(5);
        kids.set(4, night);
        kids.set(5, job);
        BrainTreeFile.Result r = BrainTreeFile.parse(o.toString());
        assertTrue(r.ok(), r.errors().toString());
        assertEquals(List.of("r", "r/4", "r/4/1", "r/4/1/3", "r/4/1/3/1"), r.tree().pathTo("night.light"));
    }

    @Test
    void badFilesAreRefusedWithTheReason() {
        assertFalse(BrainTreeFile.parse("{nope").ok());
        assertTrue(BrainTreeFile.parse("{}").errors().get(0).contains("root"));
        JsonObject o = exported(new BrainTree());
        JsonObject root = o.getAsJsonObject("root");
        JsonObject first = root.getAsJsonArray("children").get(0).getAsJsonObject();
        first.addProperty("type", "loop");
        assertTrue(BrainTreeFile.parse(o.toString()).errors().get(0).contains("unknown node type loop"));
        first.addProperty("type", "sequence");
        JsonObject leaf = first.getAsJsonArray("children").get(1).getAsJsonObject();
        leaf.addProperty("verb", "tnt");
        assertTrue(BrainTreeFile.parse(o.toString()).errors().get(0).contains("unknown verb tnt"));
        leaf.remove("verb");
        leaf.getAsJsonArray("keys").add("bogusKey");
        assertTrue(BrainTreeFile.parse(o.toString()).errors().get(0).contains("unknown key bogusKey"));
        leaf.getAsJsonArray("keys").remove(leaf.getAsJsonArray("keys").size() - 1);
        leaf.addProperty("id", "fly");
        assertTrue(BrainTreeFile.parse(o.toString()).errors().get(0).contains("no built-in leaf fly"));
        // too deep
        JsonObject deep = new JsonObject();
        JsonObject cur = deep;
        deep.addProperty("type", "selector");
        deep.addProperty("id", "d0");
        for (int i = 1; i < 10; i++) {
            JsonObject k = new JsonObject();
            k.addProperty("type", "selector");
            k.addProperty("id", "d" + i);
            JsonArray a = new JsonArray();
            a.add(k);
            cur.add("children", a);
            cur = k;
        }
        JsonObject f = new JsonObject();
        f.add("root", deep);
        assertTrue(String.join(";", BrainTreeFile.parse(f.toString()).errors()).contains("deeper than 8"));
    }

    @Test
    void aRefusedOverrideFallsBackToTheBuiltInTreeWithACheckLine() {
        BrainTest.Fake f = BrainTest.on();
        Brain b = new Brain(f);
        f.files.put(BrainTreeFile.OVERRIDE, "{\"root\":{\"type\":\"loop\",\"id\":\"x\"}}");
        String r = b.command("tree reload");
        assertTrue(r.startsWith("error: brain-tree.override.json refused (root: unknown node type loop"), r);
        assertEquals("built-in", b.treeSource());
        assertTrue(b.findings().stream().anyMatch(x -> x[0].equals("braintree")));
        // a good one
        JsonObject o = exported(new BrainTree());
        f.files.put(BrainTreeFile.OVERRIDE, o.toString());
        assertTrue(b.command("tree reload").startsWith("ok: the tree from"));
        assertEquals("override", b.treeSource());
        assertFalse(b.findings().stream().anyMatch(x -> x[0].equals("braintree")));
        assertEquals("override", JsonParser.parseString(f.files.get("brain-tree.json")).getAsJsonObject().get("source").getAsString());
        // gone: the built-in tree
        f.files.remove(BrainTreeFile.OVERRIDE);
        assertTrue(b.command("tree reload").startsWith("ok: the built-in tree"));
    }

    /** Writes the real export to build/brain-tree.json (the dashboard's offline page check and tools/brain_tree.ps1 read it). */
    @Test
    void writesTheExportForTheDashboard() throws Exception {
        Path p = Path.of("build", "brain-tree.json");
        Files.createDirectories(p.getParent());
        String version = "unknown";
        Path gp = Path.of("gradle.properties");
        if (Files.exists(gp)) for (String l : Files.readAllLines(gp)) if (l.startsWith("mod_version=")) version = l.substring(12).trim();
        JsonObject o = BrainTreeFile.export(new BrainTree(), BrainConfig.defaults(), IdleList.DEFAULT, "built-in", "the built-in tree (exported by the mod's tests)",
                System.currentTimeMillis(), version);
        Files.writeString(p, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(o));
        assertTrue(Files.size(p) > 1000);
    }
}
