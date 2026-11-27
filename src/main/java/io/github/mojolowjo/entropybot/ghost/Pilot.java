package io.github.mojolowjo.entropybot.ghost;

/**
 * Dry-run package 3, the ghost's pilot: what it presses this tick to get from where it stands to the next path point,
 * steering the way Baritone does (m0j 2026-11-26: "drive like Baritone first"). Baritone's {@code MovementHelper.moveTowards}
 * turns the yaw to the next block's centre and holds forward; its moves add the jump and sprint:
 * <ul>
 *   <li>ASCEND: jump once within {@link #ASCEND_JUMP_DIST} of the destination's centre (MovementAscend waits for 1.2);</li>
 *   <li>PARKOUR: sprint; for a 1-block gap jump as soon as the feet are on the source block, for a longer one once
 *       {@link #PARKOUR_EDGE} from its centre, past its edge while still standing on it (MovementParkour);</li>
 *   <li>PILLAR on a ladder: forward and jump held, so the climb rule lifts it;</li>
 *   <li>FALL and DESCEND: walk off, no jump;</li>
 *   <li>everything flat: sprint when Baritone may.</li>
 * </ul>
 * Kestrel's stall rule on top: when it has made no progress for {@code ghost.hopAfterTicks} on the ground and a hop is
 * left (3 per point), it jumps once.
 *
 * <p>Pure (JUnit: PilotTest). Loader notes: none. The yaw formula is Baritone's {@code RotationUtils.calcRotationFromVec3d}
 * (vanilla's facing: forward moves by (-sin yaw, cos yaw)).
 */
public final class Pilot {
    private Pilot() {}

    static final double ASCEND_JUMP_DIST = 1.2;    // structural: MovementAscend's jump distance (upstream 1.11.3)
    static final double PARKOUR_EDGE = 0.7;        // structural: MovementParkour's distance from the source centre

    /** What the pilot sees this tick, in plain numbers. */
    public record View(double x, double y, double z, boolean onGround, boolean onClimbable, int feetX, int feetY, int feetZ,
                       int srcX, int srcY, int srcZ, int destX, int destY, int destZ, MoveKind kind, boolean mayHop, boolean sprintAllowed) {}

    /** What it presses: the yaw (degrees), forward, jump, sprint, and whether this jump used a hop. */
    public record Press(float yaw, boolean forward, boolean jump, boolean sprint, boolean hop) {
        public GhostInput input(float pitch) {
            return new GhostInput(forward ? 1f : 0f, 0f, jump, sprint, false, yaw, pitch);
        }
    }

    /** Baritone's yaw from (x, z) to the centre of block (bx, bz). */
    public static float yawTo(double x, double z, int bx, int bz) {
        double dx = bx + 0.5 - x, dz = bz + 0.5 - z;
        return (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90f;
    }

    public static Press steer(View v) {
        float yaw = yawTo(v.x, v.z, v.destX, v.destZ);
        double flat = Math.hypot(v.destX + 0.5 - v.x, v.destZ + 0.5 - v.z);
        boolean jump = false, sprint = v.sprintAllowed;
        switch (v.kind) {
            case ASCEND -> {
                sprint = false;
                jump = v.onGround && v.feetY < v.destY && flat <= ASCEND_JUMP_DIST;
            }
            case PARKOUR -> {
                boolean onSrc = v.feetX == v.srcX && v.feetY == v.srcY && v.feetZ == v.srcZ;
                int dist = Math.max(Math.abs(v.destX - v.srcX), Math.abs(v.destZ - v.srcZ));
                double fromSrc = Math.max(Math.abs(v.srcX + 0.5 - v.x), Math.abs(v.srcZ + 0.5 - v.z));
                // a 1-block gap: jump on the source; a 2-block gap: once 0.7 from its centre (MovementParkour waits for
                // that only at dist 3); 3+ gaps: as soon as the feet leave it (package 3 run 1, gap4 lane: waiting for
                // 0.7 at sprint speed skipped the last standing tick and the ghost fell)
                double edge = dist == 3 ? PARKOUR_EDGE : 0.5;   // structural: the source block's edge
                jump = v.onGround && (dist < 3 ? onSrc : fromSrc >= edge && fromSrc < 1.0);   // structural: still within a block of it
            }
            case PILLAR -> {
                sprint = false;
                jump = v.onClimbable || v.onGround;
            }
            case FALL, DESCEND, DOWNWARD -> { }
            default -> { }
        }
        boolean hop = false;
        if (!jump && v.mayHop && v.onGround) {
            jump = true;
            hop = true;
        }
        return new Press(yaw, true, jump, sprint, hop);
    }
}
