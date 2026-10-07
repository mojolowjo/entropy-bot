package io.github.mojolowjo.entropybot.vocab;

/**
 * V1b (VOCABULARY 4): {@code defend} (stay within {@link #DEFEND_R} of where the bot stands and fight what comes) and
 * {@code guard <area|place|marker>} (stay in the area's box, or within {@link #GUARD_R} of the place or marker). The bot
 * walks back after a fight when it is out. Pure.
 */
public final class HoldRules {
    private HoldRules() {}

    public static final int DEFEND_R = 4, GUARD_R = 8;

    /** Out of the hold: outside the box {x1,y1,z1,x2,y2,z2} (y ignored when y1 > y2), or farther than r (flat) from x y z. */
    public static boolean outside(int[] box, int cx, int cz, int r, int x, int y, int z) {
        if (box != null) {
            boolean inXZ = x >= Math.min(box[0], box[3]) && x <= Math.max(box[0], box[3]) && z >= Math.min(box[2], box[5]) && z <= Math.max(box[2], box[5]);
            boolean allY = box[1] > box[4];
            return !inXZ || (!allY && (y < box[1] - 1 || y > box[4] + 1));
        }
        long dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz > (long) r * r;
    }

    /** Where to walk back to: the box's middle (at y) or the spot. */
    public static int[] home(int[] box, int cx, int cy, int cz) {
        if (box == null) return new int[]{cx, cy, cz};
        return new int[]{(box[0] + box[3]) / 2, cy, (box[2] + box[5]) / 2};
    }

    /** "guard" alone or with the fence's own words: the fence's verb (V1a), so those answer with fence. */
    public static boolean fenceForm(String rest) {
        String r = rest == null ? "" : rest.trim().toLowerCase();
        return r.isEmpty() || r.matches("^(status|vetoes|check|mode)\\b.*");
    }
}
