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

package baritone.maze;

import baritone.maze.BlockProbe.BlockKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decides whether a planned room would be visible from a cave or the surface.
 * Air inside the room must be wrapped by the plan. Every solid face that looks out of the plan must look at solid
 * ground. The ceiling needs {@link MazeSeed#COVER} solid blocks above it. The corridor stub must end in a cap.
 */
public final class ShellSeal {

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private ShellSeal() {}

    public record BlockPos3(int x, int y, int z) {}

    public static final class SealVerdict {
        public final boolean airEnclosed;
        /** No air, fluid, or unloaded block touches the outside of the shell. */
        public final boolean shellClear;
        /** Enough solid cover above the ceiling that the room is not open to the sky or a cave overhead. */
        public final boolean underground;
        public final boolean stubResealed;
        public final List<BlockPos3> breaches;
        /** Null when {@link #ok()} is true. */
        public final String reason;

        SealVerdict(boolean airEnclosed, boolean shellClear, boolean underground, boolean stubResealed,
                    List<BlockPos3> breaches, String reason) {
            this.airEnclosed = airEnclosed;
            this.shellClear = shellClear;
            this.underground = underground;
            this.stubResealed = stubResealed;
            this.breaches = breaches;
            this.reason = reason;
        }

        public boolean sealed() {
            return airEnclosed && shellClear && stubResealed;
        }

        public boolean ok() {
            return sealed() && underground;
        }
    }

    public static SealVerdict check(RoomPlan plan, BlockProbe probe) {
        Set<BlockPos3> breaches = new LinkedHashSet<>();
        boolean airEnclosed = true;
        boolean shellClear = true;
        boolean underground = true;
        int caps = 0;
        boolean capTouchesAir = false;

        for (int x = 0; x < plan.sizeX; x++) {
            for (int y = 0; y < plan.sizeY; y++) {
                for (int z = 0; z < plan.sizeZ; z++) {
                    BlockRole role = plan.role(x, y, z);
                    if (role == BlockRole.STUB_CAP) {
                        caps++;
                    }
                    if (role == BlockRole.AIR) {
                        for (int[] dir : DIRS) {
                            int nx = x + dir[0];
                            int ny = y + dir[1];
                            int nz = z + dir[2];
                            if (!plan.inRelative(nx, ny, nz) || plan.role(nx, ny, nz) == BlockRole.OUTSIDE) {
                                airEnclosed = false;
                                breaches.add(world(plan, nx, ny, nz));
                            } else if (plan.role(nx, ny, nz) == BlockRole.STUB_CAP) {
                                capTouchesAir = true;
                            }
                        }
                    } else if (role.solid()) {
                        for (int[] dir : DIRS) {
                            int nx = x + dir[0];
                            int ny = y + dir[1];
                            int nz = z + dir[2];
                            if (plan.inRelative(nx, ny, nz) && plan.role(nx, ny, nz) != BlockRole.OUTSIDE) {
                                continue;
                            }
                            BlockPos3 at = world(plan, nx, ny, nz);
                            if (probe.get(at.x, at.y, at.z) != BlockKind.SOLID) {
                                shellClear = false;
                                breaches.add(at);
                            }
                        }
                    }
                    if (role == BlockRole.CEILING) {
                        for (int i = 1; i <= MazeSeed.COVER; i++) {
                            int wy = plan.originY + y + i;
                            BlockKind kind = kindAt(plan, probe, x, y + i, z, wy);
                            if (kind != BlockKind.SOLID) {
                                underground = false;
                                breaches.add(new BlockPos3(plan.originX + x, wy, plan.originZ + z));
                            }
                        }
                    }
                }
            }
        }
        boolean stubResealed = caps > 0 && capTouchesAir && airEnclosed;
        String reason = null;
        if (!airEnclosed) {
            reason = "interior opens through the shell";
        } else if (!stubResealed) {
            reason = "corridor stub is not resealed";
        } else if (!shellClear) {
            reason = "air, fluid, or an unloaded block touches the shell";
        } else if (!underground) {
            reason = "shell is visible from open air above";
        }
        return new SealVerdict(airEnclosed, shellClear, underground, stubResealed, List.copyOf(breaches), reason);
    }

    /**
     * Blocks a later maintenance pass would fill back in. Empty when the shell is intact and covered.
     * This pass only lists them; it does not place anything.
     */
    public static List<BlockPos3> maintenanceTargets(RoomPlan plan, BlockProbe probe) {
        return new ArrayList<>(check(plan, probe).breaches);
    }

    private static BlockKind kindAt(RoomPlan plan, BlockProbe probe, int rx, int ry, int rz, int worldY) {
        if (plan.inRelative(rx, ry, rz) && plan.role(rx, ry, rz).solid()) {
            return BlockKind.SOLID;
        }
        return probe.get(plan.originX + rx, worldY, plan.originZ + rz);
    }

    private static BlockPos3 world(RoomPlan plan, int rx, int ry, int rz) {
        return new BlockPos3(plan.originX + rx, plan.originY + ry, plan.originZ + rz);
    }
}
