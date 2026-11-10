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

package baritone.process.interact;

import baritone.process.interact.ContainerView.Kind;
import baritone.process.interact.OpenVerifier.Step;
import org.junit.Test;

import java.lang.management.ManagementFactory;

import static org.junit.Assert.*;

/**
 * Ostinato 2.0 Phase 09: the Phase 08 verified-open rule against a fake {@link ContainerView}, with no client. The
 * fake plays the server: {@link FakeView#open} is an open-screen packet (a new menu object), {@link #close} the close.
 */
public class OpenVerifierTest {

    /** A scripted client: what is open, and how often the rule closed it. */
    static final class FakeView implements ContainerView {
        Kind kind = Kind.NONE;
        Object menu = new Object(); // the player's own inventory menu
        final Object inventory = menu;
        int closes;

        /** The server opened a container: a new menu object. */
        Object open(Kind k) {
            kind = k;
            menu = new Object();
            return menu;
        }

        @Override
        public Kind kind() {
            return kind;
        }

        @Override
        public Object menu() {
            return menu;
        }

        @Override
        public void close() {
            closes++;
            kind = Kind.NONE;
            menu = inventory;
        }
    }

    @Test
    public void ourClickOpensTheWantedContainer() {
        for (boolean verified : new boolean[]{false, true}) {
            FakeView v = new FakeView();
            OpenVerifier o = new OpenVerifier();
            assertEquals(Step.WAIT, o.click(v, Kind.CHEST, verified));
            assertEquals(Step.WAIT, o.click(v, Kind.CHEST, verified)); // not answered yet
            v.open(Kind.CHEST);
            assertEquals(Step.OPENED, o.click(v, Kind.CHEST, verified));
            assertEquals(0, v.closes);
        }
    }

    @Test
    public void aContainerLeftOpenIsClosedNotCounted() {
        // REPEAT step 2: the chest from step 1 is still open when the crafting table step clicks
        FakeView v = new FakeView();
        v.open(Kind.CHEST);
        OpenVerifier o = new OpenVerifier();
        assertEquals(Step.WAIT, o.click(v, Kind.CRAFTING, true));
        assertEquals(1, v.closes);
        assertEquals(Kind.NONE, v.kind);
        v.open(Kind.CRAFTING);
        assertEquals(Step.OPENED, o.click(v, Kind.CRAFTING, true));
    }

    @Test
    public void aStaleContainerOfTheRightKindIsAlsoClosed() {
        // CANCEL_ACT: the cancelled request's chest opened after the stop; the rerun must open it with its own click
        FakeView v = new FakeView();
        Object stale = v.open(Kind.CHEST);
        OpenVerifier o = new OpenVerifier();
        assertEquals(Step.WAIT, o.click(v, Kind.CHEST, true));
        assertEquals(1, v.closes);
        Object ours = v.open(Kind.CHEST);
        assertNotSame(stale, ours);
        assertEquals(Step.OPENED, o.click(v, Kind.CHEST, true));
    }

    @Test
    public void legacyCountsAnyOpenContainer() {
        // flag off keeps the old rule: whatever is open is "done" (Phase 08's false successes), and nothing is closed
        FakeView v = new FakeView();
        v.open(Kind.CHEST);
        OpenVerifier o = new OpenVerifier();
        assertEquals(Step.OPENED_UNVERIFIED, o.click(v, Kind.CRAFTING, false));
        // a stale container of the right kind is likewise not ours
        FakeView w = new FakeView();
        w.open(Kind.CHEST);
        assertEquals(Step.OPENED_UNVERIFIED, new OpenVerifier().click(w, Kind.CHEST, false));
        assertEquals(0, v.closes + w.closes);
    }

    @Test
    public void wrongKindAfterOurClickNeverCounts() {
        FakeView v = new FakeView();
        OpenVerifier o = new OpenVerifier();
        assertEquals(Step.WAIT, o.click(v, Kind.FURNACE, true));
        v.open(Kind.BLAST_FURNACE);
        assertEquals(Step.WAIT, o.click(v, Kind.FURNACE, true));
        v.open(Kind.OTHER);
        assertEquals(Step.WAIT, o.click(v, Kind.FURNACE, true));
        assertEquals(2, v.closes);
        v.open(Kind.FURNACE);
        assertEquals(Step.OPENED, o.click(v, Kind.FURNACE, true));
    }

