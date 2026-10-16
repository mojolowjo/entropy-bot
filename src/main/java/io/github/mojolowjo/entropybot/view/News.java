package io.github.mojolowjo.entropybot.view;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 0.32.0 (operator view, package 1): what happened since the last reply (NEW): death, damage, a hostile near,
 * night/day, a chain's detour. Highest priority first, the same text once. Plain Java. Loader API: none.
 */
public final class News {
    public static final int DEATH = 4, DAMAGE = 3, DETOUR = 2, HOSTILE = 2, TIME = 1;
    static final int CAP = 20;
    /** The bot's one news buffer (plain Java, so the chain runner can push to it). */
    public static final News GLOBAL = new News();

    private record Item(int prio, String text, long seq) {}

    private final List<Item> items = new ArrayList<>();
    private long seq, pushed;

    public synchronized void push(int prio, String text) {
        if (text == null || text.isBlank()) return;
        for (Item i : items) if (i.text.equals(text)) return;
        items.add(new Item(prio, text, ++seq));
        pushed++;
        while (items.size() > CAP) items.remove(0);
    }

    /** Everything since the last take, highest first (newest first within a priority), and clears. */
    public synchronized List<String> take() {
        List<Item> l = new ArrayList<>(items);
        items.clear();
        l.sort(Comparator.comparingInt((Item i) -> -i.prio).thenComparingLong(i -> -i.seq));
        List<String> out = new ArrayList<>();
        for (Item i : l) out.add(i.text);
        return out;
    }

    public synchronized String counters() { return "news: " + pushed + " pushed, " + items.size() + " waiting"; }
}
