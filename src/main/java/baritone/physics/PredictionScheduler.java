/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

import baritone.api.physics.PhysicsTelemetry;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionRequest.Fidelity;
import baritone.api.physics.PredictionRequest.Priority;
import baritone.api.physics.PredictionResult;
import baritone.api.physics.PredictionResult.Assumption;
import baritone.api.physics.PredictionResult.Failure;
import baritone.api.physics.PredictionResult.Status;
import baritone.api.physics.PredictionTicket;

import java.util.ArrayDeque;
import java.util.HashMap;

/**
 * Ostinato: runs requests over captured terrain, inline or on one daemon worker. Work is queued FIFO within each
 * {@link Priority}, most urgent first, in a queue of fixed capacity: a full queue drops its least urgent newest job
 * for a more urgent one, otherwise refuses (REJECTED). Equal questions over equal terrain share one job while it is
 * queued or running and are answered from the {@link PredictionCache} once done. A job is EXPIRED if its deadline
 * passed before it started and stops PARTIAL at it while running. With the queue at least half full, MOVEMENT and
 * less urgent jobs run one fidelity lower (DEGRADED). Submitting is non-blocking and may come from any thread;
 * {@link #predict} is for one calling thread (the game thread).
 */
public final class PredictionScheduler {
    private final Object lock = new Object();
    @SuppressWarnings("unchecked")
    private final ArrayDeque<Job>[] queues = new ArrayDeque[Priority.values().length];
    private final HashMap<PredictionCache.Key, Job> inFlight = new HashMap<>();
    private final int capacity;
    private int size, highWater;
    private final PredictionCache cache;
    private final PhysicsStats stats;
    private final Predictor callerPredictor = new Predictor();
    private volatile Thread worker;
    private volatile long startedNanos;

    public PredictionScheduler(int capacity, PredictionCache cache, PhysicsStats stats) {
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        for (int i = 0; i < queues.length; i++) queues[i] = new ArrayDeque<>();
        this.capacity = capacity;
        this.cache = cache;
        this.stats = stats;
    }

    public PhysicsStats stats() {
        return stats;
    }

    /** Start the worker thread (idempotent). */
    public void start() {
        synchronized (lock) {
            if (worker != null) return;
            Thread t = new Thread(this::workLoop, "Ostinato physics");
            t.setDaemon(true);
            t.setPriority(Thread.NORM_PRIORITY - 1);
            startedNanos = System.nanoTime();
            worker = t;
            t.start();
        }
    }

    /** Stop the worker; queued jobs end CANCELLED. */
    public void stop() {
        Thread t;
        synchronized (lock) {
            t = worker;
            worker = null;
            for (Job j : java.util.List.copyOf(inFlight.values())) {
                j.cancelled = true;
                stats.cancelled.increment();
                finish(j, PredictionResult.failed(Status.CANCELLED, Failure.CANCELLED, j.request.fidelity, 0));
            }
            for (ArrayDeque<Job> q : queues) q.clear();
            size = 0;
            lock.notifyAll();
        }
        if (t != null) t.interrupt();
    }

    /** Answer on this thread now, from the cache if it can. */
    public PredictionResult predict(PredictionRequest r, Terrain terrain) {
        stats.requests.increment();
        stats.inline.increment();
        PredictionResult expired = expired(r);
        if (expired != null) return expired;
        PredictionCache.Key key = PredictionCache.key(r, terrain);
        PredictionResult hit = cache.get(key);
        if (hit != null) {
            stats.cacheHits.increment();
            return hit.withTiming(0, terrain.maxAge(), terrain.superseded() ? Assumption.STALE_SNAPSHOT.bit() : 0);
        }
        PredictionResult out = callerPredictor.run(r, terrain, r.fidelity, () -> false);
        stats.compute.recordNanos(out.computeNanos);
        stats.completed.increment();
        cache.put(key, out);
        return out.withTiming(0, terrain.maxAge(), terrain.superseded() ? Assumption.STALE_SNAPSHOT.bit() : 0);
    }

