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

package baritone.swarm.protocol;

/**
 * Ostinato: read TenorClef's {@code @} catalog as text. TenorClef is not on this compile classpath,
 * so a missing class is normal and means there is nothing to merge.
 */
public final class AtCatalogBridge {

    private AtCatalogBridge() {}

    /** Catalog text, or null when TenorClef is not installed. */
    public static String exportInstalled() {
        try {
            Class<?> type = Class.forName("adris.altoclef.swarm.Phase1AtCatalog");
            Object text = type.getMethod("exportInstalled").invoke(null);
            return text instanceof String ? (String) text : null;
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
