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

package baritone.process.portal;

import baritone.process.portal.PortalLayout.Cell;
import baritone.process.portal.PortalLayout.Site;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Looks for a stretch of lava pool shore the {@link PortalLayout} fits on, and says what has to be cleared or filled
 * in first.
 */
public final class PortalSiteFinder {

    private PortalSiteFinder() {}

    /** What a cell is, as far as the build cares. */
    public enum Kind {
        AIR,
        /** Grass, flowers, snow layers: gone in one hit. */
        PLANT,
        /** A full block that can be stood on and clicked against. */
        SOLID,
        OBSIDIAN,
        LAVA_SOURCE,
        LAVA_FLOW,
        WATER,
        /** Anything else: chests, slabs, bedrock, unloaded chunks. Never touched. */
        OTHER
    }

    /** The world, reduced to what a cell is. */
    public interface Cells {

        Kind at(int x, int y, int z);
    }

    /** Solid blocks in the way that a site may have and still be used. */
    public static final int MAX_SOLID_BREAKS = 6;

    /** How far along the shore and out into the pool lava counts as within reach of the two standing cells. */
    private static final int SCOOP_X_MIN = -2, SCOOP_X_MAX = 5, SCOOP_Z_MIN = 1, SCOOP_Z_MAX = 4;

    /** A site and what it takes to use it. {@link #reject} is null for a usable one. */
    public static final class Candidate {

        public final Site site;
        /** Why not, or null. */
        public String reject;
        /** Cells to clear first, in site coordinates. */
        public final List<Cell> breaks = new ArrayList<>();
        /** Rim cells to fill in first. Only the two end cells can be missing. */
        public final List<Cell> rimPlaces = new ArrayList<>();
        /** Floor cells that are not lava: the pool is one deep there, and they have to be dug out and cast. */
        public final List<Cell> shallowFloor = new ArrayList<>();
        /** End cells of the row that are stone, not lava: dug out and cast as well. */
        public final List<Cell> solidEnds = new ArrayList<>();
        /** Lava sources within reach of the standing cells. */
        public int lava;
        public int solidBreaks;
        public double cost;

        Candidate(Site site) {
            this.site = site;
        }

        public boolean usable() {
            return reject == null;
        }

        /** Stone has to be dug: without a pickaxe that takes too long to be worth it. */
        public boolean needsPickaxe() {
            return !shallowFloor.isEmpty() || !solidEnds.isEmpty();
        }

        public boolean oneDeep() {
            return !shallowFloor.isEmpty();
        }

        public int lavaNeeded() {
            return PortalLayout.LAVA_NEEDED + shallowFloor.size() + solidEnds.size();
        }

        public int blocksNeeded() {
            return PortalLayout.BLOCKS_NEEDED + rimPlaces.size();
        }

        @Override
        public String toString() {
            return site + (reject != null ? ": " + reject
                    : " (" + lava + " lava" + (oneDeep() ? ", one deep" : "")
                    + (breaks.isEmpty() ? "" : ", " + breaks.size() + " to clear")
                    + (rimPlaces.isEmpty() ? "" : ", " + rimPlaces.size() + " rim to fill") + ")");
        }
    }

    private static Kind at(Cells cells, Site site, int x, int y, int z) {
        return cells.at(site.worldX(x, z), site.worldY(y), site.worldZ(x, z));
    }

    private static Kind at(Cells cells, Site site, Cell c) {
        return at(cells, site, c.x, c.y, c.z);
    }

    private static boolean ground(Kind k) {
        return k == Kind.SOLID || k == Kind.OBSIDIAN;
    }

