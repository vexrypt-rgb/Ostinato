/*
 * Three-way pathfinding bench (stock Baritone / Soprano / Ostinato). Per-fork adapter: stock Baritone.
 */
package baritone.pathing.calc;

import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.world.attribute.EnvironmentAttributeMap;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Stock Baritone's search reads the world's height, min y and border from a Level. This is a Level with only those
 * answers: it is allocated without running a constructor, so nothing else in it is usable.
 */
final class XLevel extends ServerLevel {
    private DimensionType dim;
    private WorldBorder border;

    @SuppressWarnings("DataFlowIssue")
    private XLevel() {
        super(null, null, null, null, null, null, false, 0L, null, false); // never runs
    }

    static XLevel make(XWorld w) {
        try {
            Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            sun.misc.Unsafe u = (sun.misc.Unsafe) f.get(null);
            XLevel l = (XLevel) u.allocateInstance(XLevel.class);
            l.dim = new DimensionType(false, true, false, false, 1.0, XVoxels.MIN_Y, XVoxels.HEIGHT, XVoxels.HEIGHT,
                    HolderSet.direct(), 0.0f, new DimensionType.MonsterSettings(ConstantInt.of(0), 0), DimensionType.Skybox.OVERWORLD,
                    CardinalLighting.Type.DEFAULT, EnvironmentAttributeMap.EMPTY, HolderSet.direct(), Optional.empty());
            l.border = XBorder.around(w.voxels.sizeX, w.voxels.sizeZ);
            return l;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public DimensionType dimensionType() {
        return dim;
    }

    @Override
    public int getMinY() {
        return XVoxels.MIN_Y;
    }

    @Override
    public int getHeight() {
        return XVoxels.HEIGHT;
    }

    @Override
    public WorldBorder getWorldBorder() {
        return border;
    }
}
