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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static baritone.gui.model.SettingCategory.*;

/**
 * Puts a Baritone setting into a {@link SettingCategory} by its field name: an explicit override table first,
 * then ordered name rules. Nothing in {@code Settings} has to be annotated, and a unit test keeps every setting
 * out of {@link SettingCategory#OTHER}.
 */
public final class SettingCategorizer {

    private static final Map<String, SettingCategory> OVERRIDES;
    private static final List<Object[]> RULES = new ArrayList<>();

    static {
        Map<String, SettingCategory> m = new HashMap<>();
        put(m, MINING, "allowBreak", "allowBreakAnyway", "allowDownward", "blockReachDistance", "blockBreakSpeed", "avoidUpdatingFallingBlocks",
                "pauseMiningForFallingBlocks", "walkWhileBreaking", "blockBreakAdditionalPenalty", "avoidBreakingMultiplier",
                "blocksToAvoidBreaking", "blocksToDisallowBreaking", "exploreForBlocks", "disableCompletionCheck",
                "replantCrops", "replantNetherWart");
        put(m, INVENTORY, "autoTool", "assumeExternalAutoTool", "useSwordToMine", "preferSilkTouch", "itemSaver",
                "itemSaverThreshold", "rightClickSpeed", "inventoryMoveOnlyIfStationary");
        put(m, BUILDING, "acceptableThrowawayItems", "allowPlace", "blockPlacementPenalty", "okIfAir", "okIfWater",
                "incorrectSize", "distanceTrim", "mapArtMode", "startAtLayer", "layerOrder", "layerHeight", "skipFailedLayers",
                "breakFromAbove", "goalBreakFromAbove", "placeIncorrectBlockPenaltyMultiplier",
                "breakCorrectBlockPenaltyMultiplier", "backfill");
        put(m, WATER_AIR, "allowDoorAirPockets", "allowBoats", "allowBoatFall", "maxFallHeightBoat", "walkOnWaterOnePenalty",
                "assumeWalkOnWater", "strictLiquidCheck", "allowWaterBucketFall", "maxFallHeightBucket",
                "allowPlaceInFluidsSource", "allowPlaceInFluidsFlow", "sprintInWater", "swimInWater");
        put(m, RENDER, "yLevelBoxSize", "fadePath", "cachedChunksOpacity");
        put(m, MOVEMENT, "freeLook", "blockFreeLook", "smoothLook", "smoothLookTicks", "randomLooking", "randomLooking113",
                "remainWithExistingLookDirection", "antiCheatCompatibility", "kinematicTravel", "physicsTravel",
                "movementBackend", "pitfallAvoidance", "jumpPenalty", "overshootTraverse", "freecamSpeed",
                "allowNeos", "allowLadderClutch", "pickupLadders", "experimentalMovement", "experimentalJumpBias",
                "experimentalBlockPlacementPenalty", "experimentalMinHealth", "fallDamageCost");
        put(m, PATHING, "blocksToAvoid", "disconnectOnArrival", "axisHeight", "followRadius", "doBedWaypoints",
                "doDeathWaypoints", "considerPotionEffects", "enterPortal", "rightClickContainerOnArrival");
        put(m, CHAT, "censorCoordinates", "censorRanCommands", "prefix", "prefixControl", "toastTimer", "logAsToast",
                "verboseCommandExceptions", "desktopNotifications", "echoCommands", "shortBaritonePrefix", "useMessageTag");
        put(m, ADVANCED, "cutoffAtLoadBoundary", "simplifyUnloadedYCoord", "movementFault");
        put(m, INTERFACE, "guiKeybind", "renderPathHud", "pathHudAnchor", "guiAccentColor");
        OVERRIDES = Collections.unmodifiableMap(m);

        rule("^swarm", SWARM);
        rule("^elytra", ELYTRA);
        rule("^(color|render)|Render|Opacity$|LineWidth|^selection", RENDER);
        rule("^(notification|chat)", CHAT);
        rule("^(build|schematic|layer)|Schematic", BUILDING);
        rule("^(mine|legitMine|minYLevel|maxYLevel|allowOnlyExposed|forceInternal|internalMining)", MINING);
        rule("^farm", MINING);
        rule("^follow", PATHING);
        rule("^(explore|worldExploring)", MINING);
        rule("Inventory|Tool|Throwaway", INVENTORY);
        rule("Parkour|Diagonal|Sprint|sprint|Jump|Vines|Slab|Step|SafeWalk|Magma|FallHeight|WalkOn", MOVEMENT);
        rule("Water|Lava|Swim|swim|Boat|Fluid|Liquid", WATER_AIR);
        rule("Avoidance|avoidance", PATHING);
        rule("Timeout|timeout|cost|Cost|pathing|Path|path|Chunk|chunk|Cache|cache|repack|prune|splice|slowPath|planning|"
                + "Lookahead|Heuristic|Repropagation|backtrack|blacklist|Goal|goal", ADVANCED);
    }

    private SettingCategorizer() {}

    private static void put(Map<String, SettingCategory> m, SettingCategory c, String... names) {
        for (String n : names) {
            m.put(n, c);
        }
    }

    private static void rule(String regex, SettingCategory c) {
        RULES.add(new Object[]{Pattern.compile(regex), c});
    }

    public static SettingCategory categorize(String settingName) {
        SettingCategory o = OVERRIDES.get(settingName);
        if (o != null) {
            return o;
        }
        for (Object[] r : RULES) {
            if (((Pattern) r[0]).matcher(settingName).find()) {
                return (SettingCategory) r[1];
            }
        }
        return OTHER;
    }
}
