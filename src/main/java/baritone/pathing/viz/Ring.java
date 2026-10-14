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

package baritone.pathing.viz;

import java.util.function.Supplier;

/**
 * Ostinato: a fixed-capacity ring buffer that reuses its slots, so recording a tick allocates nothing once warm.
 * {@link #get(int)} counts from the oldest kept entry.
 */
public final class Ring<T> {

    private final Object[] slots;
    private final Supplier<T> factory;
    private int head, size;

    public Ring(int capacity, Supplier<T> factory) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity " + capacity);
        }
        this.slots = new Object[capacity];
        this.factory = factory;
    }

    /** The slot for a new newest entry: a recycled one (its old values still in it) once the ring is full. */
    @SuppressWarnings("unchecked")
    public T add() {
        int i = (head + size) % slots.length;
        if (size == slots.length) {
            head = (head + 1) % slots.length;
        } else {
            size++;
        }
        if (slots[i] == null) {
            slots[i] = factory.get();
        }
        return (T) slots[i];
    }

    @SuppressWarnings("unchecked")
    public T get(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i + " of " + size);
        }
        return (T) slots[(head + i) % slots.length];
    }

    public T newest() {
        return size == 0 ? null : get(size - 1);
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return slots.length;
    }

    public void clear() {
        head = size = 0;
    }
}