    /** Queue a request over this terrain; never blocks. */
    public PredictionTicket submit(PredictionRequest r, Terrain terrain) {
        stats.requests.increment();
        stats.async.increment();
        PredictionResult expired = expired(r);
        if (expired != null) return new Ticket(r, null, expired);
        PredictionCache.Key key = PredictionCache.key(r, terrain);
        PredictionResult hit = cache.get(key);
        if (hit != null) {
            stats.cacheHits.increment();
            return new Ticket(r, null, hit.withTiming(0, terrain.maxAge(), 0), terrain);
        }
        synchronized (lock) {
            // A worker can finish between the optimistic lookup and acquiring this lock.
            hit = cache.get(key);
            if (hit != null) {
                stats.cacheHits.increment();
                return new Ticket(r, null, hit.withTiming(0, terrain.maxAge(), 0), terrain);
            }
            Job j = inFlight.get(key);
            if (j != null && !j.cancelled) {
                stats.dedupHits.increment();
                j.interest++;
                if (r.priority.ordinal() < j.priority.ordinal() && j.queued) { // promote
                    queues[j.priority.ordinal()].remove(j);
                    queues[r.priority.ordinal()].addLast(j);
                }
                if (r.priority.ordinal() < j.priority.ordinal()) j.priority = r.priority;
                if (j.deadline != 0 && (r.deadlineNanos == 0 || r.deadlineNanos - j.deadline > 0)) j.deadline = r.deadlineNanos;
                return new Ticket(r, j, null);
            }
            if (size >= capacity && !evictFor(r.priority)) {
                stats.rejected.increment();
                return new Ticket(r, null, PredictionResult.failed(Status.REJECTED, Failure.QUEUE_FULL, r.fidelity, 0));
            }
            j = new Job(r, terrain, key);
            inFlight.put(key, j);
            queues[r.priority.ordinal()].addLast(j);
            size++;
            highWater = Math.max(highWater, size);
            lock.notifyAll();
            return new Ticket(r, j, null);
        }
    }

    private PredictionResult expired(PredictionRequest request) {
        if (request.deadlineNanos == 0 || System.nanoTime() - request.deadlineNanos <= 0) return null;
        stats.expired.increment();
        return PredictionResult.failed(Status.EXPIRED, Failure.DEADLINE, request.fidelity, 0);
    }

    /** A ticket already holding {@code result} (a request refused before it reached the queue). */
    public PredictionTicket done(PredictionRequest r, PredictionResult result) {
        return new Ticket(r, null, result);
    }

    /** Drop the least urgent newest job if it is less urgent than {@code p}. Holding the lock. */
    private boolean evictFor(Priority p) {
        for (int i = queues.length - 1; i > p.ordinal(); i--) {
            Job victim = queues[i].pollLast();
            if (victim == null) continue;
            size--;
            stats.rejected.increment();
            finish(victim, PredictionResult.failed(Status.REJECTED, Failure.QUEUE_FULL, victim.request.fidelity, System.nanoTime() - victim.submitted));
            return true;
        }
        return false;
    }

    /** Holding the lock, or for a job no longer reachable from the queue. */
    private void finish(Job j, PredictionResult r) {
        j.queued = false;
        inFlight.remove(j.key, j);
        j.result = r;
    }

