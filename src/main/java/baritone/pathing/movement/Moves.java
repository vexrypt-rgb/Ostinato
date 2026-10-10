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

package baritone.pathing.movement;

import baritone.api.utils.BetterBlockPos;
import baritone.pathing.movement.movements.*;
import baritone.utils.pathing.MutableMoveResult;
import net.minecraft.core.Direction;

/**
 * An enum of all possible movements attached to all possible directions they could be taken in
 *
 * @author leijurv
 */
public enum Moves {
    DOWNWARD(0, -1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementDownward(context.getBaritone(), src, src.below());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementDownward.cost(context, x, y, z);
        }
    },

    PILLAR(0, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementPillar(context.getBaritone(), src, src.above());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementPillar.cost(context, x, y, z);
        }
    },

    SWIM_UP(0, 1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (0), src.y + (1), src.z + (0)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 0, 1, 0);
        }
    },

    SWIM_NORTH(0, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (0), src.y + (0), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 0, 0, -1);
        }
    },

    SWIM_SOUTH(0, 0, 1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (0), src.y + (0), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 0, 0, 1);
        }
    },

    SWIM_EAST(1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (0), src.z + (0)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, 0, 0);
        }
    },

    SWIM_WEST(-1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (0), src.z + (0)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, 0, 0);
        }
    },

    SWIM_NORTHEAST(1, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (0), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, 0, -1);
        }
    },

    SWIM_NORTHWEST(-1, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (0), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, 0, -1);
        }
    },

    SWIM_SOUTHEAST(1, 0, 1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (0), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, 0, 1);
        }
    },

    SWIM_SOUTHWEST(-1, 0, 1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (0), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, 0, 1);
        }
    },

    SWIM_DOWN(0, -1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (0), src.y + (-1), src.z + (0)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 0, -1, 0);
        }
    },

    SWIM_NORTH_UP(0, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (0), src.y + (1), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 0, 1, -1);
        }
    },

    SWIM_NORTH_DOWN(0, -1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (0), src.y + (-1), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 0, -1, -1);
        }
    },

    SWIM_SOUTH_UP(0, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (0), src.y + (1), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 0, 1, 1);
        }
    },

    SWIM_SOUTH_DOWN(0, -1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (0), src.y + (-1), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 0, -1, 1);
        }
    },

    SWIM_EAST_UP(+1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (1), src.z + (0)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, 1, 0);
        }
    },

    SWIM_EAST_DOWN(+1, -1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (-1), src.z + (0)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, -1, 0);
        }
    },

    SWIM_WEST_UP(-1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (1), src.z + (0)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, 1, 0);
        }
    },

    SWIM_WEST_DOWN(-1, -1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (-1), src.z + (0)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, -1, 0);
        }
    },

    SWIM_NORTHEAST_UP(+1, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (1), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, 1, -1);
        }
    },

    SWIM_NORTHEAST_DOWN(+1, -1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (-1), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, -1, -1);
        }
    },

    SWIM_NORTHWEST_UP(-1, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (1), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, 1, -1);
        }
    },

    SWIM_NORTHWEST_DOWN(-1, -1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (-1), src.z + (-1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, -1, -1);
        }
    },

    SWIM_SOUTHEAST_UP(+1, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (1), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, 1, 1);
        }
    },

    SWIM_SOUTHEAST_DOWN(+1, -1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (1), src.y + (-1), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, 1, -1, 1);
        }
    },

    SWIM_SOUTHWEST_UP(-1, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (1), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, 1, 1);
        }
    },

    SWIM_SOUTHWEST_DOWN(-1, -1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementSwim(context.getBaritone(), src, new BetterBlockPos(src.x + (-1), src.y + (-1), src.z + (1)));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementSwim.cost(context, x, y, z, -1, -1, 1);
        }
    },

    TRAVERSE_NORTH(0, 0, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.north());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z - 1) + baritone.bastion.EdgeCost.penalty(context, x, y, z - 1);
        }
    },

    TRAVERSE_SOUTH(0, 0, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.south());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x, z + 1) + baritone.bastion.EdgeCost.penalty(context, x, y, z + 1);
        }
    },

    TRAVERSE_EAST(+1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.east());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x + 1, z) + baritone.bastion.EdgeCost.penalty(context, x + 1, y, z);
        }
    },

    TRAVERSE_WEST(-1, 0, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementTraverse(context.getBaritone(), src, src.west());
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementTraverse.cost(context, x, y, z, x - 1, z) + baritone.bastion.EdgeCost.penalty(context, x - 1, y, z);
        }
    },

    ASCEND_NORTH(0, +1, -1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x, src.y + 1, src.z - 1));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z - 1);
        }
    },

    ASCEND_SOUTH(0, +1, +1) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x, src.y + 1, src.z + 1));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x, z + 1);
        }
    },

    ASCEND_EAST(+1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x + 1, src.y + 1, src.z));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x + 1, z);
        }
    },

    ASCEND_WEST(-1, +1, 0) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return new MovementAscend(context.getBaritone(), src, new BetterBlockPos(src.x - 1, src.y + 1, src.z));
        }

        @Override
        public double cost(CalculationContext context, int x, int y, int z) {
            return MovementAscend.cost(context, x, y, z, x - 1, z);
        }
    },

    DESCEND_EAST(+1, -1, 0, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x + 1, z, result);
        }
    },

    DESCEND_WEST(-1, -1, 0, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x - 1, z, result);
        }
    },

    DESCEND_NORTH(0, -1, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z - 1, result);
        }
    },

    DESCEND_SOUTH(0, -1, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            if (res.y == src.y - 1) {
                return new MovementDescend(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            } else {
                return new MovementFall(context.getBaritone(), src, new BetterBlockPos(res.x, res.y, res.z));
            }
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDescend.cost(context, x, y, z, x, z + 1, result);
        }
    },

    DIAGONAL_NORTHEAST(+1, 0, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.NORTH, Direction.EAST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z - 1, result);
                result.cost += baritone.bastion.EdgeCost.penalty(context, x + 1, result.y, z - 1);
        }
    },

    DIAGONAL_NORTHWEST(-1, 0, -1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.NORTH, Direction.WEST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z - 1, result);
                result.cost += baritone.bastion.EdgeCost.penalty(context, x - 1, result.y, z - 1);
        }
    },

    DIAGONAL_SOUTHEAST(+1, 0, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.SOUTH, Direction.EAST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x + 1, z + 1, result);
                result.cost += baritone.bastion.EdgeCost.penalty(context, x + 1, result.y, z + 1);
        }
    },

    DIAGONAL_SOUTHWEST(-1, 0, +1, false, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            MutableMoveResult res = new MutableMoveResult();
            apply(context, src.x, src.y, src.z, res);
            return new MovementDiagonal(context.getBaritone(), src, Direction.SOUTH, Direction.WEST, res.y - src.y);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementDiagonal.cost(context, x, y, z, x - 1, z + 1, result);
                result.cost += baritone.bastion.EdgeCost.penalty(context, x - 1, result.y, z + 1);
        }
    },

    PARKOUR_NORTH(0, 0, -4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.NORTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.NORTH, result);
        }
    },

    PARKOUR_SOUTH(0, 0, +4, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.SOUTH);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.SOUTH, result);
        }
    },

    PARKOUR_EAST(+4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.EAST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.EAST, result);
        }
    },

    PARKOUR_WEST(-4, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementParkour.cost(context, src, Direction.WEST);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementParkour.cost(context, x, y, z, Direction.WEST, result);
        }
    },

    JUMP_0(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 0, result);
        }
    },

    JUMP_1(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 1, result);
        }
    },

    JUMP_2(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 2);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 2, result);
        }
    },

    JUMP_3(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 3);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 3, result);
        }
    },

    JUMP_4(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 4);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 4, result);
        }
    },

    JUMP_5(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 5);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 5, result);
        }
    },

    JUMP_6(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 6);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 6, result);
        }
    },

    JUMP_7(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 7);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 7, result);
        }
    },

    JUMP_8(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 8);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 8, result);
        }
    },

    JUMP_9(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 9);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 9, result);
        }
    },

    JUMP_10(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 10);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 10, result);
        }
    },

    JUMP_11(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 11);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 11, result);
        }
    },

    JUMP_12(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 12);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 12, result);
        }
    },

    JUMP_13(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 13);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 13, result);
        }
    },

    JUMP_14(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 14);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 14, result);
        }
    },

    JUMP_15(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 15);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 15, result);
        }
    },

    JUMP_16(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 16);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 16, result);
        }
    },

    JUMP_17(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 17);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 17, result);
        }
    },

    JUMP_18(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 18);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 18, result);
        }
    },

    JUMP_19(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 19);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 19, result);
        }
    },

    JUMP_20(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 20);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 20, result);
        }
    },

    JUMP_21(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 21);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 21, result);
        }
    },

    JUMP_22(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 22);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 22, result);
        }
    },

    JUMP_23(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementJump.cost(context, src, 23);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementJump.cost(context, x, y, z, 23, result);
        }
    },

    SLIME_0(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementSlime.cost(context, src, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementSlime.cost(context, x, y, z, 0, result);
        }
    },

    SLIME_1(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementSlime.cost(context, src, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementSlime.cost(context, x, y, z, 1, result);
        }
    },

    SLIME_2(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementSlime.cost(context, src, 2);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementSlime.cost(context, x, y, z, 2, result);
        }
    },

    SLIME_3(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementSlime.cost(context, src, 3);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementSlime.cost(context, x, y, z, 3, result);
        }
    },

    CLIMB_JUMP_0(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimbJump.cost(context, src, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimbJump.cost(context, x, y, z, 0, result);
        }
    },

    CLIMB_JUMP_1(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimbJump.cost(context, src, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimbJump.cost(context, x, y, z, 1, result);
        }
    },

    CLIMB_JUMP_2(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimbJump.cost(context, src, 2);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimbJump.cost(context, x, y, z, 2, result);
        }
    },

    CLIMB_JUMP_3(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimbJump.cost(context, src, 3);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimbJump.cost(context, x, y, z, 3, result);
        }
    },

    CLIMB_JUMP_4(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimbJump.cost(context, src, 4);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimbJump.cost(context, x, y, z, 4, result);
        }
    },

    CLIMB_JUMP_5(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementClimbJump.cost(context, src, 5);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementClimbJump.cost(context, x, y, z, 5, result);
        }
    },

    CHAIN_JUMP_0(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementChainJump.cost(context, src, 0);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementChainJump.cost(context, x, y, z, 0, result);
        }
    },

    CHAIN_JUMP_1(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementChainJump.cost(context, src, 1);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementChainJump.cost(context, x, y, z, 1, result);
        }
    },

    CHAIN_JUMP_2(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementChainJump.cost(context, src, 2);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementChainJump.cost(context, x, y, z, 2, result);
        }
    },

    CHAIN_JUMP_3(0, 0, 0, true, true) {
        @Override
        public Movement apply0(CalculationContext context, BetterBlockPos src) {
            return MovementChainJump.cost(context, src, 3);
        }

        @Override
        public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
            MovementChainJump.cost(context, x, y, z, 3, result);
        }
    };

    public final boolean dynamicXZ;
    public final boolean dynamicY;

    public final int xOffset;
    public final int yOffset;
    public final int zOffset;

    Moves(int x, int y, int z, boolean dynamicXZ, boolean dynamicY) {
        this.xOffset = x;
        this.yOffset = y;
        this.zOffset = z;
        this.dynamicXZ = dynamicXZ;
        this.dynamicY = dynamicY;
    }

    Moves(int x, int y, int z) {
        this(x, y, z, false, false);
    }

    public abstract Movement apply0(CalculationContext context, BetterBlockPos src);

    public void apply(CalculationContext context, int x, int y, int z, MutableMoveResult result) {
        if (dynamicXZ || dynamicY) {
            throw new UnsupportedOperationException("Movements with dynamic offset must override `apply`");
        }
        result.x = x + xOffset;
        result.y = y + yOffset;
        result.z = z + zOffset;
        result.cost = cost(context, x, y, z);
    }

    public double cost(CalculationContext context, int x, int y, int z) {
        throw new UnsupportedOperationException("Movements must override `cost` or `apply`");
    }
}
