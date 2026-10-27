package io.github.mojolowjo.entropybot.aa;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 0.39.z78 D6 (the incremental planner): value equality, a hash and a fresh copy for the planner's own values - records
 * component by component (an array component by its elements: {@code Step.ChopTree}'s cell is an {@code int[]}, which a
 * record's own equals compares by reference; {@code Step.Collect}'s own equals leaves its cell out), lists and ordered
 * maps / sets in their iteration order (a LinkedHashMap's order is part of the planner's state: the bag's order decides
 * which member a take names), unordered ones (a hash map, an identity map: the leg starts) by their content, map entries
 * by key and value, arrays by elements, everything else by {@code equals}. The node cache keys and checks nodes with it
 * ({@link PlanNodes}); the replay test compares the old resolve's chains with the incremental one's by it. Pure. Loader
 * notes: java.lang.reflect.RecordComponent only (no game types).
 */
public final class Deep {
    private Deep() {}

    private static final Map<Class<?>, RecordComponent[]> PARTS = new ConcurrentHashMap<>();

    private static RecordComponent[] parts(Class<?> k) {
        return PARTS.computeIfAbsent(k, c -> {
            RecordComponent[] r = c.getRecordComponents();
            for (RecordComponent p : r) p.getAccessor().setAccessible(true);
            return r;
        });
    }

