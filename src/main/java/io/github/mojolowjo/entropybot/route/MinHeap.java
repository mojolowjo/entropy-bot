package io.github.mojolowjo.entropybot.route;

import java.util.Arrays;

/** A binary min-heap of (double key, int value), no boxing. Duplicates allowed (lazy decrease-key). */
final class MinHeap {
    private double[] keys;
    private int[] vals;
    private int size;

    MinHeap(int cap) {
        keys = new double[Math.max(4, cap)];
        vals = new int[keys.length];
    }

    boolean isEmpty() {
        return size == 0;
    }

    double peekKey() {
        return keys[0];
    }

    void push(double k, int v) {
        if (size == keys.length) {
            keys = Arrays.copyOf(keys, size * 2);
            vals = Arrays.copyOf(vals, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (keys[p] <= k) break;
            keys[i] = keys[p];
            vals[i] = vals[p];
            i = p;
        }
        keys[i] = k;
        vals[i] = v;
    }

    int pop() {
        int top = vals[0];
        size--;
        if (size > 0) {
            double k = keys[size];
            int v = vals[size];
            int i = 0;
            while (true) {
                int c = 2 * i + 1;
                if (c >= size) break;
                if (c + 1 < size && keys[c + 1] < keys[c]) c++;
                if (keys[c] >= k) break;
                keys[i] = keys[c];
                vals[i] = vals[c];
                i = c;
            }
            keys[i] = k;
            vals[i] = v;
        }
        return top;
    }
}
