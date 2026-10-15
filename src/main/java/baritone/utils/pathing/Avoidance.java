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

package baritone.utils.pathing;

import baritone.Baritone;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.process.MobProfile;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Avoidance {

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final double coefficient;
    private final int radius;
    private final int radiusSq;
    /** Asked per step during the search. A sphere painted into the favor map hitches the game thread when many exist. */
    private final boolean query;

    public Avoidance(BlockPos center, double coefficient, int radius) {
        this(center.getX(), center.getY(), center.getZ(), coefficient, radius, false);
    }

    public Avoidance(int centerX, int centerY, int centerZ, double coefficient, int radius) {
        this(centerX, centerY, centerZ, coefficient, radius, false);
    }

    public Avoidance(int centerX, int centerY, int centerZ, double coefficient, int radius, boolean query) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.coefficient = coefficient;
        this.radius = radius;
        this.radiusSq = radius * radius;
        this.query = query;
    }

    public boolean queried() {
        return query;
    }

    /**
     * Multiplier for a step at this block. The center is {@link #coefficient}; it falls to 1 at the edge of the reach.
     */
    public double coefficient(int x, int y, int z) {
        int xDiff = x - centerX;
        int yDiff = y - centerY;
        int zDiff = z - centerZ;
        int d2 = xDiff * xDiff + yDiff * yDiff + zDiff * zDiff;
        if (d2 > radiusSq) {
            return 1.0D;
        }
        if (coefficient == 1.0D || radius <= 0) {
            return coefficient;
        }
        double t = 1.0D - Math.sqrt(d2) / radius;
        return 1.0D + (coefficient - 1.0D) * t * t;
    }

    public static List<Avoidance> create(IPlayerContext ctx) {
        if (!Baritone.settings().avoidance.value) {
            return Collections.emptyList();
        }
        List<Avoidance> res = new ArrayList<>();
        double mobSpawnerCoeff = Baritone.settings().mobSpawnerAvoidanceCoefficient.value;
        double mobCoeff = Baritone.settings().mobAvoidanceCoefficient.value;
        if (mobSpawnerCoeff != 1.0D) {
            ctx.worldData().getCachedWorld().getLocationsOf("mob_spawner", 1, ctx.playerFeet().x, ctx.playerFeet().z, 2)
                    .forEach(mobspawner -> res.add(new Avoidance(mobspawner, mobSpawnerCoeff, Baritone.settings().mobSpawnerAvoidanceRadius.value)));
        }
        if (mobCoeff != 1.0D) {
            BlockPos feet = ctx.playerFeet();
            int fx = feet.getX(), fy = feet.getY(), fz = feet.getZ();
            double scale = (mobCoeff - 1.0D) / 0.5D;
            double reachScale = Baritone.settings().mobAvoidanceRadius.value / 8.0D;
            int flatRadius = Baritone.settings().mobAvoidanceRadius.value;
            ctx.entitiesStream()
                    .filter(entity -> entity instanceof LivingEntity)
                    .map(entity -> (LivingEntity) entity)
                    .filter(MobProfile::hostile)
                    .filter(entity -> !(entity instanceof Spider) || ctx.player().getLightLevelDependentMagicValue() < 0.5)
                    .filter(entity -> !(entity instanceof ZombifiedPiglin) || ((ZombifiedPiglin) entity).getLastHurtByMob() != null)
                    .filter(entity -> !(entity instanceof Enderman) || ((Enderman) entity).isCreepy())
                    .forEach(entity -> {
                        BlockPos at = entity.blockPosition();
                        int dx = at.getX() - fx, dy = at.getY() - fy, dz = at.getZ() - fz;
                        if (dx * dx + dy * dy + dz * dz > 64 * 64) {
                            return;
                        }
                        if (scale <= 0) {
                            res.add(new Avoidance(at, mobCoeff, flatRadius));
                            return;
                        }
                        MobProfile prof = MobProfile.of(entity);
                        double center = prof.pathCenter();
                        int reach = prof.pathRadius();
                        if (entity instanceof Creeper creeper && (creeper.isIgnited() || creeper.getSwelling(0f) > 0)) {
                            center = 20;
                            reach = 8;
                        }
                        int radius = Math.max(2, (int) Math.round(reach * reachScale));
                        res.add(new Avoidance(at.getX(), at.getY(), at.getZ(), 1 + (center - 1) * scale, radius, true));
                    });
        }
        return res;
    }

    public void applySpherical(Long2DoubleOpenHashMap map) {
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z <= radius * radius) {
                        long hash = BetterBlockPos.longHash(centerX + x, centerY + y, centerZ + z);
                        map.put(hash, map.get(hash) * coefficient);
                    }
                }
            }
        }
    }
}
