package io.github.mojolowjo.entropybot.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mojolowjo.entropybot.Core;
import io.github.mojolowjo.entropybot.guard.Box;
import io.github.mojolowjo.entropybot.guard.GuardCore;
import io.github.mojolowjo.entropybot.guard.Policy;
import io.github.mojolowjo.entropybot.io.BotFiles;

/**
 * What scripts call: strings and JSON in and out, nothing that can throw into a caller. A KubeJS
 * script reaches it with {@code Java.tryLoadClass('io.github.mojolowjo.entropybot.api.BotAPI')}.
 *
 * <p>Methods that change the guard take the token from {@link #token()}, so that a caller has to have
 * asked the mod first; the bridge fetches it once per script load.
 */
public final class BotAPI {
    private BotAPI() {}

    private static Core core() { return Core.INSTANCE; }

    /** The mod's version, e.g. "0.1.0". */
    public static String version() {
        try { return core().version(); } catch (Throwable t) { return "unknown"; }
    }

    /** JSON array of what works right now: "token", "files", "events", "guard:strict|log", "guard:click", "guard:place", "guard:astar". */
    public static String features() {
        try { return core().features().toString(); } catch (Throwable t) { return "[]"; }
    }

    /** The per-game-session token the guard calls want. */
    public static String token() {
        return core().token;
    }

    /** Events with seq greater than afterSeq, oldest first, at most max (0 = all), as a JSON array. */
    public static String events(long afterSeq, int max) {
        try { return core().events.since(afterSeq, max); } catch (Throwable t) { return "[]"; }
    }

    /** The newest event's seq (0 when none yet). */
    public static long lastSeq() {
        try { return core().events.lastSeq(); } catch (Throwable t) { return 0; }
    }

    /** The guard's status as JSON: mode, floor size, areas, protect boxes, leases, counts, which hooks apply. */
    public static String guard() {
        try { return core().guardStatus().toString(); } catch (Throwable t) { return "{\"error\":\"" + t + "\"}"; }
    }

