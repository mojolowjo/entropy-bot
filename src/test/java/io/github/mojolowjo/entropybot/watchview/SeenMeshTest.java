package io.github.mojolowjo.entropybot.watchview;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeenMeshTest {
    static final String OW = "minecraft:overworld";

    @Test
    void shellAndSeenFacesTogetherUnderTheCap() {
        // a corridor x 0, y 0..1, z 0..9; the bot dug z 0..2 (known air); it saw the end wall and the walls further on
        GridWorld w = new GridWorld().openBox(0, 0, 0, 0, 1, 9);
        long[] known = {CellKey.of(0, 0, 0), CellKey.of(0, 1, 0), CellKey.of(0, 0, 1), CellKey.of(0, 1, 1), CellKey.of(0, 0, 2), CellKey.of(0, 1, 2)};
        Shell.Result shell = Shell.build(known, w, 0, 0, 0, 60_000);
        SeenFaces seen = new SeenFaces();
        seen.add(OW, 0, 1, 10, 2, 12);          // the end wall's north face
        seen.add(OW, 1, 1, 8, 4, 0);            // the east wall at z 8, seen in the dark
        seen.add(OW, 1, 1, 1, 4, 15);           // also a shell face: drawn once, as shell
        seen.add(OW, 2, 1, 5, 4, 15);           // no longer a real face (rock in front of it): never drawn
        SeenMesh.Result r = SeenMesh.union(shell.faces(), shell.capped(), seen.near(OW, 0, 0, 0, 64), w, 0, 0, 0, 60_000);
        assertEquals(shell.faces().size() + 2, r.faces().size());
        assertEquals(2, r.seenFaces());
        assertEquals(shell.faces().size(), r.shellFaces());
        assertFalse(r.capped());
        assertEquals(1, r.faces().stream().filter(f -> f.x() == 1 && f.y() == 1 && f.z() == 1 && f.side() == 4).count(), "no duplicate");
        assertFalse(r.faces().stream().filter(f -> f.x() == 1 && f.y() == 1 && f.z() == 1).findFirst().orElseThrow().seen(), "the shell's copy wins");
        SeenMesh.MeshFace dark = r.faces().stream().filter(f -> f.z() == 8 && f.x() == 1).findFirst().orElseThrow();
        assertTrue(dark.seen());
        assertEquals(0, dark.light());
        for (SeenMesh.MeshFace f : r.faces()) {
            assertEquals(Shell.SOLID, w.kind(f.x(), f.y(), f.z()));
            int[] o = Shell.OFF[f.side()];
            assertEquals(Shell.OPEN, w.kind(f.x() + o[0], f.y() + o[1], f.z() + o[2]), "every face looks into air");
        }
    }

    @Test
    void aMinedOrCoveredSeenFaceIsDropped() {
        GridWorld w = new GridWorld().openBox(0, 0, 0, 0, 1, 9);
        SeenFaces seen = new SeenFaces();
        seen.add(OW, 1, 1, 5, 4, 15);
        assertEquals(1, SeenMesh.union(List.of(), false, seen.near(OW, 0, 0, 0, 64), w, 0, 0, 0, 100).faces().size());
        w.open(1, 1, 5);                        // mined since
        assertEquals(0, SeenMesh.union(List.of(), false, seen.near(OW, 0, 0, 0, 64), w, 0, 0, 0, 100).faces().size());
        GridWorld w2 = new GridWorld().openBox(0, 0, 0, 0, 1, 9);
        w2.solid(0, 1, 5);                      // the air in front of it filled since
        assertEquals(0, SeenMesh.union(List.of(), false, seen.near(OW, 0, 0, 0, 64), w2, 0, 0, 0, 100).faces().size());
        GridWorld w3 = new GridWorld().openBox(0, 0, 0, 0, 1, 9);
        w3.unknown.add(CellKey.of(1, 1, 5));    // not loaded
        assertEquals(0, SeenMesh.union(List.of(), false, seen.near(OW, 0, 0, 0, 64), w3, 0, 0, 0, 100).faces().size());
    }

    @Test
    void theCapKeepsTheNearestOfBoth() {
        GridWorld w = new GridWorld().openBox(0, 0, 0, 0, 0, 40);
        long[] known = {CellKey.of(0, 0, 30)};
        Shell.Result shell = Shell.build(known, w, 0, 0, 0, 60_000);     // 4 faces far away at z 30
        SeenFaces seen = new SeenFaces();
        for (int z = 0; z < 5; z++) seen.add(OW, 1, 0, z, 4, 15);       // 5 near faces
        SeenMesh.Result r = SeenMesh.union(shell.faces(), false, seen.near(OW, 0, 0, 0, 64), w, 0, 0, 0, 6);
        assertTrue(r.capped());
        assertEquals(6, r.faces().size());
        assertEquals(5, r.seenFaces(), "the near seen faces first");
        assertEquals(1, r.shellFaces());
    }
}
