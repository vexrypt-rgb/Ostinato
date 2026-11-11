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

package baritone.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: parse {@code command-inbox.txt} lines for {@link CommandInbox}.
 * Leading {@code #} or {@code @} is stripped. Empty lines and {@code //} or {@code ;} comments are ignored.
 */
public final class CommandInboxText {

    private CommandInboxText() {}

    public static List<String> commands(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        for (String raw : text.split("\r?\n", -1)) {
            String line = normalize(raw);
            if (line != null) {
                out.add(line);
            }
        }
        return out;
    }

    /** {@code null} if the line should not be executed. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("//") || line.startsWith(";")) {
            return null;
        }
        char c = line.charAt(0);
        if (c == '#' || c == '@') {
            line = line.substring(1).trim();
        }
        return line.isEmpty() ? null : line;
    }
}