    /** A dry run: "ok" or "would refuse: <reason>" for action "break", "place" or "go" at a spot. Never logs. */
    public static String check(String dim, int x, int y, int z, String action) {
        try {
            String a = action == null ? "break" : action.toLowerCase();
            if (!a.equals("break") && !a.equals("place") && !a.equals("go")) return "error: action must be break, place or go";
            GuardCore.BlockInfo info = GuardCore.BlockInfo.PLAIN;
            var mc = net.minecraft.client.Minecraft.getInstance();
            boolean here = mc.level != null && dim.equals(io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level));
            if (a.equals("break") && here) info = core().guard.infoFor(mc.level, new net.minecraft.core.BlockPos(x, y, z));
            // a walk that ends next to a portal could step in: part of the floor, so in every mode
            if (a.equals("go") && here) {
                String portal = io.github.mojolowjo.entropybot.guard.Guard.portalNear(mc.level, x, y, z, 2);
                if (portal != null) return "would refuse: next to a " + portal + " (I stay out of the Nether and the End)";
            }
            GuardCore.Verdict v = core().guard.core.checkUnlogged(dim, x, y, z, a, info);
            if (v.allowed() && !v.wouldVeto()) return "ok";
            return (v.allowed() ? "would refuse (log mode): " : "would refuse: ") + v.reason();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** Replaces the areas and protect boxes: {"areas":[{name,dim?,x1,z1,x2,z2,y1?,y2?}...],"protect":[...]}. */
    public static String setPolicy(String json, String token) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            Policy p = Policy.parse(json);
            String r = core().guard.core.setPolicy(p);
            core().events.push("guard", "policy: " + r, null);
            return r;
        } catch (IllegalArgumentException e) {
            return "error: " + e.getMessage();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** The current areas and protect boxes as JSON. */
    public static String policy() {
        try { return core().guard.core.policy().toJson().toString(); } catch (Throwable t) { return "{}"; }
    }

    /** "strict" (vetoes) or "log" (area and lease rules only record). The floor is enforced either way. */
    public static String mode(String mode, String token) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            GuardCore.Mode m = "log".equalsIgnoreCase(mode) ? GuardCore.Mode.LOG : "strict".equalsIgnoreCase(mode) ? GuardCore.Mode.STRICT : null;
            if (m == null) return "error: mode must be strict or log";
            String r = core().guard.core.setMode(m);
            core().events.push("guard", r, null);
            return r;
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** Takes a lease for a job: the lease id, or "error: ...". boxJson needs x1 y1 z1 x2 y2 z2 (dim optional). */
    public static String lease(String token, String task, String boxJson, boolean place) {
        return leaseImpl(token, task, boxJson, place, false);
    }

    /** The owner's {@code dig ... force}: may break building blocks (never block entities) in a box of at most 64. */
    public static String forceLease(String token, String task, String boxJson) {
        return leaseImpl(token, task, boxJson, false, true);
    }

    private static String leaseImpl(String token, String task, String boxJson, boolean place, boolean force) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            JsonObject o = JsonParser.parseString(boxJson).getAsJsonObject();
            if (!o.has("y1") || !o.has("y2")) return "error: a lease box needs y1 and y2";
            Box box = Box.fromJson(o, Policy.DEFAULT_DIM);
            String r = core().guard.core.lease(token, task == null ? "job" : task, box, place, force);
            if (!r.startsWith("error")) core().events.push("guard", "lease " + r + " for " + task + ": " + box.describe() + (place ? " (place)" : "") + (force ? " (force)" : ""), null);
            return r;
        } catch (IllegalArgumentException | IllegalStateException e) {
            return "error: " + e.getMessage();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    public static void release(String id) {
        try { core().guard.core.release(id); } catch (Throwable ignored) {}
    }

    public static void releaseAll(String token) {
        try { if (core().token.equals(token)) core().guard.core.releaseAll(token); } catch (Throwable ignored) {}
    }

    /** Renews every lease taken with this token; a lease without a heartbeat for 100 ticks dies. Returns the count. */
    public static int heartbeat(String token) {
        try { return core().guard.core.heartbeat(token); } catch (Throwable t) { return 0; }
    }

    /** The last vetoes (and would-be vetoes in log mode), newest last, as a JSON array. */
    public static String vetoes(int max) {
        try { return core().guard.core.log().recent(max); } catch (Throwable t) { return "[]"; }
    }

    /** Writes JSON atomically under the bot's folder: "ok: N bytes" or "error: ...". */
    public static String writeJson(String name, String json) {
        try {
            BotFiles f = core().files();
            return f == null ? "error: not in a world yet" : f.writeJson(name, json);
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** The file's text, null when it does not exist, "error: ..." when it cannot be read. */
    public static String readJson(String name) {
        try {
            BotFiles f = core().files();
            return f == null ? "error: not in a world yet" : f.readJson(name);
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** True while a reflex (eating, fighting, fleeing, retreating) runs and every job should hold still. */
    public static boolean hold() {
        try { return core().reflexes.hold(); } catch (Throwable t) { return false; }
    }

    /**
     * The reflexes as JSON: {reflex: none|eating|fighting|fleeing|retreating, status, on, target?, dist?,
     * urgent (just hurt, or a monster within 5), noFood, deniedDim?, engine: none|hold|override|off}.
     */
    public static String reflex() {
        try { return core().reflexes.status().toString(); } catch (Throwable t) { return "{\"reflex\":\"none\",\"error\":\"" + t + "\"}"; }
    }

    /** The "eat" verb: eat now if hungry at all. "started: eating" or "error: ...". */
    public static String eat(String token) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            return core().reflexes.eat();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** "defend on|off": fighting, creepers and retreats (eating goes on either way). */
    public static String defence(String token, boolean on) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            core().reflexes.setDefence(on);
            return "ok: self-defence " + (on ? "on" : "off");
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** B3a: the knowledge files' version; it goes up with every change (the bridge pulls when it moved). */
    public static long knowledgeVersion() {
        try { return core().knowledge.version(); } catch (Throwable t) { return -1; }
    }

    /** {"version":n,"places":{name:{x,y,z,dim,...}},"chests":{"x y z":{dim,items,seen,trusted?}}} */
    public static String knowledge() {
        try { return core().knowledge.toJson().toString(); } catch (Throwable t) { return "{\"error\":\"" + t + "\"}"; }
    }

    /** Changes from the bridge: {"places":{name: obj|null}, "chests":{key: obj|null}} (null deletes). The new version, or -1. */
    public static long knowledgePut(String token, String json) {
        try {
            if (!core().token.equals(token)) return -1;
            return core().knowledge.put(json, core().tick());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The bridge's whole notes at its load: its places win, a chest note seen later wins. The new version, or -1. */
    public static long knowledgeMerge(String token, String json) {
        try {
            if (!core().token.equals(token)) return -1;
            return core().knowledge.merge(json, core().tick());
        } catch (Throwable t) {
            return -1;
        }
    }

    /** B3b: the points of interest: {"next":n,"pois":[{id,kind,x,y,z,dim,first,last}]} */
    public static String pois() {
        try { return core().pois.toJson().toString(); } catch (Throwable t) { return "{\"pois\":[],\"error\":\"" + t + "\"}"; }
    }

    /** Forgets one point of interest: "ok: ..." or "error: ...". */
    public static String poiForget(String token, int id) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            return core().pois.forget(id, core().tick()) ? "ok: forgot poi " + id : "error: no poi " + id;
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    // ---- B4: caves ----

    /** The caves known: {"next":n,"caves":{name:{dim,entrance,furthest,frontierLeft,explored,created,updated}}} */
    public static String caves() {
        try { return core().caves.toJson(false).toString(); } catch (Throwable t) { return "{\"caves\":{},\"error\":\"" + t + "\"}"; }
    }

    /** The cave to explore from where the bot stands: the named one, the nearest unfinished one, or a new one. JSON or "error: ...". */
    public static String caveStart(String token, String name) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return "error: not in a world";
            var p = mc.player.blockPosition();
            io.github.mojolowjo.entropybot.cave.Caves.Cave c = core().caves.pick(name, io.github.mojolowjo.entropybot.guard.Guard.dimOf(mc.level),
                    p.getX(), p.getY(), p.getZ(), System.currentTimeMillis(), core().tick());
            if (c == null) return "error: I know no cave called " + name + " (\"caves\" lists them)";
            JsonObject o = c.toJsonPublic();
            o.addProperty("name", c.name);
            return o.toString();
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /**
     * One look around in the cave: marks where the bot is as explored, then searches. oresJson is an array of block
     * ids to mine. {"frontier":{x,y,z,dist}|null,"ores":[{x,y,z,id,stand:[x,y,z],dist}],"reached":n,"dark":bool,
     * "fromEntrance":n} or {"error":...}.
     */
    public static String caveStep(String token, String name, String oresJson, int maxFromEntrance) {
        try {
            if (!core().token.equals(token)) return "{\"error\":\"bad token\"}";
            var mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return "{\"error\":\"not in a world\"}";
            io.github.mojolowjo.entropybot.cave.Caves.Cave c = core().caves.get(name);
            if (c == null) return "{\"error\":\"no cave called " + name + "\"}";
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (var e : JsonParser.parseString(oresJson).getAsJsonArray()) ids.add(e.getAsString());
            var p = mc.player.blockPosition();
            core().caves.visit(c, p.getX(), p.getY(), p.getZ(), System.currentTimeMillis(), core().tick());
            var r = io.github.mojolowjo.entropybot.cave.CaveSearch.search(new io.github.mojolowjo.entropybot.cave.LevelWorld(mc.level),
                    p.getX(), p.getY(), p.getZ(), c.visited, ids::contains, c.ex, c.ey, c.ez, maxFromEntrance);
            JsonObject o = new JsonObject();
            if (r.frontier() != null) {
                JsonObject f = new JsonObject();
                f.addProperty("x", r.frontier().x());
                f.addProperty("y", r.frontier().y());
                f.addProperty("z", r.frontier().z());
                f.addProperty("dist", r.frontier().dist());
                o.add("frontier", f);
            }
            com.google.gson.JsonArray a = new com.google.gson.JsonArray();
            for (var ore : r.ores()) {
                JsonObject q = new JsonObject();
                q.addProperty("x", ore.x());
                q.addProperty("y", ore.y());
                q.addProperty("z", ore.z());
                q.addProperty("id", ore.id());
                q.addProperty("dist", ore.dist());
                a.add(q);
            }
            o.add("ores", a);
            o.addProperty("reached", r.reached());
            o.addProperty("dark", r.dark());
            o.addProperty("fromEntrance", (int) Math.round(Math.sqrt(p.distSqr(new net.minecraft.core.BlockPos(c.ex, c.ey, c.ez)))));
            return o.toString();
        } catch (Throwable t) {
            return "{\"error\":\"" + t.toString().replace("\"", "'") + "\"}";
        }
    }

    /** Marks the spot as explored without a search (a frontier the bot couldn't reach). */
    public static String caveVisit(String token, String name, int x, int y, int z) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            io.github.mojolowjo.entropybot.cave.Caves.Cave c = core().caves.get(name);
            if (c == null) return "error: no cave called " + name;
            core().caves.visit(c, x, y, z, System.currentTimeMillis(), core().tick());
            return "ok";
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** The run in this cave is over; frontierLeft false = the cave is finished. */
    public static String caveEnd(String token, String name, boolean frontierLeft) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            io.github.mojolowjo.entropybot.cave.Caves.Cave c = core().caves.get(name);
            if (c == null) return "error: no cave called " + name;
            core().caves.finish(c, frontierLeft, System.currentTimeMillis(), core().tick());
            return "ok";
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    public static String caveRename(String token, String from, String to) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            return core().caves.rename(from, to, core().tick());
        } catch (Throwable t) {
            return "error: " + t;
        }
    }

    /** Where to retreat to: {"base":{x,y,z,dim},"home":{x,y,z,dim}} (the /home landing), from the bridge's notes. */
    public static String setPlaces(String token, String json) {
        try {
            if (!core().token.equals(token)) return "error: bad token";
            return core().reflexes.setPlaces(json);
        } catch (Throwable t) {
            return "error: " + t;
        }
    }
}
