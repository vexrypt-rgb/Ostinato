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
package baritone.api.process;

/** Ostinato: immutable snapshot of {@link IPvpProcess} counters. Final class, not a record, so it backports to Java 8. */
public final class PvpStats {
    public final int attacks, crits, sprintHits, axeHits, blocks, gapples;
    public final float damageTaken;

    public PvpStats(int attacks, int crits, int sprintHits, int axeHits, int blocks, int gapples, float damageTaken) {
        this.attacks = attacks;
        this.crits = crits;
        this.sprintHits = sprintHits;
        this.axeHits = axeHits;
        this.blocks = blocks;
        this.gapples = gapples;
        this.damageTaken = damageTaken;
    }

    @Override
    public String toString() {
        return String.format("attacks=%d crits=%d sprintHits=%d axeHits=%d blocks=%d gapples=%d dmgTaken=%.1f",
                attacks, crits, sprintHits, axeHits, blocks, gapples, damageTaken);
    }
}
