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

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ostinato: -Dostinato.lockstep=true runs the singleplayer server one tick per client tick, in a fixed order, for the
 * bench and the parity check. Left alone, the integrated server keeps its own 50 ms clock on its own thread, so
 * where its ticks fall between ours (and so on which client tick a hit, a hurt flash or a knockback shows up) depends
 * on thread timing, CPU load and frame rate: the same drill run twice, or on a headless and a real client, parts
 * ways within a tick or two. In lockstep each client tick ends by waiting until every packet it sent has reached the
 * server, lets exactly one server tick run, waits for it and for what it sent back to settle, and the next client
 * tick first handles everything that arrived (as a fast client does between two ticks, whatever the frame rate).
 * Every wait has a timeout, counted in {@link #misses}, so nothing can hang.
 */
public final class Lockstep implements AbstractGameEventListener {

    public static final boolean ENABLED = Boolean.getBoolean("ostinato.lockstep");
    private static final long WAIT_MS = Long.getLong("ostinato.lockstep.waitMs", 2000);
    /**
     * How long the server waits for a go. Between two client ticks the client renders, and a frame that rebuilds the
     * chunks around a teleport can take seconds on a software renderer: with the gate on the same 2 s as the other
     * waits the server then ran a tick on its own, one more server tick per slow frame, which moved where mob
     * trackers' send intervals fell relative to the fight (the parity drill parted at fight tick 39 on a knockback
     * velocity update one tick apart). The client always sends its go unless it is gone, so wait long.
     */
    private static final long GATE_MS = Long.getLong("ostinato.lockstep.gateMs", 60000);

    /** Set by the client while it is in a singleplayer level and ticking; the server only gates its ticks then. */
    private static volatile boolean engaged;
    private static final Semaphore go = new Semaphore(0);
    /**
     * Which of the client's go's the server has finished a tick for. A tick the server ran without the gate (one
     * already past it when the client engaged) must not count as the answer to a go, or the client runs on while the
     * server is still ticking: so each go has a number and only a gated tick reports it back.
     */
    private static final AtomicLong goSeq = new AtomicLong(), doneSeq = new AtomicLong();
    private static long gatedSeq = -1; // server thread only
    private static volatile boolean atGate;

    /** Server thread: inside {@link #serverGate}, where the tasks the client waits on must run whatever the clock says. */
    public static boolean atGate() {
        return atGate;
    }
    /** Packets the client has handed to its connection, packets the server's connection has read, packets the client's connection has read. */
    public static final AtomicLong c2sSent = new AtomicLong(), c2sRead = new AtomicLong(), s2cSent = new AtomicLong(), s2cRead = new AtomicLong();
    public static volatile int misses, ticks;
    /** Which wait ran out: path search, client-to-server packets, the server tick, server-to-client packets, the server's gate. */
    public static volatile int missPath, missC2s, missTick, missS2c, missGate;

    private final Baritone baritone;

    private Lockstep(Baritone baritone) {
        this.baritone = baritone;
    }

    public static void install(Baritone baritone) {
        if (ENABLED) baritone.getGameEventHandler().registerEventListener(new Lockstep(baritone));
    }

    public static boolean engaged() {
        return ENABLED && engaged;
    }

    /** Server thread, before it reads its packets for a tick: wait for the client's go, running submitted tasks meanwhile. */
    public static void serverGate(net.minecraft.server.MinecraftServer server, java.util.function.BooleanSupplier pollTask) {
        if (!engaged()) return;
        long until = System.nanoTime() + GATE_MS * 1_000_000L;
        gatedSeq = -1;
        atGate = true;
        try {
            while (!go.tryAcquire(1, TimeUnit.MILLISECONDS)) {
                while (pollTask.getAsBoolean()) { } // a command the client is waiting on must still run
                if (!engaged) return;
                if (System.nanoTime() > until) {
                    misses++;
                    missGate++;
                    return;
                }
            }
            gatedSeq = goSeq.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            atGate = false;
        }
    }

    /** Server thread, after the tick. */
    public static void serverDone() {
        if (gatedSeq >= 0) doneSeq.set(gatedSeq);
        gatedSeq = -1;
    }

    @Override
    public void onTick(TickEvent event) {
        boolean in = event.getType() == TickEvent.Type.IN && Minecraft.getInstance().getSingleplayerServer() != null;
        if (!in && engaged) {
            engaged = false;
            go.release(64); // let a waiting server go
        }
    }

    @Override
    public void onPostTick(TickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getType() != TickEvent.Type.IN || mc.getSingleplayerServer() == null || mc.player == null) return;
        if (!engaged) {
            engaged = true;
            go.drainPermits();
        }
        // a path search runs on its own thread: whether its path is there for the next tick was a race with the
        // clock (a chase began a tick earlier or later from run to run). In lockstep it always is, when it ends in time
        if (!await(() -> baritone.getPathingBehavior().getInProgress().isEmpty(), PATH_WAIT_MS)) {
            misses++;
            missPath++;
        }
        // every packet this tick sent is in the server's queue before its tick reads the queue
        if (!await(() -> c2sRead.get() >= c2sSent.get(), 200)) {
            misses++;
            missC2s++;
        }
        long seq = goSeq.incrementAndGet();
        go.release();
        if (!await(() -> doneSeq.get() >= seq, WAIT_MS)) {
            misses++;
            missTick++;
        }
        // everything the server tick sent this client has been read (a bundle is one packet on both ends)
        if (!await(() -> s2cRead.get() >= s2cSent.get(), 200)) {
            misses++;
            missS2c++;
        }
        ticks++;
    }

    private static final long PATH_WAIT_MS = Long.getLong("ostinato.lockstep.pathWaitMs", 3000);

    private static boolean await(java.util.function.BooleanSupplier cond, long ms) {
        long until = System.nanoTime() + ms * 1_000_000L;
        while (!cond.getAsBoolean()) {
            if (System.nanoTime() > until) return false;
            Thread.onSpinWait();
        }
        return true;
    }
}
