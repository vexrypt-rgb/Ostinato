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

package baritone.api.swarm.protocol;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Ostinato: small versioned capability descriptor for one bot.
 * Version 1 kinds are navigation, mining, building, combat, inventory and liveRendering.
 * A newer version matches nothing, so the allocator refuses work it cannot understand.
 */
public final class CapabilityDescriptor {

    public static final int VERSION = 1;

    private final int version;
    private final Set<String> kinds;
    private final boolean understood;

    private CapabilityDescriptor(int version, Set<String> kinds, boolean understood) {
        this.version = version;
        this.kinds = kinds;
        this.understood = understood;
    }

    /** Gameplay kinds a live bot may advertise. liveRendering is not included. */
    public static CapabilityDescriptor gameplay() {
        return of("navigation", "mining", "building", "combat", "inventory");
    }

    public static CapabilityDescriptor of(String... kinds) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (kinds != null) {
            for (String k : kinds) {
                if (k != null && !k.isBlank()) {
                    set.add(k.trim());
                }
            }
        }
        return new CapabilityDescriptor(VERSION, Collections.unmodifiableSet(set), true);
    }

    public static CapabilityDescriptor decode(String text) {
        if (text == null || text.isBlank()) {
            return new CapabilityDescriptor(0, Collections.emptySet(), false);
        }
        int colon = text.indexOf(':');
        if (colon <= 1 || text.charAt(0) != 'v') {
            return new CapabilityDescriptor(0, Collections.emptySet(), false);
        }
        int version;
        try {
            version = Integer.parseInt(text.substring(1, colon));
        } catch (NumberFormatException e) {
            return new CapabilityDescriptor(0, Collections.emptySet(), false);
        }
        if (version != VERSION) {
            return new CapabilityDescriptor(version, Collections.emptySet(), false);
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        String rest = text.substring(colon + 1).trim();
        if (!rest.isEmpty()) {
            for (String part : rest.split(",")) {
                String k = part.trim();
                if (!k.isEmpty()) {
                    set.add(k);
                }
            }
        }
        return new CapabilityDescriptor(VERSION, Collections.unmodifiableSet(set), true);
    }

    public int version() {
        return version;
    }

    public boolean understood() {
        return understood;
    }

    public Set<String> kinds() {
        return kinds;
    }

    public String encode() {
        StringBuilder b = new StringBuilder();
        b.append('v').append(VERSION).append(':');
        boolean first = true;
        for (String k : kinds) {
            if (!first) {
                b.append(',');
            }
            first = false;
            b.append(k);
        }
        return b.toString();
    }

    /** Null when every required kind is supported. Unknown versions fail closed. */
    public String rejection(Set<String> required) {
        if (required == null || required.isEmpty()) {
            return null;
        }
        if (!understood || version != VERSION) {
            return "capability descriptor v" + version + " is not understood";
        }
        for (String need : required) {
            if (need == null || need.isBlank()) {
                continue;
            }
            if (!kinds.contains(need.trim())) {
                return "missing " + need.trim();
            }
        }
        return null;
    }

    public static String hex(byte[] id) {
        if (id == null || id.length == 0) {
            return "unsigned";
        }
        StringBuilder b = new StringBuilder(id.length * 2);
        for (byte v : id) {
            b.append(Character.forDigit((v >> 4) & 0xF, 16));
            b.append(Character.forDigit(v & 0xF, 16));
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }
}
