package io.github.mojolowjo.entropybot.screen;

/** 0.39.z24: the one line an {@code aa} order's end whispers to the reporting player (and logs). Pure (JUnit). */
public final class OrderEnd {
    private OrderEnd() {}

    public enum End { DONE, FAILED, PARKED, STOPPED }

    public static End kind(boolean done, boolean cancelled, String why) {
        if (done) return End.DONE;
        if (cancelled) return End.STOPPED;
        return why != null && why.toLowerCase().contains("park") ? End.PARKED : End.FAILED;
    }

    public static String line(String label, End end, String why) {
        String w = why == null || why.isBlank() ? "" : " - " + why;
        return switch (end) {
            case DONE -> label + " done" + w;
            case FAILED -> label + " failed" + w;
            case PARKED -> label + " parked" + w;
            case STOPPED -> label + " stopped" + w;
        };
    }
}
