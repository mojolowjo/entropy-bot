package io.github.mojolowjo.entropybot.camp;

/**
 * C4: night and day for {@code sleep} and the rule triggers {@code rule when night do ...} / {@code rule when day do ...}.
 * Pure. dayTime is the level's day time (ticks since the world began; {@code % 24000} is the time of day).
 *
 * <p>Vanilla lets a player sleep from 12542 to 23459 (clear weather); that is "night" here. A night/day rule fires once
 * per night (or day): the rule stores the {@link #key} it fired for and fires again only for a new one.
 *
 * <p>Loader notes: none. The day time comes from {@code ClientLevel.getDayTime()} (vanilla, every loader).
 */
public final class DayNight {
    private DayNight() {}

    public static final long NIGHT_FROM = 12542, NIGHT_TO = 23459;

    public static boolean night(long dayTime) {
        if (dayTime < 0) return false;
        long t = dayTime % 24000;
        return t >= NIGHT_FROM && t <= NIGHT_TO;
    }

    /** "n<day>" or "d<day>"; a night belongs to the day it began on, the morning after it to the next day. */
    public static String key(long dayTime) {
        long day = dayTime / 24000, t = dayTime % 24000;
        if (night(dayTime)) return "n" + day;
        return "d" + (t > NIGHT_TO ? day + 1 : day);
    }

    /** A "when night" / "when day" rule is due: that phase now, and it has not fired for this one (lastKey). */
    public static boolean due(String arg, long dayTime, String lastKey) {
        if (dayTime < 0 || arg == null) return false;
        boolean wantNight = arg.equals("night");
        if (!wantNight && !arg.equals("day")) return false;
        if (night(dayTime) != wantNight) return false;
        return !key(dayTime).equals(lastKey);
    }

    /** "6:30" style clock for messages (day time 0 = 06:00). */
    public static String clock(long dayTime) {
        long t = ((dayTime % 24000) + 24000) % 24000;
        long h = (t / 1000 + 6) % 24, m = (t % 1000) * 60 / 1000;
        return String.format("%02d:%02d", h, m);
    }
}
