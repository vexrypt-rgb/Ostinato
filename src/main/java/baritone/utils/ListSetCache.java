/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.utils;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;

import java.util.List;

/**
 * Ostinato: O(1) membership for a list-valued setting that the planner checks per node (blocksToAvoid,
 * blocksToDisallowBreaking, ...). The hash set is rebuilt when the setting's list object or its size changes, which
 * covers every way settings are edited (the setting command and the GUI assign a new list). Block identity equality
 * is what List.contains used, so the answer is the same.
 */
public final class ListSetCache<T> {

    private volatile Snapshot<T> snap;

    private record Snapshot<T>(List<T> list, int size, ReferenceOpenHashSet<T> set) {}

    public boolean contains(List<T> list, T value) {
        Snapshot<T> s = snap;
        if (s == null || s.list != list || s.size != list.size()) {
            s = new Snapshot<>(list, list.size(), new ReferenceOpenHashSet<>(list));
            snap = s;
        }
        return s.set.contains(value);
    }
}
