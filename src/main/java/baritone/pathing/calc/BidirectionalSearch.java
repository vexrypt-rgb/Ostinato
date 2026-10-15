package baritone.pathing.calc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Bidirectional A* on a small explicit graph. The live world search does not call this.
 * Baritone's moves have many equal-cost ties (two cardinals versus another order), and the path
 * bidirectional search returns among those ties is not the path forward A* returns. Identical paths
 * are required, so this stays a checked algorithm for graphs whose optimal path is unique.
 */
public final class BidirectionalSearch {

    public static final class Edge {
        public final int to;
        public final double cost;

        public Edge(int to, double cost) {
            this.to = to;
            this.cost = cost;
        }
    }

    public interface Graph {
        int nodeCount();

        int start();

        int goal();

        List<Edge> out(int node);

        /** Must be consistent. {@code heuristic(n, n) == 0}. */
        double heuristic(int from, int to);
    }

    private BidirectionalSearch() {}

    public static int[] forward(Graph g) {
        return search(g, false);
    }

    public static int[] bidirectional(Graph g) {
        return search(g, true);
    }

    private static int[] search(Graph g, boolean both) {
        int n = g.nodeCount();
        int start = g.start();
        int goal = g.goal();
        if (start == goal) {
            return new int[]{start};
        }
        double[] gf = fill(n);
        double[] gb = fill(n);
        int[] pf = parent(n);
        int[] pb = parent(n);
        boolean[] closedF = new boolean[n];
        boolean[] closedB = new boolean[n];
        PriorityQueue<Node> openF = new PriorityQueue<>(Comparator.comparingDouble(a -> a.f));
        PriorityQueue<Node> openB = new PriorityQueue<>(Comparator.comparingDouble(a -> a.f));
        gf[start] = 0;
        openF.add(new Node(start, g.heuristic(start, goal)));
        if (both) {
            gb[goal] = 0;
            openB.add(new Node(goal, g.heuristic(goal, start)));
        }
        List<Edge>[] rev = both ? reverse(g) : null;
        double best = Double.POSITIVE_INFINITY;
        int meetF = -1;
        int meetB = -1;
        while (!openF.isEmpty() && (!both || !openB.isEmpty())) {
            double minF = openF.peek().f;
            double minB = both ? openB.peek().f : 0;
            if (best < Double.POSITIVE_INFINITY && minF + minB >= best - 1e-9) {
                break;
            }
            boolean expandForward = !both || minF <= minB;
            if (expandForward) {
                Node cur = openF.poll();
                if (cur == null || closedF[cur.id] || cur.g > gf[cur.id] + 1e-12) {
                    continue;
                }
                if (!both && cur.id == goal) {
                    return reconstruct(pf, start, goal);
                }
                closedF[cur.id] = true;
                for (Edge e : g.out(cur.id)) {
                    if (e.cost < 0) {
                        continue;
                    }
                    double ng = cur.g + e.cost;
                    if (ng + 1e-12 < gf[e.to]) {
                        gf[e.to] = ng;
                        pf[e.to] = cur.id;
                        openF.add(new Node(e.to, ng, ng + g.heuristic(e.to, goal)));
                    }
                    if (both && gb[e.to] < Double.POSITIVE_INFINITY) {
                        double total = ng + gb[e.to];
                        if (total < best) {
                            best = total;
                            meetF = cur.id;
                            meetB = e.to;
                        }
                    }
                }
            } else {
                Node cur = openB.poll();
                if (cur == null || closedB[cur.id] || cur.g > gb[cur.id] + 1e-12) {
                    continue;
                }
                closedB[cur.id] = true;
                for (Edge e : rev[cur.id]) {
                    double ng = cur.g + e.cost;
                    if (ng + 1e-12 < gb[e.to]) {
                        gb[e.to] = ng;
                        pb[e.to] = cur.id;
                        openB.add(new Node(e.to, ng, ng + g.heuristic(e.to, start)));
                    }
                    if (gf[e.to] < Double.POSITIVE_INFINITY) {
                        double total = gf[e.to] + ng;
                        if (total < best) {
                            best = total;
                            meetF = e.to;
                            meetB = cur.id;
                        }
                    }
                }
            }
        }
        if (!both) {
            return gf[goal] < Double.POSITIVE_INFINITY ? reconstruct(pf, start, goal) : new int[0];
        }
        if (meetF < 0) {
            return new int[0];
        }
        int[] left = reconstruct(pf, start, meetF);
        int[] right = reconstruct(pb, goal, meetB);
        int[] path = new int[left.length + right.length];
        System.arraycopy(left, 0, path, 0, left.length);
        for (int i = 0; i < right.length; i++) {
            path[left.length + i] = right[right.length - 1 - i];
        }
        return path;
    }

    @SuppressWarnings("unchecked")
    private static List<Edge>[] reverse(Graph g) {
        int n = g.nodeCount();
        List<Edge>[] rev = new List[n];
        for (int i = 0; i < n; i++) {
            rev[i] = new ArrayList<>();
        }
        for (int i = 0; i < n; i++) {
            for (Edge e : g.out(i)) {
                rev[e.to].add(new Edge(i, e.cost));
            }
        }
        return rev;
    }

    private static double[] fill(int n) {
        double[] g = new double[n];
        Arrays.fill(g, Double.POSITIVE_INFINITY);
        return g;
    }

    private static int[] parent(int n) {
        int[] p = new int[n];
        Arrays.fill(p, -1);
        return p;
    }

    private static int[] reconstruct(int[] parent, int start, int end) {
        int[] buf = new int[parent.length];
        int k = 0;
        for (int at = end; at != -1; at = parent[at]) {
            buf[k++] = at;
            if (at == start) {
                break;
            }
        }
        int[] path = new int[k];
        for (int i = 0; i < k; i++) {
            path[i] = buf[k - 1 - i];
        }
        return path;
    }

    private static final class Node {
        final int id;
        final double g;
        final double f;

        Node(int id, double f) {
            this(id, 0, f);
        }

        Node(int id, double g, double f) {
            this.id = id;
            this.g = g;
            this.f = f;
        }
    }
}