    /** Checks one site. The result says why when it cannot be used. */
    public static Candidate evaluate(Cells cells, Site site, boolean hasPickaxe) {
        Candidate c = new Candidate(site);

        // the two cells the first water goes into and empties
        for (Cell pit : PortalLayout.PIT) {
            if (at(cells, site, pit) != Kind.LAVA_SOURCE) {
                return reject(c, "no lava source at " + pit);
            }
        }
        // the ends of the row: the frame's bottom side cells. Lava sets under the first water, stone is dug and cast.
        for (int x = 0; x <= 3; x += 3) {
            Kind k = at(cells, site, x, 1, 0);
            if (k == Kind.SOLID) {
                c.solidEnds.add(new Cell(x, 1, 0));
            } else if (k != Kind.LAVA_SOURCE && k != Kind.OBSIDIAN) {
                return reject(c, "row end " + new Cell(x, 1, 0) + " is neither lava nor solid");
            }
        }
        // under the pit: the bottom of the frame
        for (Cell floor : PortalLayout.FLOOR) {
            Kind k = at(cells, site, floor);
            if (k == Kind.SOLID) {
                c.shallowFloor.add(floor);
            } else if (k != Kind.LAVA_SOURCE && k != Kind.OBSIDIAN) {
                return reject(c, "pool floor " + floor + " is neither lava nor solid");
            }
        }
        // the pool side of the pit, which holds the water in and is then stood on
        for (Cell stand : new Cell[]{PortalLayout.STAND_A, PortalLayout.STAND_B}) {
            Kind k = at(cells, site, stand);
            if (k != Kind.LAVA_SOURCE && !ground(k)) {
                return reject(c, "nothing to stand on at " + stand);
            }
        }
        // the shore: the middle two are stood on and hold the water in, the ends can be filled in from them
        for (Cell rim : PortalLayout.RIM) {
            Kind k = at(cells, site, rim);
            if (ground(k)) {
                continue;
            }
            boolean end = rim.x == 0 || rim.x == 3;
            if (!end || k == Kind.OTHER || k == Kind.WATER) {
                return reject(c, "no solid shore at " + rim);
            }
            c.rimPlaces.add(rim);
        }
        // room for the shapes, the water, the frame and the bot
        for (Cell clear : PortalLayout.CLEAR) {
            Kind k = at(cells, site, clear);
            if (k == Kind.AIR) {
                continue;
            }
            if (k == Kind.PLANT) {
                c.breaks.add(clear);
            } else if (ground(k) && k != Kind.OBSIDIAN) {
                c.breaks.add(clear);
                c.solidBreaks++;
            } else {
                return reject(c, clear + " is in the way and cannot be cleared");
            }
        }
        if (c.solidBreaks > MAX_SOLID_BREAKS) {
            return reject(c, c.solidBreaks + " blocks in the way");
        }
        // lava to pour: sources that can be seen from the standing cells
        for (int x = SCOOP_X_MIN; x <= SCOOP_X_MAX; x++) {
            for (int z = SCOOP_Z_MIN; z <= SCOOP_Z_MAX; z++) {
                if (z == 1 && (x == 1 || x == 2)) {
                    continue;
                }
                if (at(cells, site, x, 1, z) == Kind.LAVA_SOURCE && at(cells, site, x, 2, z) == Kind.AIR) {
                    c.lava++;
                }
            }
        }
        if (c.lava < c.lavaNeeded()) {
            return reject(c, "only " + c.lava + " lava sources in reach, " + c.lavaNeeded() + " needed");
        }
        if (c.needsPickaxe() && !hasPickaxe) {
            return reject(c, "stone to dig and no pickaxe");
        }
        c.cost = c.breaks.size() * 0.5
                + c.solidBreaks * (hasPickaxe ? 3 : 6)
                + c.rimPlaces.size() * 4
                + c.shallowFloor.size() * 6
                + c.solidEnds.size() * 8
                - Math.min(c.lava, 16) * 0.25;
        return c;
    }

    private static Candidate reject(Candidate c, String why) {
        c.reject = why;
        return c;
    }

    /**
     * Every usable site within the box around the given position, best first: near, with little to prepare and
     * plenty of lava.
     */
    public static List<Candidate> find(Cells cells, int px, int py, int pz, int radius, int vertical, boolean hasPickaxe) {
        List<Candidate> found = new ArrayList<>();
        Cell anchor = PortalLayout.PIT.get(0);
        List<Site> orientations = Site.orientations(0, 0, 0);
        for (int y = py - vertical; y <= py + vertical; y++) {
            for (int x = px - radius; x <= px + radius; x++) {
                for (int z = pz - radius; z <= pz + radius; z++) {
                    if (cells.at(x, y, z) != Kind.LAVA_SOURCE || cells.at(x, y + 1, z) != Kind.AIR) {
                        continue;
                    }
                    for (Site o : orientations) {
                        Candidate c = evaluate(cells, o.anchoredAt(anchor, x, y, z), hasPickaxe);
                        if (c.usable()) {
                            Site s = c.site;
                            double dx = s.worldX(PortalLayout.RIM_FEET) - px;
                            double dy = s.worldY(PortalLayout.RIM_FEET) - py;
                            double dz = s.worldZ(PortalLayout.RIM_FEET) - pz;
                            c.cost += Math.sqrt(dx * dx + dy * dy * 4 + dz * dz);
                            found.add(c);
                        }
                    }
                }
            }
        }
        Collections.sort(found, Comparator.comparingDouble(c -> c.cost));
        return found;
    }
}
