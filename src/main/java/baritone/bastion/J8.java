package baritone.bastion;

import java.util.*;

/** Java 8 stand-ins for List.of / Set.of / Map.of (1.16.1 builds on Java 8). Immutable like the originals. */
public final class J8 {
    private J8() {}
    @SafeVarargs public static <T> List<T> listOf(T... a) { return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(a))); }
    @SafeVarargs public static <T> Set<T> setOf(T... a) { return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(a))); }
    public static <T> List<T> listCopy(Collection<? extends T> c) { return Collections.unmodifiableList(new ArrayList<>(c)); }
    public static <T> Set<T> setCopy(Collection<? extends T> c) { return Collections.unmodifiableSet(new HashSet<>(c)); }
    public static <K, V> Map.Entry<K, V> entry(K k, V v) { return new AbstractMap.SimpleImmutableEntry<>(k, v); }
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> mapOf(Object... kv) {
        Map<K, V> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((K) kv[i], (V) kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }
}
