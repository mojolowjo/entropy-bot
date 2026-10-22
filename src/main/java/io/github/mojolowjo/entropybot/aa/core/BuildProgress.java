package io.github.mojolowjo.entropybot.aa.core;

/**
 * aa-night2 P0 3 (normal-2026-10-20: "BASE_BED cancelled: stuck: needs.bed made no progress for 40s (no move, no bag
 * change)" while it placed walls): a build stands and spends blocks, so a bag that shrinks during a build task feeds
 * {@link JobPort#progress}. Pure (JUnit BuildProgressTest). Loader notes: none.
 */
public final class BuildProgress {
    private BuildProgress() {}

    /** A task kind whose bag going down is its work (placing blocks): the house, bed and shelter builds. */
    public static boolean counts(TaskKind k) { return k == TaskKind.BASE_BED; }
}
