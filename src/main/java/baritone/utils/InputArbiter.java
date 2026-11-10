/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.api.utils.input.Input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Ostinato: one complete key frame per seal. A missing key is up. Game thread only.

/**
 * Leases player inputs to named owners. One owner holds a resource at a time.
 * Emergency beats a normal claim, then higher priority, then the smaller owner id.
 */
public final class InputArbiter {

    /** Exclusive group of keys. */
    public enum Resource {
        MOVE(Input.MOVE_FORWARD, Input.MOVE_BACK, Input.MOVE_LEFT, Input.MOVE_RIGHT, Input.SPRINT),
        JUMP(Input.JUMP),
        SNEAK(Input.SNEAK),
        CLICK(Input.CLICK_LEFT, Input.CLICK_RIGHT);

        private final Input[] keys;

        Resource(Input... keys) {
            this.keys = keys;
        }
    }

    /** Result of {@link #submit}. */
    public enum Submit {
        OK,
        /** Epoch does not match, or the owner is unknown. */
        STALE,
        /** Epoch matches, but this owner holds nothing. */
        NO_LEASE
    }

    /** How a lease ended. Preemption leaves this unset. */
    public enum End {
        RELEASED,
        FAILED
    }

    /** One owner's claim. The arbiter mutates it; callers only read it. */
    public static final class Lease {
        private final String id;
        private int priority;
        private boolean emergency;
        private final Set<Resource> resources = EnumSet.noneOf(Resource.class);
        private long epoch;
        private boolean alive;
        private boolean cancelRequested;
        private End end;
        private Map<Input, Boolean> pending;

        private Lease(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public long epoch() {
            return epoch;
        }

        public boolean alive() {
            return alive;
        }

        public boolean holds(Resource resource) {
            return alive && resources.contains(resource);
        }

        public boolean cancelRequested() {
            return cancelRequested;
        }

        public End end() {
            return end;
        }
    }

    private final Map<String, Lease> owners = new HashMap<>();
    private final Map<Input, Boolean> applied = blank();

    /**
     * Take {@code resources} for {@code id}. Always bumps that owner's epoch, so a frame submitted
     * under the previous epoch is {@link Submit#STALE}. A loser who keeps at least one resource keeps
     * their epoch. A loser left with nothing is no longer alive and their epoch bumps once more.
     */
    public Lease claim(String id, int priority, boolean emergency, Resource... resources) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("owner");
        }
        Lease lease = owners.get(id);
        if (lease == null) {
            lease = new Lease(id);
            owners.put(id, lease);
        }
        lease.priority = priority;
        lease.emergency = emergency;
        lease.epoch++;
        lease.pending = null;
        lease.end = null;
        lease.cancelRequested = false;

