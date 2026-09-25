package baritone.utils.fault;

import java.util.ArrayDeque;

/**
 * Generic A<->B flip detector over a stream of names (active child task, phase, ...).
 * Every earlier guard knew only the pair it was written for; this one catches any pair.
 */
public final class LoopDetector {

    private final int flips;
    private final long windowMs;
    private final ArrayDeque<String> names = new ArrayDeque<>();
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    public LoopDetector(int flips, long windowMs) {
        this.flips = flips;
        this.windowMs = windowMs;
    }

    public void clear() {
        names.clear();
        times.clear();
    }

    /** Returns "A<->B xN" once the last N+1 changes alternate between two names inside the window, else null. */
    public String feed(String name, long now) {
        if (name == null) name = "-";
        if (!names.isEmpty() && names.peekLast().equals(name)) return null;
        names.addLast(name);
        times.addLast(now);
        while (!times.isEmpty() && now - times.peekFirst() > windowMs) {
            times.removeFirst();
            names.removeFirst();
        }
        while (names.size() > flips + 1) {
            names.removeFirst();
            times.removeFirst();
        }
        if (names.size() < flips + 1) return null;
        String[] arr = names.toArray(new String[0]);
        String a = arr[0], b = arr[1];
        for (int i = 0; i < arr.length; i++) {
            if (!arr[i].equals(i % 2 == 0 ? a : b)) return null;
        }
        clear(); // report once per completed loop
        return a + "<->" + b + " x" + flips;
    }
}
