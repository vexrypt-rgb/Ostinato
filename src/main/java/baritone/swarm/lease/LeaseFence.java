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

package baritone.swarm.lease;

import java.util.function.BooleanSupplier;

/**
 * Ostinato: the last-line fence for lease-protected world actions (break, place, container clicks).
 *
 * <p>{@link #denied()} is true only while a leased command is active, its lease is no longer valid, and the process
 * in control is the leased one (mine, builder or custom goal). Eating, fleeing lava and fighting are other processes
 * (or no process) and are never fenced. With {@code swarmLeases=false} nothing installs a check and this is always
 * false.
 */
public final class LeaseFence {

    private static final BooleanSupplier OFF = () -> false;
    private static volatile BooleanSupplier check = OFF;
    private static volatile long denials;

    private LeaseFence() {}

    public static void install(BooleanSupplier c) {
        check = c == null ? OFF : c;
    }

    public static void clear() {
        check = OFF;
    }

    /** True when the current action must not run. Counts each denial. */
    public static boolean denied() {
        BooleanSupplier c = check;
        if (c == OFF) {
            return false;
        }
        boolean d;
        try {
            d = c.getAsBoolean();
        } catch (RuntimeException e) {
            d = false; // A broken check must not freeze the bot.
        }
        if (d) {
            denials++;
        }
        return d;
    }

    public static long denials() {
        return denials;
    }
}
