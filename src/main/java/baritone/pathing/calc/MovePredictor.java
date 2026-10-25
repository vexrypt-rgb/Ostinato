/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.pathing.calc;

import baritone.pathing.movement.CalculationContext;
import net.minecraft.world.level.block.Block;

/**
 * Ostinato: tiny optional movement predictor (setting {@code pathPredictMoves}, off by default). A per-search lookup
 * table, keyed by move type and the block ids under and at the destination column, counts how often a move was
 * rejected (infinite cost). Once a key has been rejected {@link #CONFIDENT} times and never accepted, the move is
 * skipped without running its cost function. No ML stack: a 64K-entry short table, 128 KB, reused per search.
 */
final class MovePredictor {

    static final int CONFIDENT = 8;
    private static final int BITS = 16;
    private final short[] table = new short[1 << BITS]; // >0: rejects seen; -1: accepted at least once

    int key(CalculationContext ctx, int ordinal, int x, int y, int z, int dx, int dy, int dz) {
        int tx = x + dx, ty = y + dy, tz = z + dz;
        int a = Block.BLOCK_STATE_REGISTRY.getId(ctx.get(tx, ty - 1, tz));
        int b = Block.BLOCK_STATE_REGISTRY.getId(ctx.get(tx, ty, tz));
        int c = Block.BLOCK_STATE_REGISTRY.getId(ctx.get(tx, ty + 1, tz));
        int h = ordinal * 0x9E3779B1 ^ a * 0x85EBCA6B ^ b * 0xC2B2AE35 ^ c * 0x27D4EB2F;
        return (h ^ (h >>> 16)) & ((1 << BITS) - 1);
    }

    boolean predictReject(int key) {
        return table[key] >= CONFIDENT;
    }

    void learn(int key, boolean rejected) {
        short v = table[key];
        if (!rejected) table[key] = -1;
        else if (v >= 0 && v < Short.MAX_VALUE) table[key] = (short) (v + 1);
    }
}