    @Test
    public void aRefusedOpenWaitsAndClosesNothing() {
        // CAT: the server never answers; the process's 20-tick timeout, not this rule, ends the request
        FakeView v = new FakeView();
        OpenVerifier o = new OpenVerifier();
        for (int i = 0; i < 100; i++) {
            assertEquals(Step.WAIT, o.click(v, Kind.CHEST, true));
        }
        assertEquals(0, v.closes);
    }

    @Test
    public void resetStartsANewRequestClean() {
        FakeView v = new FakeView();
        OpenVerifier o = new OpenVerifier();
        assertEquals(Step.WAIT, o.click(v, Kind.CHEST, true)); // saw nothing open
        o.reset(); // a new request
        v.open(Kind.CHEST); // e.g. the old request's click answered late
        assertEquals(Step.WAIT, o.click(v, Kind.CHEST, true)); // not inherited: closed instead
        assertEquals(1, v.closes);
    }

    @Test
    public void anAdapterThatFailsNeverReportsAnOpen() {
        ContainerView broken = new ContainerView() {
            @Override
            public Kind kind() {
                throw new IllegalStateException("no player");
            }

            @Override
            public Object menu() {
                throw new IllegalStateException("no player");
            }

            @Override
            public void close() {
                throw new IllegalStateException("no player");
            }
        };
        OpenVerifier o = new OpenVerifier();
        assertThrows(IllegalStateException.class, () -> o.click(broken, Kind.CHEST, true));
        assertThrows(IllegalStateException.class, () -> OpenVerifier.reached(broken, Kind.CHEST, null));
    }

    @Test
    public void reachedNeedsANewContainerOfTheWantedKind() {
        FakeView v = new FakeView();
        Object before = v.menu();
        assertFalse(OpenVerifier.reached(v, Kind.CHEST, before)); // nothing open
        Object chest = v.open(Kind.CHEST);
        assertTrue(OpenVerifier.reached(v, Kind.CHEST, before));
        assertFalse(OpenVerifier.reached(v, Kind.CRAFTING, before)); // wrong kind
        assertFalse(OpenVerifier.reached(v, Kind.CHEST, chest)); // the one open when the step started
        assertFalse(OpenVerifier.reached(v, Kind.NONE, before)); // not a container: never by menu
        v.close();
        assertFalse(OpenVerifier.reached(v, Kind.CHEST, before));
    }

    @Test
    public void noPlayerIsNeverReached() {
        // ClientContainers with no player: kind NONE, menu null
        FakeView v = new FakeView();
        v.kind = Kind.NONE;
        v.menu = null;
        assertFalse(OpenVerifier.reached(v, Kind.CHEST, new Object()));
        assertFalse(OpenVerifier.reached(v, Kind.CHEST, null));
    }

    @Test
    public void clickAllocatesNothing() {
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        FakeView v = new FakeView();
        OpenVerifier o = new OpenVerifier();
        int n = 200_000;
        long sink = 0;
        for (int i = 0; i < n; i++) { // warm up
            sink += o.click(v, Kind.CHEST, true).ordinal();
        }
        long t = Thread.currentThread().threadId();
        long bytes0 = mx.getThreadAllocatedBytes(t);
        long ns0 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            sink += o.click(v, Kind.CHEST, true).ordinal();
            sink += OpenVerifier.reached(v, Kind.CHEST, null) ? 1 : 0;
        }
        long ns = System.nanoTime() - ns0;
        long bytes = mx.getThreadAllocatedBytes(t) - bytes0;
        System.out.printf("OpenVerifier click+reached: %.1f ns/call, %.3f bytes/call (sink %d)%n", ns / (double) n, bytes / (double) n, sink);
        assertTrue("allocated " + bytes + " bytes over " + n + " calls", bytes / (double) n < 1.0);
    }
}
