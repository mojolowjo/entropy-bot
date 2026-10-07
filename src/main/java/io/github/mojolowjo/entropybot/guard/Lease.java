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
    /**
     * 0.21.2: granted because the near-me zone covered the box (no area of the owner's does). It stays good inside its
     * box when the owner walks off (a dig started next to them finishes); it never reaches past its box.
     */
    public final boolean near;
    /**
     * V1a (0.22.0): a destroy lease: the owner's own "dig &lt;area&gt;" on a destroy area; it may break built blocks inside
     * its box (never block entities). {@link #area} names that area.
     */
    public boolean destroy;
    public String area;
    /** 0.23.1: granted under the roaming permission of a running explore/find (outside the areas, natural blocks only). */
    public boolean roam;
    volatile long lastHeartbeat;

    Lease(String id, String owner, String task, Box box, boolean place, boolean force, long tick) {
        this(id, owner, task, box, place, force, tick, false);
    }

    Lease(String id, String owner, String task, Box box, boolean place, boolean force, long tick, boolean seal) {
        this(id, owner, task, box, place, force, tick, seal, false);
    }

    Lease(String id, String owner, String task, Box box, boolean place, boolean force, long tick, boolean seal, boolean near) {
        this.near = near;
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
        if (near) o.addProperty("near", true);
        if (destroy) o.addProperty("destroy", area == null ? "?" : area);
        if (roam) o.addProperty("roam", true);
        o.addProperty("since", createdTick);
        return o;
    }
}
