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


package baritone.swarm;

import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.frame.SwarmMessage;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.SwarmPriority;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Coordinated region builds over the swarm link (see {@code docs/REGION_BUILD.md}). No game types here;
 * the game side supplies a {@link RegionBuilder}.
 *
 * <p>The group lead splits one schematic into one region per member (roster order) and sends each member
 * a {@link #BUILD} order. Every member computes the same partition from the same file and parameters, so
 * only {@code (file, origin, index, count, strategy...)} travels. Members report {@link #STAT} back.
 * Orders are only accepted from the group's lead, as named in the roster and authenticated by the envelope.
 * For ordered plans ({@code layers}) the lead starts band {@code i + 1} only after band {@code i} is done.
 */
public final class SwarmBuild {

    public static final String BUILD = "BUILD";
    public static final String STAT = "BSTAT";
    public static final String STOP = "BSTOP";
    /** An order with no status after this long is sent once more. */
    public static final long RESEND_MS = 60_000L;
    /** A build that never reported busy counts as done after this long (an empty region finishes at once). */
    static final long SETTLE_MS = 2_000L;

    /** One member's share of a job. */
    public static final class Order {
        public final String job;
        public final String file;
        public final int x;
        public final int y;
        public final int z;
        public final int index;
        public final int count;
        public final String strategy;
        public final String axis;
        public final int seam;
        public final int columns;

        public Order(String job, String file, int x, int y, int z, int index, int count, String strategy, String axis,
                     int seam, int columns) {
            this.job = job;
            this.file = file;
            this.x = x;
            this.y = y;
            this.z = z;
            this.index = index;
            this.count = count;
            this.strategy = strategy;
            this.axis = axis;
            this.seam = seam;
            this.columns = columns;
        }

        public boolean ordered() {
            return "layers".equals(strategy);
        }

        String body() {
            return "job=" + job + ";file=" + file + ";at=" + x + "," + y + "," + z + ";i=" + index + ";n=" + count
                    + ";st=" + strategy + ";ax=" + axis + ";seam=" + seam + ";cols=" + columns;
        }

        /** @throws IllegalArgumentException on a malformed body */
        static Order parse(String body) {
            Map<String, String> kv = SwarmControl.parseBody(body);
            String[] at = req(kv, "at").split(",");
            if (at.length != 3) {
                throw new IllegalArgumentException("bad origin");
            }
            int i = Integer.parseInt(req(kv, "i"));
            int n = Integer.parseInt(req(kv, "n"));
            if (n < 1 || i < 0 || i >= n) {
                throw new IllegalArgumentException("bad region " + i + "/" + n);
            }
            return new Order(req(kv, "job"), checkFile(req(kv, "file")), Integer.parseInt(at[0]),
                    Integer.parseInt(at[1]), Integer.parseInt(at[2]), i, n, req(kv, "st").toLowerCase(Locale.ROOT),
                    req(kv, "ax").toLowerCase(Locale.ROOT), Integer.parseInt(req(kv, "seam")),
                    Integer.parseInt(req(kv, "cols")));
        }

        private static String req(Map<String, String> kv, String k) {
            String v = kv.get(k);
            if (v == null || v.isEmpty()) {
                throw new IllegalArgumentException("missing " + k);
            }
            return v;
        }
    }

    /** Game side: builds one region of a schematic in the schematics folder. */
    public interface RegionBuilder {
        /** @throws Exception with a short message if the build cannot start (no file, bad plan...) */
        void start(Order order) throws Exception;

        /** True while the builder is still working on the region. */
        boolean busy();

        void cancel();
    }

    /** Lead-side view of one member's region. */
    static final class Slot {
        final String member;
        final Order order;
        String state = "waiting"; // waiting, sent, building, done, failed
        String detail;
        long sentAtMs = -1;
        boolean resent;

        Slot(String member, Order order) {
            this.member = member;
            this.order = order;
        }
    }

    private static final class Job {
        final String group;
        final List<Slot> slots = new ArrayList<>();

        Job(String group) {
            this.group = group;
        }
    }

    private final SwarmRoster roster;
    private final SwarmEndpoint endpoint;
    private final RegionBuilder builder;
    private final LongSupplier clockMs;
    private final Consumer<String> log;

    private Job job; // as lead
    private Order mine; // as member (or lead building its own region)
    private String mineGroup;
    private String mineLead;
    private long mineStartMs;
    private boolean mineSawBusy;
    private long sendFailures;

    public SwarmBuild(SwarmRoster roster, SwarmEndpoint endpoint, RegionBuilder builder, LongSupplier clockMs,
                      Consumer<String> log) {
        this.roster = roster;
        this.endpoint = endpoint;
        this.builder = builder;
        this.clockMs = clockMs;
        this.log = log;
    }

    /** File names travel in a {@code key=value} body and name a file in the schematics folder only. */
    static String checkFile(String file) {
        if (file == null || file.isEmpty() || file.length() > SwarmControl.MAX_FIELD || file.startsWith(".")
                || !file.equals(SwarmControl.clean(file))) {
            throw new IllegalArgumentException("bad schematic file name '" + file + "'");
        }
        for (int i = 0; i < file.length(); i++) {
            char c = file.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == ',') {
                throw new IllegalArgumentException("schematic file must be a plain name in the schematics folder");
            }
        }
        return file;
    }

    /**
     * Start a job as the lead of {@code group}: one region per member, in roster order.
     *
     * @throws IllegalArgumentException if we are not the group's lead or the parameters are bad
     */
    public synchronized void start(String group, String file, int x, int y, int z, String strategy, String axis,
                                   int seam, int columns) {
        SwarmRoster.Group g = roster.group(group);
        if (g == null || !g.has(endpoint.selfId())) {
            throw new IllegalArgumentException("you are not in group " + group);
        }
        if (!endpoint.selfId().equals(g.lead())) {
            throw new IllegalArgumentException("only the lead of " + group + " (" + g.lead() + ") can start a build");
        }
        checkFile(file);
        String st = strategy.toLowerCase(Locale.ROOT);
        if (!st.equals("strips") && !st.equals("grid") && !st.equals("layers")) {
            throw new IllegalArgumentException("unknown strategy '" + strategy + "' (strips|grid|layers)");
        }
        String ax = axis.toLowerCase(Locale.ROOT);
        if (!ax.equals("auto") && !ax.equals("x") && !ax.equals("z")) {
            throw new IllegalArgumentException("unknown axis '" + axis + "' (auto|x|z)");
        }
        if (job != null) {
            stop();
        }
        String id = Long.toString(clockMs.getAsLong(), 36);
        Job j = new Job(group);
        List<String> members = g.members();
        for (int i = 0; i < members.size(); i++) {
            j.slots.add(new Slot(members.get(i), new Order(id, file, x, y, z, i, members.size(), st, ax,
                    Math.max(0, seam), Math.max(0, columns))));
        }
        job = j;
        log.accept("swarm: build " + file + " at " + x + "," + y + "," + z + " split " + st + " over "
                + members.size() + " member(s), job " + id);
        dispatch();
    }

    /** Stop our job (as lead: tell every member) and our own region. */
    public synchronized void stop() {
        Job j = job;
        job = null;
        if (j != null) {
            try {
                endpoint.send(j.group, SwarmFrame.BROADCAST, STOP,
                        "job=" + j.slots.get(0).order.job, SwarmPriority.HIGH);
            } catch (Exception e) {
                sendFailures++;
                log.accept("swarm: stop to " + j.group + " failed: " + e.getMessage());
            }
        }
        if (mine != null) {
            builder.cancel();
            mine = null;
        }
    }

    /** Handle a build message. @return false if {@code m} is not a build message */
    synchronized boolean handle(SwarmMessage m) {
        switch (m.type()) {
            case BUILD:
                onOrder(m);
                return true;
            case STAT:
                onStat(m);
                return true;
            case STOP:
                // a stop names its job: one that arrives after the order for the next job must not end that one
                String stopped = SwarmControl.parseBody(m.body()).get("job");
                if (fromLead(m) && mine != null && m.group().equals(mineGroup)
                        && (stopped == null || stopped.equals(mine.job))) {
                    builder.cancel();
                    log.accept("swarm: " + m.from() + " stopped build job " + mine.job);
                    mine = null;
                }
                return true;
            default:
                return false;
        }
    }

    private boolean fromLead(SwarmMessage m) {
        SwarmRoster.Group g = roster.group(m.group());
        return g != null && m.from().equals(g.lead());
    }

    private void onOrder(SwarmMessage m) {
        if (!fromLead(m)) {
            log.accept("swarm: ignored build order from " + m.from() + ", not the lead of " + m.group());
            return;
        }
        Order o;
        try {
            o = Order.parse(m.body());
        } catch (RuntimeException e) {
            reply(m.group(), m.from(), "?", -1, "failed", "bad order: " + e.getMessage());
            return;
        }
        if (mine != null && mine.job.equals(o.job) && mine.index == o.index) {
            reply(m.group(), m.from(), o.job, o.index, "building", null); // a resend of what we already run
            return;
        }
        startMine(m.group(), m.from(), o);
    }

    private void startMine(String group, String lead, Order o) {
        if (mine != null) {
            builder.cancel();
        }
        mine = null;
        try {
            builder.start(o);
        } catch (Exception e) {
            String err = e.getMessage() == null ? e.toString() : e.getMessage();
            log.accept("swarm: build region " + o.index + "/" + o.count + " of " + o.file + " failed: " + err);
            report(group, lead, o, "failed", err);
            return;
        }
        mine = o;
        mineGroup = group;
        mineLead = lead;
        mineStartMs = clockMs.getAsLong();
        mineSawBusy = false;
        log.accept("swarm: building region " + o.index + "/" + o.count + " of " + o.file + " for " + lead);
        report(group, lead, o, "building", null);
    }

    private void report(String group, String lead, Order o, String state, String err) {
        if (lead.equals(endpoint.selfId())) {
            onStatLocal(o.job, o.index, state, err);
        } else {
            reply(group, lead, o.job, o.index, state, err);
        }
    }

    private void reply(String group, String to, String jobId, int index, String state, String err) {
        String body = "job=" + jobId + ";i=" + index + ";state=" + state + (err == null ? "" : ";err=" + SwarmControl.clean(err));
        try {
            endpoint.send(group, to, STAT, body, SwarmPriority.HIGH);
        } catch (Exception e) {
            sendFailures++;
            log.accept("swarm: build status to " + to + " failed: " + e.getMessage());
        }
    }

    private void onStat(SwarmMessage m) {
        Map<String, String> kv = SwarmControl.parseBody(m.body());
        int i;
        try {
            i = Integer.parseInt(kv.getOrDefault("i", ""));
        } catch (NumberFormatException e) {
            return;
        }
        Job j = job;
        if (j == null || !m.group().equals(j.group) || i < 0 || i >= j.slots.size()
                || !j.slots.get(i).member.equals(m.from())) {
            return; // only the member a region was given to reports on it
        }
        onStatLocal(kv.getOrDefault("job", ""), i, kv.getOrDefault("state", "?"), kv.get("err"));
    }

    private void onStatLocal(String jobId, int index, String state, String err) {
        Job j = job;
        if (j == null || !j.slots.get(0).order.job.equals(jobId)) {
            return;
        }
        Slot s = j.slots.get(index);
        if (s.state.equals("done") || !(state.equals("building") || state.equals("done") || state.equals("failed"))) {
            return;
        }
        s.state = state;
        s.detail = err;
        if (!state.equals("building")) {
            log.accept("swarm: " + s.member + " region " + index + " " + state + (err == null ? "" : ": " + err));
        }
        if (state.equals("done")) {
            dispatch();
        }
    }

    /** Send every order that may start now: all at once, or the next band of an ordered plan. */
    private void dispatch() {
        Job j = job;
        if (j == null) {
            return;
        }
        boolean ordered = j.slots.get(0).order.ordered();
        int done = 0;
        for (Slot s : j.slots) {
            if (s.state.equals("done")) {
                done++;
                continue;
            }
            if (s.state.equals("waiting")) {
                send(j, s);
            }
            if (ordered) {
                break; // band i + 1 waits for band i
            }
        }
        if (done == j.slots.size()) {
            log.accept("swarm: build job " + j.slots.get(0).order.job + " finished, " + done + " region(s)");
            job = null;
        }
    }

    private void send(Job j, Slot s) {
        s.state = "sent";
        s.sentAtMs = clockMs.getAsLong();
        if (s.member.equals(endpoint.selfId())) {
            startMine(j.group, s.member, s.order);
            return;
        }
        try {
            endpoint.send(j.group, s.member, BUILD, s.order.body(), SwarmPriority.HIGH);
        } catch (Exception e) {
            sendFailures++;
            s.state = "failed";
            s.detail = "send failed: " + e.getMessage();
            log.accept("swarm: build order to " + s.member + " failed: " + e.getMessage());
        }
    }

    /** Watch our own region and resend unanswered orders. Never throws. */
    synchronized void tick() {
        long now = clockMs.getAsLong();
        if (mine != null) {
            boolean busy = builder.busy();
            mineSawBusy |= busy;
            if (!busy && (mineSawBusy || now - mineStartMs >= SETTLE_MS)) {
                Order o = mine;
                mine = null;
                log.accept("swarm: finished region " + o.index + "/" + o.count + " of " + o.file);
                report(mineGroup, mineLead, o, "done", null);
            }
        }
        Job j = job;
        if (j != null) {
            for (Slot s : j.slots) {
                if (s.state.equals("sent") && !s.resent && now - s.sentAtMs >= RESEND_MS
                        && !s.member.equals(endpoint.selfId())) {
                    s.resent = true;
                    s.sentAtMs = now;
                    try {
                        endpoint.send(j.group, s.member, BUILD, s.order.body(), SwarmPriority.HIGH);
                    } catch (Exception e) {
                        sendFailures++;
                    }
                }
            }
        }
    }

    synchronized String slotState(int index) {
        return job == null ? null : job.slots.get(index).state;
    }

    synchronized boolean jobRunning() {
        return job != null;
    }

    synchronized Order myOrder() {
        return mine;
    }

    synchronized List<String> statusLines() {
        List<String> out = new ArrayList<>();
        if (mine != null) {
            out.add("build: region " + mine.index + "/" + mine.count + " of " + mine.file + " for " + mineLead
                    + " (job " + mine.job + ")");
        }
        Job j = job;
        if (j != null) {
            Order o = j.slots.get(0).order;
            out.add("build job " + o.job + ": " + o.file + " at " + o.x + "," + o.y + "," + o.z + ", " + o.strategy
                    + ", group " + j.group);
            for (Slot s : j.slots) {
                out.add("  region " + s.order.index + " " + s.member + ": " + s.state
                        + (s.detail == null ? "" : " (" + s.detail + ")"));
            }
        }
        if (sendFailures > 0) {
            out.add("build: send failures " + sendFailures);
        }
        return out;
    }
}