    private static Object get(RecordComponent p, Object o) {
        try {
            return p.getAccessor().invoke(o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Deep: the record component " + p.getName() + " of " + o.getClass().getName() + " could not be read", e);
        }
    }

    /** Value equality as the class doc says. */
    public static boolean equal(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a instanceof Double x && b instanceof Double y) return Double.doubleToLongBits(x) == Double.doubleToLongBits(y);
        if (a instanceof Float x && b instanceof Float y) return Float.floatToIntBits(x) == Float.floatToIntBits(y);
        Class<?> k = a.getClass();
        if (k != b.getClass()) {
            // two lists or two maps of different classes (List.of / ArrayList) compare by their elements
            if (a instanceof List<?> la && b instanceof List<?> lb) return seq(la, lb);
            if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) return maps(ma, mb);
            if (a instanceof java.util.Set<?> sa && b instanceof java.util.Set<?> sb) return ordered(sa) && ordered(sb) ? seq(sa, sb) : bag(sa, sb);
            if (a instanceof Map.Entry<?, ?> x && b instanceof Map.Entry<?, ?> y) return equal(x.getKey(), y.getKey()) && equal(x.getValue(), y.getValue());
            return false;
        }
        if (k.isRecord()) {
            for (RecordComponent p : parts(k)) if (!equal(get(p, a), get(p, b))) return false;
            return true;
        }
        if (k.isArray()) {
            if (a instanceof int[] x) return Arrays.equals(x, (int[]) b);
            if (a instanceof long[] x) return Arrays.equals(x, (long[]) b);
            if (a instanceof double[] x) return Arrays.equals(x, (double[]) b);
            if (a instanceof boolean[] x) return Arrays.equals(x, (boolean[]) b);
            if (a instanceof Object[] x) {
                Object[] y = (Object[]) b;
                if (x.length != y.length) return false;
                for (int i = 0; i < x.length; i++) if (!equal(x[i], y[i])) return false;
                return true;
            }
            return Arrays.deepEquals(new Object[]{a}, new Object[]{b});
        }
        if (a instanceof List<?> la) return seq(la, (List<?>) b);
        if (a instanceof Map<?, ?> ma) return maps(ma, (Map<?, ?>) b);
        if (a instanceof Map.Entry<?, ?> x) { Map.Entry<?, ?> y = (Map.Entry<?, ?>) b; return equal(x.getKey(), y.getKey()) && equal(x.getValue(), y.getValue()); }
        if (a instanceof java.util.Set<?> sa && !ordered(sa)) return bag(sa, (Collection<?>) b);
        if (a instanceof Collection<?> ca) return seq(ca, (Collection<?>) b);
        return a.equals(b);
    }

    /** A map or a set whose iteration order is part of its value (insertion or sorted order); a hash map's or an identity map's is not. */
    private static boolean ordered(Object o) {
        return o instanceof java.util.SequencedMap<?, ?> || o instanceof java.util.SequencedCollection<?>;
    }

    /** Two collections with the same elements in any order (each matched once). */
    private static boolean bag(Collection<?> a, Collection<?> b) {
        if (a.size() != b.size()) return false;
        List<Object> left = new java.util.ArrayList<>(b);
        outer:
        for (Object x : a) {
            for (int i = 0; i < left.size(); i++) if (equal(x, left.get(i))) { left.remove(i); continue outer; }
            return false;
        }
        return true;
    }

    private static boolean seq(Collection<?> a, Collection<?> b) {
        if (a.size() != b.size()) return false;
        Iterator<?> i = a.iterator(), j = b.iterator();
        while (i.hasNext()) if (!equal(i.next(), j.next())) return false;
        return true;
    }

    private static boolean maps(Map<?, ?> a, Map<?, ?> b) {
        if (a.size() != b.size()) return false;
        if (!ordered(a) || !ordered(b)) return bag(entries(a), entries(b));
        Iterator<? extends Map.Entry<?, ?>> i = a.entrySet().iterator(), j = b.entrySet().iterator();
        while (i.hasNext()) {
            var x = i.next();
            var y = j.next();
            if (!equal(x.getKey(), y.getKey()) || !equal(x.getValue(), y.getValue())) return false;
        }
        return true;
    }

    private static List<List<Object>> entries(Map<?, ?> m) {
        List<List<Object>> out = new java.util.ArrayList<>(m.size());
        for (var e : m.entrySet()) out.add(java.util.Arrays.asList(e.getKey(), e.getValue()));
        return out;
    }

    /** A hash that agrees with {@link #equal} (records by their parts, lists / ordered maps in order, unordered ones summed, arrays by elements). */
    public static int hash(Object o) {
        if (o == null) return 0;
        if (o instanceof Double d) return Long.hashCode(Double.doubleToLongBits(d));
        Class<?> k = o.getClass();
        if (k.isRecord()) {
            int h = k.hashCode();
            for (RecordComponent p : parts(k)) h = 31 * h + hash(get(p, o));      // structural: a hash's multiplier
            return h;
        }
        if (o instanceof int[] x) return Arrays.hashCode(x);
        if (o instanceof long[] x) return Arrays.hashCode(x);
        if (o instanceof double[] x) return Arrays.hashCode(x);
        if (o instanceof Object[] x) {
            int h = 1;
            for (Object e : x) h = 31 * h + hash(e);      // structural: a hash's multiplier
            return h;
        }
        if (o instanceof Map<?, ?> m) {
            int h = 7;
            boolean ord = ordered(m);
            for (var e : m.entrySet()) {
                int eh = 17 * hash(e.getKey()) + hash(e.getValue());      // structural: a hash's multiplier
                h = ord ? 31 * h + eh : h + eh;                          // structural: a hash's multiplier (an unordered map: summed)
            }
            return h;
        }
        if (o instanceof Map.Entry<?, ?> e) return 17 * hash(e.getKey()) + hash(e.getValue());      // structural: a hash's multiplier
        if (o instanceof java.util.Set<?> s && !ordered(s)) {
            int h = 5;
            for (Object e : s) h += hash(e);
            return h;
        }
        if (o instanceof Collection<?> c) {
            int h = 3;
            for (Object e : c) h = 31 * h + hash(e);      // structural: a hash's multiplier
            return h;
        }
        return o.hashCode();
    }

    /**
     * A new object equal to the record {@code r} (its canonical constructor with its own parts; an array part copied by the
     * constructor where the record does so): every resolve hands out its own step objects, as before D6 - the run's maps
     * keyed by a step's identity never see one object in two chains. Not a record: {@code r} itself.
     */
    @SuppressWarnings("unchecked")
    public static <T> T fresh(T r) {
        if (r == null || !r.getClass().isRecord()) return r;
        Class<?> k = r.getClass();
        RecordComponent[] ps = parts(k);
        Class<?>[] types = new Class<?>[ps.length];
        Object[] args = new Object[ps.length];
        for (int i = 0; i < ps.length; i++) {
            types[i] = ps[i].getType();
            args[i] = get(ps[i], r);
            if (args[i] instanceof int[] a) args[i] = a.clone();
        }
        try {
            var c = k.getDeclaredConstructor(types);
            c.setAccessible(true);
            return (T) c.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Deep: no fresh copy of " + k.getName(), e);
        }
    }
}
