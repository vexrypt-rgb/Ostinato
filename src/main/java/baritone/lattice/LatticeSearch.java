/*
 * Ostinato: bounded beam search and weighted A* over motion primitives. Not part of upstream Baritone.
 * Production pathing does not call this class. The benchmark calls it while latticeExperiment stays off.
 */
package baritone.lattice;

import baritone.contract.PlayerSimPhysics;
import baritone.contract.PlayerState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Lazy expansion: a primitive is simulated when it is selected, not when it is queued.
 * States are deduplicated on a 0.25-block position grid, 0.1 speed buckets, the yaw sector, and onGround.
 * Ordering is f, then g, then insertion order. No clock is part of the order.
 */
public final class LatticeSearch {
    public static final int NODE_BUDGET = 1500;
    public static final int TICK_BUDGET = 400;
    public static final int BEAM_WIDTH = 24;
    public static final double WEIGHT = 1.5;
    public static final double RADIUS = 0.5;
    private static final int BEAM_DEPTH = 64;
    private static final int OPEN_CAP = 100_000;

    public enum Method {
        BEAM, WEIGHTED
    }

    public static final class Hit {
        public final boolean reached;
        public final int expansions;
        public final int travelTicks;
        public final List<MotionPrimitive> primitives;

        Hit(boolean reached, int expansions, int travelTicks, List<MotionPrimitive> primitives) {
            this.reached = reached;
            this.expansions = expansions;
            this.travelTicks = travelTicks;
            this.primitives = primitives;
        }
    }

    private static final class Key {
        final int qx, qy, qz, bvx, bvz, sector;
        final boolean ground;

        Key(PlayerState s) {
            qx = (int) Math.floor(s.x() * 4);
            qy = (int) Math.floor(s.y() * 4);
            qz = (int) Math.floor(s.z() * 4);
            bvx = bucket(s.vx());
            bvz = bucket(s.vz());
            sector = PrimitiveCatalog.sector(s.yaw());
            ground = s.onGround();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) {
                return false;
            }
            return qx == k.qx && qy == k.qy && qz == k.qz && bvx == k.bvx && bvz == k.bvz
                    && sector == k.sector && ground == k.ground;
        }

