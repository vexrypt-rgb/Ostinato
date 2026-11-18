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

package baritone.swarm.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Ostinato: Phase 2c. The run number a bot stamps on its observation messages. The coordinator drops a message whose
 * run is older than one it already applied from that bot, so a relaunch must always get a larger number, also when
 * the clock stepped back between runs: max(now, last + 1), with the last one kept in a small file.
 */
public final class RunEpoch {

    private RunEpoch() {}

    /** The next run number for the bot whose file this is; never smaller than or equal to the last one written. */
    public static long next(Path file, long nowMs) {
        long last = 0L;
        try {
            if (Files.exists(file)) {
                last = Long.parseLong(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim());
            }
        } catch (IOException | NumberFormatException e) {
            last = 0L; // unreadable: the clock alone still gives a plausible, usually larger number
        }
        long epoch = Math.max(Math.max(1L, nowMs), last + 1);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, Long.toString(epoch).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // not written: this run still uses its number; the next one falls back to the clock
        }
        return epoch;
    }
}