    private void workLoop() {
        Thread self = Thread.currentThread();
        Predictor predictor = new Predictor();
        while (worker == self) {
            try {
                runNext(50_000_000L, self, predictor);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Run the most urgent queued job, waiting up to {@code waitNanos} for one; false if none came. */
    boolean runNext(long waitNanos) throws InterruptedException {
        return runNext(waitNanos, null, new Predictor());
    }

    private boolean runNext(long waitNanos, Thread owner, Predictor predictor) throws InterruptedException {
        Job j;
        int left;
        synchronized (lock) {
            long until = System.nanoTime() + waitNanos;
            while (true) {
                if (owner != null && worker != owner) return false;
                j = poll();
                if (j != null) break;
                long rest = until - System.nanoTime();
                if (rest <= 0) return false;
                lock.wait(Math.max(1, rest / 1_000_000L));
            }
            j.queued = false;
            size--;
            left = size;
        }
        long start = System.nanoTime();
        long waited = start - j.submitted;
        stats.queue.recordNanos(waited);
        PredictionRequest r = j.request;
        PredictionResult out;
        if (j.cancelled) {
            stats.cancelled.increment();
            out = PredictionResult.failed(Status.CANCELLED, Failure.CANCELLED, r.fidelity, waited);
        } else if (j.deadline != 0 && start - j.deadline > 0) {
            stats.expired.increment();
            out = PredictionResult.failed(Status.EXPIRED, Failure.DEADLINE, r.fidelity, waited);
        } else {
            Fidelity f = r.fidelity;
            boolean degrade = left * 2 >= capacity && j.priority.ordinal() >= Priority.MOVEMENT.ordinal() && f != Fidelity.SCREENING;
            if (degrade) {
                f = Fidelity.values()[f.ordinal() - 1];
                stats.degraded.increment();
            }
            Job job = j;
            out = predictor.run(r, j.terrain, f, j.deadline, () -> job.cancelled);
            out = out.withTiming(waited, j.terrain.maxAge(), degrade ? Assumption.DEGRADED.bit() : 0);
            stats.compute.recordNanos(out.computeNanos);
            if (out.status == Status.CANCELLED) stats.cancelled.increment();
            else stats.completed.increment();

        }
        stats.workerBusyNanos.add(System.nanoTime() - start);
        synchronized (lock) {
            if (!j.cancelled) {
                cache.put(j.key, out);
                finish(j, out);
            } else if (j.result == null) {
                finish(j, PredictionResult.failed(Status.CANCELLED, Failure.CANCELLED, r.fidelity, waited));
            }
        }
        return true;
    }

    private Job poll() {
        for (ArrayDeque<Job> q : queues) {
            Job j = q.pollFirst();
            if (j != null) return j;
        }
        return null;
    }

    private void release(Job j) {
        synchronized (lock) {
            if (--j.interest > 0) return;
            j.cancelled = true;
            if (j.queued && queues[j.priority.ordinal()].remove(j)) {
                size--;
                stats.cancelled.increment();
                finish(j, PredictionResult.failed(Status.CANCELLED, Failure.CANCELLED, j.request.fidelity, System.nanoTime() - j.submitted));
            } else {
                inFlight.remove(j.key, j); // running: let a new asker start afresh
            }
        }
    }

    public int queueDepth() {
        synchronized (lock) {
            return size;
        }
    }

    public PhysicsTelemetry telemetry() {
        int depth, high;
        synchronized (lock) {
            depth = size;
            high = highWater;
        }
        long started = startedNanos;
        return stats.snapshot(depth, high, worker == null || started == 0 ? 0 : System.nanoTime() - started);
    }

    private static final class Job {
        final PredictionRequest request;
        final Terrain terrain;
        final PredictionCache.Key key;
        final long submitted = System.nanoTime();
        volatile Priority priority;
        volatile long deadline;
        int interest = 1;
        boolean queued = true;
        volatile boolean cancelled;
        volatile PredictionResult result;

        Job(PredictionRequest request, Terrain terrain, PredictionCache.Key key) {
            this.request = request;
            this.terrain = terrain;
            this.key = key;
            this.priority = request.priority;
            this.deadline = request.deadlineNanos;
        }
    }

    private final class Ticket implements PredictionTicket {
        private final PredictionRequest request;
        private final Job job;
        private volatile PredictionResult own;
        private PredictionResult delivered;
        private final Terrain terrain;

        Ticket(PredictionRequest request, Job job, PredictionResult own) {
            this(request, job, own, job == null ? null : job.terrain);
        }

        Ticket(PredictionRequest request, Job job, PredictionResult own, Terrain terrain) {
            this.request = request;
            this.job = job;
            this.own = own;
            this.terrain = terrain;
        }

        @Override
        public PredictionRequest request() {
            return request;
        }

        @Override
        public boolean isDone() {
            return result() != null;
        }

        @Override
        public synchronized PredictionResult result() {
            if (own == null && job.result == null) {
                PredictionResult expired = expired(request);
                if (expired != null) {
                    own = expired;
                    release(job);
                }
            }
            PredictionResult r = own != null ? own : job.result;
            if (r == null) return null;
            boolean stale = terrain != null && r.ok() && terrain.superseded();
            if (delivered == null || stale && !delivered.has(Assumption.STALE_SNAPSHOT)) {
                delivered = r.withTiming(r.queueNanos, r.snapshotAgeTicks, stale ? Assumption.STALE_SNAPSHOT.bit() : 0);
            }
            return delivered;
        }

        @Override
        public synchronized void cancel() {
            if (own != null || job.result != null) return;
            own = PredictionResult.failed(Status.CANCELLED, Failure.CANCELLED, request.fidelity, System.nanoTime() - job.submitted);
            release(job);
        }
    }
}
