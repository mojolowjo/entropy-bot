package io.github.mojolowjo.entropybot.guard;

import com.google.gson.JsonObject;

/**
 * A job's short-lived permission to break (and, with {@code place}, to place) inside one box. A force
 * lease (the owner's {@code dig ... force}) may also break building blocks, never block entities.
 */
public final class Lease {
    public final String id;
    public final String owner;   // the token of whoever took it
    public final String task;
    public final Box box;
    public final boolean place;
    public final boolean force;
    public final long createdTick;
    volatile long lastHeartbeat;

    Lease(String id, String owner, String task, Box box, boolean place, boolean force, long tick) {
        this.id = id;
        this.owner = owner;
        this.task = task;
        this.box = box;
        this.place = place;
        this.force = force;
        this.createdTick = tick;
        this.lastHeartbeat = tick;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("task", task);
        o.add("box", box.toJson());
        o.addProperty("place", place);
        if (force) o.addProperty("force", true);
        o.addProperty("since", createdTick);
        return o;
    }
}