        @Override
        public int hashCode() {
            int h = qx;
            h = h * 31 + qy;
            h = h * 31 + qz;
            h = h * 31 + bvx;
            h = h * 31 + bvz;
            h = h * 31 + sector;
            return h * 31 + (ground ? 1 : 0);
        }
    }

    private static final class Node {
        final Node parent;
        final PrimitiveCatalog.Template template;
        final int seq;
        PlayerSimPhysics sim;
        MotionPrimitive primitive;
        Key key;
        int g;
        double f;
        boolean simulated;

        Node(Node parent, PrimitiveCatalog.Template template, int seq) {
            this.parent = parent;
            this.template = template;
            this.seq = seq;
        }

        static int order(Node a, Node b) {
            int c = Double.compare(a.f, b.f);
            if (c != 0) {
                return c;
            }
            c = Integer.compare(a.g, b.g);
            if (c != 0) {
                return c;
            }
            return Integer.compare(a.seq, b.seq);
        }
    }

    private LatticeSearch() {
    }

    public static boolean reached(PlayerState state, double gx, double gy, double gz) {
        return PrimitiveCatalog.hit(state, gx, gy, gz, RADIUS);
    }

    public static boolean reached(double x, double y, double z, boolean onGround, double gx, double gy, double gz) {
        if (!onGround) {
            return false;
        }
        double dx = x - gx;
        double dy = y - gy;
        double dz = z - gz;
        return dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS;
    }

    /** Copies {@code start}. The caller's body is not modified. */
    public static Hit search(PlayerSimPhysics start, double gx, double gy, double gz, Method method) {
        PlayerSimPhysics rootSim = start.copy();
        Node root = new Node(null, null, 0);
        root.sim = rootSim;
        root.key = new Key(rootSim.state());
        root.g = 0;
        root.f = WEIGHT * heuristic(rootSim.state(), gx, gy, gz);
        root.simulated = true;
        Map<Key, Integer> best = new HashMap<>();
        best.put(root.key, 0);
        if (reached(rootSim.state(), gx, gy, gz)) {
            return new Hit(true, 0, 0, List.of());
        }
        return method == Method.BEAM ? beam(root, best, gx, gy, gz) : weighted(root, best, gx, gy, gz);
    }

    /**
     * Replays the primitive controls from a fresh settled body and returns the largest position error
     * against the stored predicted states. NaN when there is nothing to replay.
     */
    public static double divergence(PlayerSimPhysics start, List<MotionPrimitive> primitives) {
        if (primitives.isEmpty()) {
            return Double.NaN;
        }
        PlayerSimPhysics sim = start.copy();
        double max = 0;
        for (MotionPrimitive primitive : primitives) {
            for (int i = 0; i < primitive.ticks(); i++) {
                PlayerState got = sim.step(primitive.controls().get(i));
                PlayerState want = primitive.predicted().get(i);
                double dx = got.x() - want.x();
                double dy = got.y() - want.y();
                double dz = got.z() - want.z();
                double err = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (err > max) {
                    max = err;
                }
            }
        }
        return max;
    }

    public static int replayTicks(PlayerSimPhysics start, List<MotionPrimitive> primitives, double gx, double gy, double gz) {
        PlayerSimPhysics sim = start.copy();
        int ticks = 0;
        for (MotionPrimitive primitive : primitives) {
            for (int i = 0; i < primitive.ticks(); i++) {
                PlayerState state = sim.step(primitive.controls().get(i));
                ticks++;
                if (reached(state, gx, gy, gz)) {
                    return ticks;
                }
            }
        }
        return -1;
    }

    private static Hit beam(Node root, Map<Key, Integer> best, double gx, double gy, double gz) {
        List<Node> beam = new ArrayList<>();
        beam.add(root);
        int expansions = 0;
        int seq = 1;
        for (int depth = 0; depth < BEAM_DEPTH && expansions < NODE_BUDGET; depth++) {
            List<Node> stubs = new ArrayList<>();
            for (Node node : beam) {
                Integer known = best.get(node.key);
                if (known != null && known < node.g) {
                    continue;
                }
                if (node.g > TICK_BUDGET) {
                    continue;
                }
                for (PrimitiveCatalog.Template template : PrimitiveCatalog.templates()) {
                    Node stub = new Node(node, template, seq++);
                    stub.g = node.g + template.minTicks;
                    stub.f = stub.g + WEIGHT * heuristic(node.sim.state(), gx, gy, gz);
                    stubs.add(stub);
                }
            }
            stubs.sort(Node::order);
            List<Node> next = new ArrayList<>();
            Node bestGoal = null;
            for (Node stub : stubs) {
                if (next.size() >= BEAM_WIDTH || expansions >= NODE_BUDGET) {
                    break;
                }
                expansions++;
                Node grown = realize(stub, best, gx, gy, gz);
                if (grown == null) {
                    continue;
                }
                if (reached(grown.sim.state(), gx, gy, gz)) {
                    if (bestGoal == null || grown.g < bestGoal.g || (grown.g == bestGoal.g && grown.seq < bestGoal.seq)) {
                        bestGoal = grown;
                    }
                    continue;
                }
                next.add(grown);
            }
            if (bestGoal != null) {
                return hit(bestGoal, expansions);
            }
            if (next.isEmpty()) {
                break;
            }
            beam = next;
        }
        return new Hit(false, expansions, -1, List.of());
    }

    private static Hit weighted(Node root, Map<Key, Integer> best, double gx, double gy, double gz) {
        PriorityQueue<Node> open = new PriorityQueue<>(Node::order);
        open.add(root);
        int expansions = 0;
        int seq = 1;
        while (!open.isEmpty() && expansions < NODE_BUDGET && open.size() < OPEN_CAP) {
            Node node = open.poll();
            if (!node.simulated) {
                expansions++;
                Node grown = realize(node, best, gx, gy, gz);
                if (grown == null) {
                    continue;
                }
                if (reached(grown.sim.state(), gx, gy, gz)) {
                    return hit(grown, expansions);
                }
                if (grown.g > TICK_BUDGET) {
                    continue;
                }
                grown.f = grown.g + WEIGHT * heuristic(grown.sim.state(), gx, gy, gz);
                open.add(grown);
                continue;
            }
            Integer known = best.get(node.key);
            if (known != null && known < node.g) {
                continue;
            }
            if (node.g > TICK_BUDGET) {
                continue;
            }
            for (PrimitiveCatalog.Template template : PrimitiveCatalog.templates()) {
                Node stub = new Node(node, template, seq++);
                stub.g = node.g + template.minTicks;
                stub.f = stub.g + WEIGHT * heuristic(node.sim.state(), gx, gy, gz);
                open.add(stub);
            }
        }
        return new Hit(false, expansions, -1, List.of());
    }

    /** Simulates the stub. Returns null when it is infeasible or a worse duplicate. Goal states are kept. */
    private static Node realize(Node stub, Map<Key, Integer> best, double gx, double gy, double gz) {
        PrimitiveCatalog.Sim sim = PrimitiveCatalog.simulate(stub.parent.sim, stub.template, gx, gy, gz, RADIUS);
        if (sim == null) {
            return null;
        }
        stub.sim = sim.end;
        stub.primitive = sim.primitive;
        stub.g = stub.parent.g + sim.primitive.ticks();
        stub.key = new Key(sim.end.state());
        stub.simulated = true;
        boolean goal = reached(sim.end.state(), gx, gy, gz);
        Integer prev = best.get(stub.key);
        if (!goal && prev != null && prev <= stub.g) {
            return null;
        }
        if (prev == null || stub.g < prev) {
            best.put(stub.key, stub.g);
        }
        return stub;
    }

    private static Hit hit(Node node, int expansions) {
        List<MotionPrimitive> path = new ArrayList<>();
        for (Node cursor = node; cursor.primitive != null; cursor = cursor.parent) {
            path.add(cursor.primitive);
        }
        Collections.reverse(path);
        return new Hit(true, expansions, node.g, List.copyOf(path));
    }

    private static double heuristic(PlayerState state, double gx, double gy, double gz) {
        double horiz = Math.hypot(state.x() - gx, state.z() - gz);
        return horiz / PrimitiveCatalog.HEURISTIC_SPEED + Math.max(0, gy - state.y()) * 4;
    }

    private static int bucket(double speed) {
        return (int) Math.max(0, Math.min(15, Math.floor(speed * 10) + 8));
    }
}
