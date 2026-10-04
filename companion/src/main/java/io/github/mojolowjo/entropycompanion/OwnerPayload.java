package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonObject;

/** The JSON body of a post: {@code {"name","x","y","z","dim","at"}} ({@code at} = epoch milliseconds). */
public final class OwnerPayload {
    private OwnerPayload() {}

    /** One player position. */
    public record Snapshot(String name, double x, double y, double z, String dim) {}

    /** The body for a snapshot at the time {@code atMs}; null when a coordinate is not a finite number. */
    public static String json(Snapshot s, long atMs) {
        if (s == null || s.name() == null || s.name().isEmpty() || s.dim() == null || s.dim().isEmpty()) return null;
        if (!Double.isFinite(s.x()) || !Double.isFinite(s.y()) || !Double.isFinite(s.z())) return null;
        JsonObject o = new JsonObject();
        o.addProperty("name", s.name());
        o.addProperty("x", round2(s.x()));
        o.addProperty("y", round2(s.y()));
        o.addProperty("z", round2(s.z()));
        o.addProperty("dim", s.dim());
        o.addProperty("at", atMs);
        return o.toString();
    }

    static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}