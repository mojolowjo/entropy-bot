package io.github.mojolowjo.entropybot.ghost;

/**
 * Dry-run package 3: which of Baritone's moves leads into a path point, so the pilot drives it the way Baritone does
 * (a jump for an ascend or a parkour gap, sprint on the flat, forward and jump on a ladder).
 *
 * <p>Pure (JUnit: PilotTest). Loader notes: none; the adapter names it from the movement's class
 * ({@code baritone.pathing.movement.movements.Movement*}, the same in upstream 1.11.3 and the fork).
 */
public enum MoveKind {
    TRAVERSE, DIAGONAL, ASCEND, DESCEND, FALL, PARKOUR, PILLAR, DOWNWARD, OTHER;

    /** From the movement's simple class name ("MovementParkour" -> PARKOUR); OTHER when unknown. */
    public static MoveKind fromClassName(String simpleName) {
        if (simpleName == null || !simpleName.startsWith("Movement")) return OTHER;
        try {
            return valueOf(simpleName.substring("Movement".length()).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;   // a move this pilot does not know drives like a walk, and a stall refuses it
        }
    }
}
