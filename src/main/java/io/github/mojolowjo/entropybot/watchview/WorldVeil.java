package io.github.mojolowjo.entropybot.watchview;

/**
 * Camera v2 since 0.15.4: the rules that keep the real world hidden while the tunnel view is on (the owner's decision,
 * 2026-10-04: a noclip camera, and nothing of the world drawn but the shell of the cells the bot opened, the bot and
 * mobs). Pure (JUnit: WorldVeilTest); {@link TunnelView} does the drawing.
 *
 * <p>How it works, per frame: the level renders as usual (Sodium included), then at
 * {@code RenderLevelStageEvent.Stage.AFTER_LEVEL} (posted by {@code GameRenderer.renderLevel} after
 * {@code LevelRenderer.renderLevel}, after Fabulous' composite, before the hand and the GUI) the view clears the main
 * target's colour and depth to {@link #BG_R}/{@link #BG_G}/{@link #BG_B} and draws only its own things. Whatever the
 * world render drew (terrain, block entities, water, clouds, the block outline) is gone. The frame guard checks every
 * frame that this "veil" ran: a frame that was rendered with the view on but without the veil is cleared at
 * {@code RenderFrameEvent.Post} (before the picture reaches the screen; that frame loses its GUI) and counted; 3 such
 * frames in a row turn the view off, which puts the normal camera back at the bot. So the tunnel view can never show
 * the real world from a camera inside rock.
 */
public final class WorldVeil {
    /** The background behind the shell: a dark slate blue (the sky is not drawn; it would be "the world" too). */
    public static final float BG_R = 0.09f, BG_G = 0.11f, BG_B = 0.16f;
    /** Mobs and players further than this from the bot are not drawn (blocks). */
    public static final double ENTITY_RADIUS = 48;
    /** Frames in a row without the veil after which the view turns itself off. */
    public static final int MISSES_TO_STOP = 3;

    /** What {@link Guard#post} asks the caller to do at the end of a frame. */
    public enum Verdict { NOTHING, CLEAR, CLEAR_AND_STOP }

    private WorldVeil() {}

    /**
     * Which entities the view draws itself after the clear: the bot always; other mobs and players when visible and
     * within {@link #ENTITY_RADIUS}. Never items, frames, minecarts, armour stands (they belong to the world, and could
     * give away a mineshaft or a dungeon).
     */
    public static boolean shows(boolean isBot, boolean isMobOrPlayer, boolean invisible, double distSq) {
        if (isBot) return true;
        return isMobOrPlayer && !invisible && distSq <= ENTITY_RADIUS * ENTITY_RADIUS;
    }

    /** The frame guard (render thread only). */
    public static final class Guard {
        private boolean armed, veiled, placed;
        private int inARow;
        private long frames, veiledFrames, misses, placedFrames;

        /** RenderFrameEvent.Pre: is the tunnel view on for this frame? Only an armed frame may place the camera. */
        public void pre(boolean on) {
            armed = on;
            veiled = false;
            placed = false;
        }

        public boolean armed() { return armed; }

        /** The camera was moved to the noclip pose this frame. */
        public void placed() { placed = true; }

        /** The veil ran this frame (the world cleared, the view's own things drawn). */
        public void veiled() { veiled = true; }

        /**
         * RenderFrameEvent.Post, before the frame reaches the screen. levelPresent: a level exists (a frame without one
         * drew no world); onNow: the view is still on. A frame that was armed (or had its camera moved) and got no veil
         * must be cleared now. If the view was turned off during the frame, a moved camera is still cleared but that is
         * no miss (the veil rightly stopped); a frame with the view on and no veil is a miss.
         */
        public Verdict post(boolean levelPresent, boolean onNow) {
            boolean wasArmed = armed, wasPlaced = placed, wasVeiled = veiled;
            armed = false;
            placed = false;
            veiled = false;
            if (!levelPresent || (!wasArmed && !wasPlaced)) return Verdict.NOTHING;
            frames++;
            if (wasPlaced) placedFrames++;
            if (wasVeiled) {
                veiledFrames++;
                inARow = 0;
                return Verdict.NOTHING;
            }
            if (!onNow) return wasPlaced ? Verdict.CLEAR : Verdict.NOTHING;
            misses++;
            inARow++;
            return inARow >= MISSES_TO_STOP ? Verdict.CLEAR_AND_STOP : Verdict.CLEAR;
        }

        public long frames() { return frames; }

        public long veiledFrames() { return veiledFrames; }

        public long misses() { return misses; }

        public long placedFrames() { return placedFrames; }

        /** A fresh start (the view turned on again). The totals stay, for status. */
        public void resetRun() { inARow = 0; }
    }
}
