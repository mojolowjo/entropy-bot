package io.github.mojolowjo.entropycompanion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The v2 owner post (COMPANION_PLAN 2.3): v1's {name, x, y, z, dim, at} plus v, health, maxHealth, food, saturation,
 * armor, yaw, pitch, held, counts {torch, food, blocks} and, only when the inventory changed and at least
 * {@link #INV_GAP_MS} passed since it was last sent, inv [[id, n], ...] (stacks of one id summed, at most
 * {@link #INV_MAX} ids). Plain Java; the Minecraft side fills a {@link State}.
 */
public final class OwnerState {
    public static final long INV_GAP_MS = 10_000;
    public static final int INV_MAX = 64;
    public static final int BODY_MAX = 8192;

    /** One inventory stack. food / block: what kind of item (for the counts). */
    public record Stack(String id, int n, boolean food, boolean block) {}

    public record State(String name, double x, double y, double z, String dim,
                        float health, float maxHealth, int food, float saturation, int armor,
                        float yaw, float pitch, String held, List<Stack> stacks) {}

    private int lastInvHash;
    private long lastInvSent = Long.MIN_VALUE / 2;
    private boolean everSent;

    /** 0.3.1 (assist): the owner's last broken and placed block, what is under the crosshair, the action guess. */
    public record Extra(OwnerActions.Ev broke, OwnerActions.Ev placed, OwnerActions.Target target, String action) {}

    /** The body for the state at time now, or null when a value is unusable. Decides whether inv goes along. */
    public String json(State s, long now) { return json(s, null, now); }

    /** The same with 0.3.1's fields: broke {id,x,y,z,age}, placed {id,x,y,z,age,left}, target {id,x,y,z,kind}, action. */
    public String json(State s, Extra x, long now) {
        String v1 = OwnerPayload.json(new OwnerPayload.Snapshot(s.name(), s.x(), s.y(), s.z(), s.dim()), now);
        if (v1 == null) return null;
        JsonObject o = com.google.gson.JsonParser.parseString(v1).getAsJsonObject();
        o.addProperty("v", 2);
        o.addProperty("health", r1(s.health()));
        o.addProperty("maxHealth", r1(s.maxHealth()));
        o.addProperty("food", s.food());
        o.addProperty("saturation", r1(s.saturation()));
        o.addProperty("armor", s.armor());
        o.addProperty("yaw", r1(s.yaw()));
        o.addProperty("pitch", r1(s.pitch()));
        if (s.held() != null && !s.held().isEmpty()) o.addProperty("held", s.held());
        Map<String, Integer> sum = summed(s.stacks());
        JsonObject counts = new JsonObject();
        int torch = 0, food = 0, blocks = 0;
        for (Stack st : s.stacks() == null ? List.<Stack>of() : s.stacks()) {
            if (st.id().endsWith(":torch")) torch += st.n();
            if (st.food()) food += st.n();
            if (st.block()) blocks += st.n();
        }
        counts.addProperty("torch", torch);
        counts.addProperty("food", food);
        counts.addProperty("blocks", blocks);
        o.add("counts", counts);
        if (x != null) {
            if (x.broke() != null) o.add("broke", x.broke().json(now, null));
            if (x.placed() != null) o.add("placed", x.placed().json(now, sum.getOrDefault(x.placed().id(), 0)));
            if (x.target() != null) o.add("target", x.target().json());
            if (x.action() != null) o.addProperty("action", x.action());
        }
        int hash = sum.hashCode();
        if ((!everSent || hash != lastInvHash) && now - lastInvSent >= INV_GAP_MS) {
            JsonArray inv = new JsonArray();
            for (Map.Entry<String, Integer> e : sum.entrySet()) {
                if (inv.size() >= INV_MAX) break;
                JsonArray pair = new JsonArray();
                pair.add(e.getKey());
                pair.add(e.getValue());
                inv.add(pair);
            }
            o.add("inv", inv);
            lastInvHash = hash;
            lastInvSent = now;
            everSent = true;
        }
        String body = o.toString();
        if (body.length() > BODY_MAX) {          // never expected (64 ids x ~60 chars); drop inv rather than be refused
            o.remove("inv");
            body = o.toString();
        }
        return body;
    }

    /** Stacks of one id summed, sorted by id (empty and bad ids left out). */
    static Map<String, Integer> summed(List<Stack> stacks) {
        Map<String, Integer> m = new TreeMap<>();
        if (stacks == null) return m;
        for (Stack s : stacks) {
            if (s == null || s.n() <= 0 || s.id() == null || !s.id().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) continue;
            m.merge(s.id(), s.n(), Integer::sum);
        }
        return m;
    }

    static double r1(double v) {
        return Double.isFinite(v) ? Math.round(v * 10.0) / 10.0 : 0;
    }
}
