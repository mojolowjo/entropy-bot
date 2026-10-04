package io.github.mojolowjo.entropybot.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * B7e (E1): the reflex whispers to the owner the bridge's syncReflexes sent, once per transition and with its texts:
 * the start of a retreat, a denied dimension (once each time the bot ends up in one), and running out of food at
 * food 6 or less (said again only after food came back). Pure: {@link #step} gets the reflex's state once a tick and
 * returns what to whisper.
 */
public final class ReflexNotes {
    public static final int NO_FOOD_AT = 6;

    private String reflexWas = "none";
    private String deniedNoted;
    private boolean noFoodWarned;

    /**
     * kind: the reflex in lower case ("none", "eating", "fighting", "fleeing", "retreating", "fetching"); target: what
     * it fights or flees (null = "a monster"); health 0..20; deniedDim: the off-limits dimension the bot is in, else
     * null; noFood: the reflexes found nothing to eat; food: the food bar 0..20.
     */
    public List<String> step(String kind, String target, float health, String deniedDim, boolean noFood, int food) {
        List<String> out = new ArrayList<>();
        String k = kind == null ? "none" : kind.toLowerCase();
        String prev = reflexWas;
        reflexWas = k;
        if (k.equals("retreating") && !prev.equals("retreating")) {
            out.add("Low health (" + Math.round(health) + "/20), retreating from " + (target == null || target.isEmpty() ? "a monster" : target));
        }
        if (deniedDim != null && !deniedDim.equals(deniedNoted)) {
            deniedNoted = deniedDim;
            out.add("I ended up in " + deniedDim + " - sending /home. Tell me if I should stay put instead.");
        } else if (deniedDim == null) {
            deniedNoted = null;
        }
        if (noFood && !noFoodWarned && food <= NO_FOOD_AT) {
            noFoodWarned = true;
            out.add("I'm out of food (food " + food + "/20) - I can keep working but will starve eventually.");
        } else if (!noFood) {
            noFoodWarned = false;
        }
        return out;
    }
}
