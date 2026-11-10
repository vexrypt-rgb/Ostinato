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

/**
 * Ostinato 2.0 Phase 09: the Phase 08 verified-open rule, read through a {@link ContainerView} so it holds on any
 * version's adapter and can be tested without a client. One instance per get-to-block process; {@link #reset()} at
 * each request. No Minecraft import, Java 8 syntax.
 */
public final class OpenVerifier {

    public enum Step {
        /** Keep clicking: nothing open yet, or (verified) a container that is not ours, which was just closed. */
        WAIT,
        /** A container of the wanted kind opened after this request saw none open while clicking. */
        OPENED,
        /** Not verified (the legacy rule): some other container is open, and that counts as done. */
        OPENED_UNVERIFIED
    }

    /** While clicking, this request saw no container open, so a container open now was opened after our click. */
    private boolean sawClosed;

    public void reset() {
        sawClosed = false;
    }

    /** One tick of the right click on {@code want}'s block; {@code verified} is the request's verifiedGetToBlock. */
    public Step click(ContainerView view, Kind want, boolean verified) {
        Kind open = view.kind();
        if (open == Kind.NONE) {
            sawClosed = true;
            return Step.WAIT;
        }
        if (sawClosed && open == want) {
            return Step.OPENED;
        }
        if (!verified) {
            return Step.OPENED_UNVERIFIED;
        }
        // a container left open from before, or not the one we clicked: close it so the click can land
        view.close();
        sawClosed = true; // anything open from here on was opened after this close
        return Step.WAIT;
    }

    /** The postcondition: a {@code want} container is open and is not {@code before} (the one open at the start). */
    public static boolean reached(ContainerView view, Kind want, Object before) {
        return want != Kind.NONE && view.menu() != before && view.kind() == want;
    }
}
