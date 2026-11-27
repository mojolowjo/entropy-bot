package io.github.mojolowjo.entropybot.ghost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PilotTest {
    static Pilot.View view(double x, double y, double z, int[] src, int[] dest, MoveKind k, boolean mayHop) {
        return new Pilot.View(x, y, z, true, false, (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
                src[0], src[1], src[2], dest[0], dest[1], dest[2], k, mayHop, true);
    }

    @Test
    void theYawIsBaritones() {
        assertEquals(-90f, Pilot.yawTo(0.5, 0.5, 5, 0), 1e-4, "east (+x) is yaw -90");
        assertEquals(0f, Pilot.yawTo(0.5, 0.5, 0, 5), 1e-4, "south (+z) is yaw 0");
        assertEquals(90f, Pilot.yawTo(0.5, 0.5, -5, 0), 1e-4, "west (-x) is yaw 90");
        // vanilla moves forward by (-sin yaw, cos yaw): for -90 that is +x
        double r = Math.toRadians(Pilot.yawTo(0.5, 0.5, 5, 0));
        assertEquals(1.0, -Math.sin(r), 1e-6);
    }

    @Test
    void aFlatWalkSprintsWithoutJumping() {
        Pilot.Press p = Pilot.steer(view(1.5, -50, 0.5, new int[]{1, -50, 0}, new int[]{2, -50, 0}, MoveKind.TRAVERSE, false));
        assertTrue(p.forward());
        assertTrue(p.sprint());
        assertFalse(p.jump());
        assertFalse(p.hop());
    }

    @Test
    void anAscendJumpsOnlyNearTheStep() {
        assertFalse(Pilot.steer(view(1.5, -50, 0.5, new int[]{1, -50, 0}, new int[]{3, -49, 0}, MoveKind.ASCEND, false)).jump(), "2 away");
        Pilot.Press near = Pilot.steer(view(2.4, -50, 0.5, new int[]{2, -50, 0}, new int[]{3, -49, 0}, MoveKind.ASCEND, false));
        assertTrue(near.jump());
        assertFalse(near.sprint(), "Baritone does not sprint up a step");
    }

    @Test
    void parkourJumpsFromTheSourceAtOnceForAShortGapAndAtTheEdgeForALongOne() {
        assertTrue(Pilot.steer(view(9.5, -50, 0.5, new int[]{9, -50, 0}, new int[]{11, -50, 0}, MoveKind.PARKOUR, false)).jump(), "1-block gap");
        assertFalse(Pilot.steer(view(9.5, -50, 0.5, new int[]{9, -50, 0}, new int[]{12, -50, 0}, MoveKind.PARKOUR, false)).jump(), "2-block gap, at the centre");
        assertFalse(Pilot.steer(view(9.85, -50, 0.5, new int[]{9, -50, 0}, new int[]{12, -50, 0}, MoveKind.PARKOUR, false)).jump(), "2-block gap, 0.35 from the centre");
        assertTrue(Pilot.steer(view(10.25, -50, 0.5, new int[]{9, -50, 0}, new int[]{12, -50, 0}, MoveKind.PARKOUR, false)).jump(), "2-block gap, over the edge");
        assertFalse(Pilot.steer(view(8.5, -50, 0.5, new int[]{9, -50, 0}, new int[]{11, -50, 0}, MoveKind.PARKOUR, false)).jump(), "not on the source yet");
        assertTrue(Pilot.steer(view(10.05, -50, 0.5, new int[]{9, -50, 0}, new int[]{13, -50, 0}, MoveKind.PARKOUR, false)).jump(), "3-block gap, feet just off the source");
        assertFalse(Pilot.steer(view(9.85, -50, 0.5, new int[]{9, -50, 0}, new int[]{13, -50, 0}, MoveKind.PARKOUR, false)).jump(), "3-block gap, still on the source");
    }

    @Test
    void aLadderHoldsJumpAndAFallDoesNot() {
        Pilot.Press ladder = Pilot.steer(view(13.5, -49, 1.5, new int[]{13, -50, 1}, new int[]{13, -49, 1}, MoveKind.PILLAR, false));
        assertTrue(ladder.jump());
        assertFalse(ladder.sprint());
        assertFalse(Pilot.steer(view(9.5, -50, 0.5, new int[]{9, -50, 0}, new int[]{10, -53, 0}, MoveKind.FALL, false)).jump());
    }

    @Test
    void aStallHopsWhenAllowed() {
        Pilot.Press p = Pilot.steer(view(1.5, -50, 0.5, new int[]{1, -50, 0}, new int[]{2, -50, 0}, MoveKind.TRAVERSE, true));
        assertTrue(p.jump());
        assertTrue(p.hop());
        GhostInput in = p.input(0f);
        assertEquals(1f, in.forward());
        assertEquals(in.yaw(), in.jumpYaw());
    }

    @Test
    void moveKindsComeFromBaritonesClassNames() {
        assertEquals(MoveKind.PARKOUR, MoveKind.fromClassName("MovementParkour"));
        assertEquals(MoveKind.TRAVERSE, MoveKind.fromClassName("MovementTraverse"));
        assertEquals(MoveKind.OTHER, MoveKind.fromClassName("MovementSwim"));
        assertEquals(MoveKind.OTHER, MoveKind.fromClassName("Something"));
        assertEquals(MoveKind.OTHER, MoveKind.fromClassName(null));
    }
}
