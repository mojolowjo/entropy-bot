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
    /**
     * Water plan: a one-cell place lease just outside the areas, within one block of a break lease of the same owner
     * (a dig sealing water off at its edge). It lets a block go only into water or air ({@link GuardCore#sealLease}).
     */
    public final boolean seal;
    volatile long lastHeartbeat;

    Lease(String id, String owner, String task, Box box, boolean place, boolean force, long tick) {
        this(id, owner, task, box, place, force, tick, false);
    }

    Lease(String id, String owner, String task, Box box, boolean place, boolean force, long tick, boolean seal) {
        this.seal = seal;
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
        if (seal) o.addProperty("seal", true);
        o.addProperty("since", createdTick);
        return o;
    }
}
