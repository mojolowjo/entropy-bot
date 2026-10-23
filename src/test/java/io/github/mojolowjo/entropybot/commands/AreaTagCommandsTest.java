package io.github.mojolowjo.entropybot.commands;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** STRUCTURE_BLOCKS (0.39.z26 fix round): the area tag / untag / list command path and the migration's tags loop. */
class AreaTagCommandsTest {
    static final PolicyCommands.Pos HERE = new PolicyCommands.Pos(10, 64, 20, "minecraft:overworld");

    @Test
    void tagUntagListAndTheKeepOutRefusal() {
        JsonObject p = new JsonObject();
        CommandsTest.FakeGuard g = new CommandsTest.FakeGuard();
        PolicyCommands pc = new PolicyCommands(p, g, () -> {});
        assertTrue(pc.command("area", "here 30 camp", true, "owner", HERE, HERE).startsWith("ok: area camp"));
        assertTrue(pc.command("area", "tag camp use-stations break-structures", true, "owner", HERE, HERE).startsWith("ok: area camp tags: use-stations, break-structures"));
        assertTrue(pc.command("area", "list", true, "owner", HERE, HERE).contains("[tags: use-stations, break-structures]"));
        assertEquals(List.of("use-stations", "break-structures"), PolicyCommands.tagsOf(pc.findArea("camp")));
        assertTrue(g.last.toString().contains("\"tags\":[\"use-stations\",\"break-structures\"]"), "the guard gets the tags");
        assertTrue(pc.command("area", "untag camp use-stations", true, "owner", HERE, HERE).startsWith("ok: area camp tags: break-structures"));
        assertTrue(pc.command("area", "tag camp burn", true, "owner", HERE, HERE).contains("burn"), "an unknown word is named");
        assertEquals(List.of("break-structures"), PolicyCommands.tagsOf(pc.findArea("camp")));

        assertTrue(pc.command("area", "here 5 shrine safe", true, "owner", HERE, HERE).startsWith("ok: area shrine"));
        assertEquals("a no-break area takes no tags (it refuses everything)", pc.command("area", "tag shrine break-structures", true, "owner", HERE, HERE));
        assertTrue(PolicyCommands.tagsOf(pc.findArea("shrine")).isEmpty());
    }

    @Test
    void theMigrationGivesEveryAreaItsTagsList() {
        JsonObject a = JsonParser.parseString("{\"areas\":[{\"name\":\"map\",\"x1\":0,\"z1\":0,\"x2\":5,\"z2\":5},"
                + "{\"name\":\"tagged\",\"x1\":0,\"z1\":0,\"x2\":5,\"z2\":5,\"tags\":[\"use-stations\"]}],"
                + "\"protect\":[{\"name\":\"base\",\"x1\":0,\"z1\":0,\"x2\":5,\"z2\":5}]}").getAsJsonObject();
        assertTrue(AreaMigration.migrate(a, null, null).ran());
        assertEquals("[]", a.getAsJsonArray("areas").get(0).getAsJsonObject().get("tags").toString(), "an area without tags gets an empty list");
        assertEquals("[\"use-stations\"]", a.getAsJsonArray("areas").get(1).getAsJsonObject().get("tags").toString(), "tags already there are kept");
        assertEquals("[]", a.getAsJsonArray("protect").get(0).getAsJsonObject().get("tags").toString(), "the protect list too");
    }
}
