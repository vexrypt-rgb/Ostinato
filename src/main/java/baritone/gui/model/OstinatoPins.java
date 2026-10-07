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


package baritone.gui.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Settings that only exist in Ostinato (not in upstream Baritone). The screen pins them to the top of their
 * category with an OSTINATO badge.
 */
public final class OstinatoPins {

    private static final Set<String> PINNED = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "kinematicTravel", "slowKinematic", "physicsTravel", "movementBackend", "pitfallAvoidance", "sprintJump",
            "allowBoats", "allowBoatFall", "maxFallHeightBoat", "allowDoorAirPockets", "swimInWater",
            "elytraGlideWithoutFireworks", "elytraGlideRatio", "elytraGlidePitch",
            "guiKeybind", "renderPathHud", "pathHudAnchor", "guiAccentColor"
    )));

    private OstinatoPins() {}

    public static boolean isOstinato(String settingName) {
        return PINNED.contains(settingName) || settingName.startsWith("swarm");
    }

    /** A description that starts with "Experimental" gets an EXPERIMENTAL badge. */
    public static boolean isExperimental(String description) {
        return description != null && description.trim().toLowerCase(Locale.ROOT).startsWith("experimental");
    }
}
