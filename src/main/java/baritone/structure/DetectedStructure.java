package baritone.structure;

import net.minecraft.core.BlockPos;

/** One structure the bot knows about: where, which variant, and how it was found. */
public final class DetectedStructure {
    public enum Source { CLIENT, SERVER }

    public final String id;
    public final String dimension;
    public final BlockPos pos;
    public final Source source;
    public final double confidence;
    public final long foundTick;

    public DetectedStructure(String id, String dimension, BlockPos pos, Source source, double confidence, long foundTick) {
        this.id = id;
        this.dimension = dimension;
        this.pos = pos;
        this.source = source;
        this.confidence = confidence;
        this.foundTick = foundTick;
    }

    @Override
    public String toString() {
        return id + " at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " [" + source.name().toLowerCase()
                + (source == Source.CLIENT ? String.format(" %.0f%%", confidence * 100) : "") + "]";
    }
}
