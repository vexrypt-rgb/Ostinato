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

import java.util.HashMap;
import java.util.Map;

/**
 * Slider ranges for numeric settings with an obvious sensible range. Numbers without an entry get a plain value
 * field (click to type, scroll to step).
 */
public final class SettingRanges {

    public static final class Range {
        public final double min, max, step;

        Range(double min, double max, double step) {
            this.min = min;
            this.max = max;
            this.step = step;
        }

        public double clamp(double v) {
            return Math.max(min, Math.min(max, v));
        }

        /** Snap to the step grid (relative to {@link #min}) and clamp. */
        public double snap(double v) {
            double s = Math.round((v - min) / step) * step + min;
            // kill float noise like 0.30000000000000004
            s = Math.round(s * 1e6) / 1e6;
            return clamp(s);
        }

        public double fraction(double v) {
            return max == min ? 0 : (clamp(v) - min) / (max - min);
        }
    }

    private static final Map<String, Range> RANGES = new HashMap<>();

    static {
        r(0, 1, 0.05, "selectionOpacity", "cachedChunksOpacity", "freecamGhostOpacity", "pathCutoffFactor", "backtrackCostFavoringCoefficient",
                "acsHudOpacity");
        r(0.1, 1, 0.05, "pathingMapLoadFactor");
        r(0, 6, 1, "acsHudScale");
        r(1, 20, 1, "smoothLookTicks", "maxFallHeightNoWater");
        r(1, 5, 0.01, "costHeuristic");
        r(0, 10, 0.5, "jumpPenalty", "walkOnWaterOnePenalty", "blockBreakAdditionalPenalty");
        r(0, 100, 1, "blockPlacementPenalty");
        r(0, 10, 0.1, "avoidBreakingMultiplier", "placeIncorrectBlockPenaltyMultiplier");
        r(0, 50, 0.5, "breakCorrectBlockPenaltyMultiplier");
        r(1, 6, 0.1, "blockReachDistance");
        r(3, 64, 1, "maxFallHeightBucket");
        r(3, 128, 1, "maxFallHeightBoat");
        r(1, 10, 0.5, "pathRenderLineWidthPixels", "goalRenderLineWidthPixels", "selectionLineWidth");
        r(0, 0.5, 0.01, "randomLooking");
        r(0, 10, 0.5, "randomLooking113");
        r(0, 5, 0.1, "mobAvoidanceCoefficient", "mobSpawnerAvoidanceCoefficient");
        r(0, 32, 1, "mobAvoidanceRadius", "followRadius");
        r(0, 64, 1, "mobSpawnerAvoidanceRadius");
        r(0, 16, 0.5, "followOffsetDistance");
        r(-180, 180, 1, "followOffsetDirection");
        r(0, 255, 1, "legitMineYLevel", "minYLevelWhileMining", "maxYLevelWhileMining", "axisHeight");
        r(-1, 255, 1, "exploreMaintainY");
        r(0, 20, 1, "rightClickSpeed", "ticksBetweenInventoryMoves", "mineGoalUpdateInterval");
        r(0, 100, 1, "itemSaverThreshold");
        r(1000, 20000, 500, "toastTimer");
        r(20, 400, 5, "movementTimeoutTicks");
        r(0, 90, 1, "elytraPitchRange");
        r(-30, 45, 0.5, "elytraGlidePitch");
        r(1, 20, 0.5, "elytraGlideRatio");
        r(0, 3, 0.1, "elytraFireworkSpeed");
        r(1, 432, 1, "elytraMinimumDurability");
        r(0, 64, 1, "elytraMinFireworksBeforeLanding");
        r(1, 100, 1, "elytraSimulationTicks");
        r(100, 10000, 100, "primaryTimeoutMS", "planAheadPrimaryTimeoutMS");
        r(500, 20000, 100, "failureTimeoutMS", "planAheadFailureTimeoutMS");
        r(1, 32, 1, "layerHeight");
        r(1, 16, 1, "builderTickScanRadius");
        r(1, 64, 1, "yLevelBoxSize");
        r(1, 64, 1, "allowOnlyExposedOresDistance");
    }

    private SettingRanges() {}

    private static void r(double min, double max, double step, String... names) {
        for (String n : names) {
            RANGES.put(n, new Range(min, max, step));
        }
    }

    /** @return the slider range, or {@code null} when the setting should get a plain number field */
    public static Range get(String settingName) {
        return RANGES.get(settingName);
    }

    /** Scroll step for number fields without a range: 1 for integer types, 0.1 otherwise. */
    public static double defaultStep(boolean integral) {
        return integral ? 1 : 0.1;
    }
}
