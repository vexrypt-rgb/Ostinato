/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc.hierarchy;

import baritone.pathing.movement.CalculationContext;

/**
 * Ostinato: one level of the multi-level (Distant Horizons-style) pathing hierarchy. A cell at this level covers
 * {@code 1 << shift()} blocks per axis; {@link #outgoing} gives the edge cost (ticks) to each of the 26 neighbours in
 * {@link SectionGraph#DIRS} order, +inf where there is none. Level 0 above blocks is {@link SectionGraph} (16^3),
 * coarser levels are {@link CoarseGraph}s built from the level below.
 */
public interface LevelGraph {

    int shift();

    float[] outgoing(CalculationContext ctx, int cx, int cy, int cz);

    /** Drop the cell holding fine-level cell (fx,fy,fz) of the level below (and the neighbours whose edges read it). */
    default void invalidateFine(int fx, int fy, int fz) {}
}