        EnumSet<Resource> want = EnumSet.noneOf(Resource.class);
        if (resources != null) {
            Collections.addAll(want, resources);
        }
        EnumSet<Resource> kept = EnumSet.noneOf(Resource.class);
        for (Resource resource : want) {
            Lease holder = holder(resource, lease);
            if (holder != null && !beats(lease, holder)) {
                continue;
            }
            if (holder != null) {
                holder.resources.remove(resource);
                clear(resource);
                if (holder.resources.isEmpty()) {
                    holder.alive = false;
                    holder.pending = null;
                    holder.epoch++;
                }
            }
            kept.add(resource);
        }
        for (Resource resource : EnumSet.copyOf(lease.resources)) {
            if (!kept.contains(resource)) {
                lease.resources.remove(resource);
                clear(resource);
            }
        }
        lease.resources.clear();
        lease.resources.addAll(kept);
        lease.alive = !kept.isEmpty();
        if (!lease.alive) {
            lease.pending = null;
        }
        return lease;
    }

    /**
     * Queue one frame. Keys outside the owner's lease are ignored. A leased key missing from
     * {@code frame} is up. The frame is not applied until {@link #seal()}.
     */
    public Submit submit(String id, long epoch, Map<Input, Boolean> frame) {
        Lease lease = owners.get(id);
        if (lease == null || lease.epoch != epoch) {
            return Submit.STALE;
        }
        if (!lease.alive) {
            return Submit.NO_LEASE;
        }
        EnumMap<Input, Boolean> pending = new EnumMap<>(Input.class);
        for (Resource resource : lease.resources) {
            for (Input key : resource.keys) {
                pending.put(key, frame != null && Boolean.TRUE.equals(frame.get(key)));
            }
        }
        lease.pending = pending;
        return Submit.OK;
    }

    /** Ask to stop. The lease and the last sealed keys stay until {@link #confirmRelease}. */
    public void requestCancel(String id) {
        Lease lease = owners.get(id);
        if (lease == null || !lease.alive || lease.end != null) {
            return;
        }
        lease.cancelRequested = true;
    }

    /** Drop the lease, bump the epoch, and clear that owner's keys. */
    public void confirmRelease(String id) {
        finish(id, End.RELEASED);
    }

    /** Same key release as {@link #confirmRelease}, marked {@link End#FAILED}, without requesting cancel. */
    public void fail(String id) {
        finish(id, End.FAILED);
    }

    /**
     * Release every living lease, as on disconnect or world change. Each owner ends {@link End#RELEASED}
     * and its epoch bumps, so a frame queued before the call is {@link Submit#STALE}.
     */
    public void releaseAll() {
        for (String id : new ArrayList<>(owners.keySet())) {
            if (owners.get(id).alive) {
                finish(id, End.RELEASED);
            }
        }
    }

    /**
     * Paint this tick. Starts from every key up, then each living owner. An owner who did not submit
     * since the last seal paints their resources up. A second seal with no new frame clears them.
     */
    public Map<Input, Boolean> seal() {
        Map<Input, Boolean> next = blank();
        List<Lease> living = new ArrayList<>();
        for (Lease lease : owners.values()) {
            if (lease.alive) {
                living.add(lease);
            }
        }
        living.sort((a, b) -> a.id.compareTo(b.id));
        for (Lease lease : living) {
            for (Resource resource : lease.resources) {
                for (Input key : resource.keys) {
                    boolean down = lease.pending != null && Boolean.TRUE.equals(lease.pending.get(key));
                    next.put(key, down);
                }
            }
            lease.pending = null;
        }
        applied.clear();
        applied.putAll(next);
        return applied();
    }

    /** Defensive copy of the keys last sealed, plus any release that has cleared keys since. */
    public Map<Input, Boolean> applied() {
        return new EnumMap<>(applied);
    }

    private void finish(String id, End end) {
        Lease lease = owners.get(id);
        if (lease == null || lease.end != null) {
            return;
        }
        for (Resource resource : EnumSet.copyOf(lease.resources)) {
            lease.resources.remove(resource);
            clear(resource);
        }
        lease.pending = null;
        lease.alive = false;
        lease.epoch++;
        lease.end = end;
    }

    private Lease holder(Resource resource, Lease except) {
        for (Lease other : owners.values()) {
            if (other != except && other.alive && other.resources.contains(resource)) {
                return other;
            }
        }
        return null;
    }

    /** Emergency first, then higher priority, then the smaller id. */
    private static boolean beats(Lease challenger, Lease holder) {
        if (challenger.emergency != holder.emergency) {
            return challenger.emergency;
        }
        if (challenger.priority != holder.priority) {
            return challenger.priority > holder.priority;
        }
        return challenger.id.compareTo(holder.id) < 0;
    }

    private void clear(Resource resource) {
        for (Input key : resource.keys) {
            applied.put(key, Boolean.FALSE);
        }
    }

    private static EnumMap<Input, Boolean> blank() {
        EnumMap<Input, Boolean> frame = new EnumMap<>(Input.class);
        for (Input input : Input.values()) {
            frame.put(input, Boolean.FALSE);
        }
        return frame;
    }
}
