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

package baritone.pathing.calc.openset;

import baritone.pathing.calc.PathNode;

import java.util.Arrays;

/**
 * A binary heap implementation of an open set. This is the one used in the AStarPathFinder.
 *
 * @author leijurv
 *
 * Parallel {@code keys} array (sift on contiguous doubles) ported from Soprano
 * (https://github.com/AverWasTaken/soprano, LGPL-3.0, by AverWasTaken),
 * src/main/java/baritone/pathing/calc/openset/BinaryHeapOpenSet.java.
 */
public final class BinaryHeapOpenSet implements IOpenSet {

    /**
     * The initial capacity of the heap (2^10)
     */
    private static final int INITIAL_CAPACITY = 1024;

    /**
     * The array backing the heap
     */
    private PathNode[] array;

    // keys[i] is always array[i].combinedCost
    // so sifting compares doubles that are right next to each other instead of chasing a pointer per level
    // into some PathNode that fell out of cache ages ago. this was worth 15% by itself, i was not expecting that
    private double[] keys;

    /**
     * The size of the heap
     */
    private int size;

    public BinaryHeapOpenSet() {
        this(INITIAL_CAPACITY);
    }

    public BinaryHeapOpenSet(int size) {
        this.size = 0;
        this.array = new PathNode[size];
        this.keys = new double[size];
    }

    public int size() {
        return size;
    }

    // Ostinato: Phase 2: 4-ary heap rooted at 0 (was binary rooted at 1). Half the levels, so siftUp (10% of planner CPU,
    // mostly cache misses on parent heapPosition writes) moves half as many nodes; the 4 child keys share a cache line.
    @Override
    public final void insert(PathNode value) {
        if (size >= array.length) {
            array = Arrays.copyOf(array, array.length << 1);
            keys = Arrays.copyOf(keys, keys.length << 1);
        }
        siftUp(size++, value, value.combinedCost);
    }

    @Override
    public final void update(PathNode val) {
        // cost only ever goes down so it only ever goes up (the heap i mean)
        siftUp(val.heapPosition, val, val.combinedCost);
    }

    private void siftUp(int index, PathNode val, double cost) {
        PathNode[] array = this.array;
        double[] keys = this.keys;
        while (index > 0) {
            int parentInd = (index - 1) >>> 2;
            double pk = keys[parentInd];
            if (pk <= cost) {
                break;
            }
            PathNode parentNode = array[parentInd];
            array[index] = parentNode;
            keys[index] = pk;
            parentNode.heapPosition = index;
            index = parentInd;
        }
        array[index] = val;
        keys[index] = cost;
        val.heapPosition = index;
    }

    @Override
    public final boolean isEmpty() {
        return size == 0;
    }

    @Override
    public final PathNode removeLowest() {
        if (size == 0) {
            throw new IllegalStateException("Cannot remove from empty heap");
        }
        PathNode[] array = this.array;
        double[] keys = this.keys;
        PathNode result = array[0];
        result.heapPosition = -1;
        int last = --size;
        PathNode val = array[last];
        double cost = keys[last];
        array[last] = null;
        if (last == 0) {
            return result;
        }
        int index = 0;
        while (true) {
            int first = (index << 2) + 1;
            if (first >= last) {
                break;
            }
            int best = first;
            double bestCost = keys[first];
            int end = Math.min(first + 4, last);
            for (int c = first + 1; c < end; c++) {
                double k = keys[c];
                if (k < bestCost) {
                    best = c;
                    bestCost = k;
                }
            }
            if (cost <= bestCost) {
                break;
            }
            PathNode childNode = array[best];
            array[index] = childNode;
            keys[index] = bestCost;
            childNode.heapPosition = index;
            index = best;
        }
        array[index] = val;
        keys[index] = cost;
        val.heapPosition = index;
        return result;
    }
}
