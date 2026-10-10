/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.pathing.physics;

import baritone.pathing.kinematic.PlayerSim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Tungsten-style pathfinder skeleton: A* over simulated player states instead of blocks.
 * Each edge holds one input for {@link #HOLD} ticks through {@link PlayerSim}, so the result is
 * a key sequence that vanilla physics will reproduce, with jumps, sprint-jumps and falls found by search.
 * <p>
 * Not wired in yet. Next steps: seed the heuristic from Baritone's block path, add swim/sneak
 * inputs, and replay the plan tick by tick with re-planning when the real player drifts.
 */
public final class PhysicsPathfinder {

    /** One held input. */
    public static final class Action {
        public final float yaw;
        public final boolean forward, sprint, jump;

        Action(float yaw, boolean forward, boolean sprint, boolean jump) {
            this.yaw = yaw;
            this.forward = forward;
            this.sprint = sprint;
            this.jump = jump;
        }
    }

    private static final class Node implements Comparable<Node> {
        final PlayerSim sim;
        final Node parent;
        final Action action;
        final int ticks;
        final double f;

        Node(PlayerSim sim, Node parent, Action action, int ticks, double f) {
            this.sim = sim;
            this.parent = parent;
            this.action = action;
            this.ticks = ticks;
            this.f = f;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(f, o.f);
        }
    }

    public static final int HOLD = 2;
    /** Sprint speed on flat ground, blocks per tick: the admissible heuristic's divisor. */
    private static final double MAX_SPEED = 0.29;
    private static final List<Action> ACTIONS = new ArrayList<>();

    static {
        for (int i = 0; i < 16; i++) {
            float yaw = i * 22.5f;
            ACTIONS.add(new Action(yaw, true, true, false));
            ACTIONS.add(new Action(yaw, true, true, true));
            ACTIONS.add(new Action(yaw, true, false, false));
        }
        ACTIONS.add(new Action(0, false, false, false));
    }

    private final PlayerSim.World world;
    private final int maxNodes;

    public PhysicsPathfinder(PlayerSim.World world, int maxNodes) {
        this.world = world;
        this.maxNodes = maxNodes;
    }

    /** Plans from {@code start} until standing within {@code radius} of the goal; null if none found. */
    public List<Action> plan(PlayerSim start, double gx, double gy, double gz, double radius) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        Map<Long, Integer> best = new HashMap<>();
        open.add(new Node(new PlayerSim(world).copyFrom(start), null, null, 0, h(start, gx, gy, gz)));
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < maxNodes) {
            Node n = open.poll();
            PlayerSim s = n.sim;
            if (s.onGround && sq(s.x - gx) + sq(s.y - gy) + sq(s.z - gz) <= radius * radius) {
                return unwind(n);
            }
            for (Action a : ACTIONS) {
                PlayerSim c = new PlayerSim(world).copyFrom(s);
                for (int t = 0; t < HOLD; t++) c.tick(a.yaw, a.forward, a.sprint, a.jump);
                if (c.y < gy - 64) continue;
                int g = n.ticks + HOLD;
                Integer seen = best.putIfAbsent(key(c), g);
                if (seen != null) {
                    if (seen <= g) continue;
                    best.put(key(c), g);
                }
                open.add(new Node(c, n, a, g, g + h(c, gx, gy, gz)));
            }
        }
        return null;
    }

    private static double h(PlayerSim s, double gx, double gy, double gz) {
        return Math.sqrt(sq(s.x - gx) + sq(s.z - gz)) / MAX_SPEED + Math.max(0, gy - s.y) * 4;
    }

    /** Dedup key: position to 0.25 blocks, horizontal speed to 0.1, grounded flag. */
    private static long key(PlayerSim s) {
        long k = (long) Math.floor(s.x * 4) & 0xFFFFF;
        k = k << 20 | ((long) Math.floor(s.z * 4) & 0xFFFFF);
        k = k << 12 | ((long) Math.floor(s.y * 4) & 0xFFF);
        // each part in bits of its own: two states that differ in any of them never share a key
        k = k << 4 | speedBucket(s.vx);
        k = k << 4 | speedBucket(s.vz);
        return k << 1 | (s.onGround ? 1 : 0);
    }

    /** Speed in steps of 0.1 as 0..15, which covers the +-0.8 blocks per tick a player on foot stays within. */
    private static long speedBucket(double v) {
        return Math.max(0, Math.min(15, (long) Math.floor(v * 10) + 8));
    }

    private static List<Action> unwind(Node n) {
        List<Action> out = new ArrayList<>();
        for (; n.action != null; n = n.parent) {
            for (int t = 0; t < HOLD; t++) out.add(n.action);
        }
        Collections.reverse(out);
        return out;
    }

    private static double sq(double v) {
        return v * v;
    }
}
