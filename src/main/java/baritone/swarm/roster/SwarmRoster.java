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


package baritone.swarm.roster;

import baritone.swarm.frame.SwarmFrame;
import baritone.swarm.transport.SwarmChannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Swarm roster ({@code baritone/swarm.txt} by default, {@code swarmRosterFile}). One line per group:
 *
 * <pre>
 * # comment
 * group builders circle=my-circle members=Alice,Bob,Carol lead=Alice parent=- transport=whisper
 * </pre>
 *
 * <ul>
 *   <li>{@code circle} (required): name of the sigil circle record to use for this group. Each group
 *       needs its own circle (a shared circle would let one group impersonate the other).</li>
 *   <li>{@code members} (required): exact Minecraft usernames, comma-separated.</li>
 *   <li>{@code lead}, {@code parent} (optional, used by the hierarchy slice): the lead must be a
 *       member; the parent must be another group, without cycles; {@code -} means none.</li>
 *   <li>{@code transport} (optional): {@code whisper|global|team}, overriding {@code swarmChannel}.</li>
 * </ul>
 *
 * The roster holds names only, never keys or passphrases; keys come from the sigil keyring.
 */
public final class SwarmRoster {

    private static final Pattern MEMBER = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern CIRCLE = Pattern.compile("[^\\s=,]{1,64}");

    /** One roster group. */
    public static final class Group {
        private final String id;
        private final String circle;
        private final List<String> members;
        private final String lead;
        private final String parent;
        private final SwarmChannel channel;

        Group(String id, String circle, List<String> members, String lead, String parent, SwarmChannel channel) {
            this.id = id;
            this.circle = circle;
            this.members = Collections.unmodifiableList(members);
            this.lead = lead;
            this.parent = parent;
            this.channel = channel;
        }

        public String id() { return id; }
        public String circle() { return circle; }
        public List<String> members() { return members; }
        /** Lead member, or {@code null}. */
        public String lead() { return lead; }
        /** Parent group id, or {@code null}. */
        public String parent() { return parent; }
        /** Channel override, or {@code null} for {@code swarmChannel}. */
        public SwarmChannel channel() { return channel; }
        public boolean has(String member) { return members.contains(member); }
    }

    private final Map<String, Group> groups;

    private SwarmRoster(Map<String, Group> groups) {
        this.groups = Collections.unmodifiableMap(groups);
    }

    public static SwarmRoster empty() {
        return new SwarmRoster(new LinkedHashMap<>());
    }

    public Collection<Group> groups() { return groups.values(); }
    public Group group(String id) { return groups.get(id); }

    // Ostinato: canonical serialization preserves group order and all routing/hierarchy fields.
    public String toText() {
        StringBuilder out = new StringBuilder();
        for (Group g : groups.values()) {
            out.append("group ").append(g.id()).append(" circle=").append(g.circle())
                    .append(" members=").append(String.join(",", g.members()));
            if (g.lead() != null) out.append(" lead=").append(g.lead());
            if (g.parent() != null) out.append(" parent=").append(g.parent());
            if (g.channel() != null) out.append(" transport=").append(g.channel().name().toLowerCase(Locale.ROOT));
            out.append('\n');
        }
        return out.toString();
    }

    // Ostinato: immutable update, with the same validation as a hand-written roster.
    public SwarmRoster withMember(String group, String member) throws SwarmRosterException {
        Group old = groups.get(group);
        if (old == null) throw new IllegalArgumentException("Unknown swarm group " + group);
        if (old.members().stream().anyMatch(m -> m.equalsIgnoreCase(member))) return this;
        List<String> members = new ArrayList<>(old.members());
        members.add(member);
        Map<String, Group> copy = new LinkedHashMap<>(groups);
        copy.put(group, new Group(old.id(), old.circle(), members, old.lead(), old.parent(), old.channel()));
        return parse(new SwarmRoster(copy).toText());
    }

    public boolean isMember(String group, String member) {
        Group g = groups.get(group);
        return g != null && g.has(member);
    }

    /** Groups {@code member} belongs to, in file order. */
    public List<Group> groupsOf(String member) {
        List<Group> out = new ArrayList<>();
        for (Group g : groups.values()) {
            if (g.has(member)) {
                out.add(g);
            }
        }
        return out;
    }

    /** Parse roster text; errors carry the 1-based line number. */
    public static SwarmRoster parse(String text) throws SwarmRosterException {
        Map<String, Group> groups = new LinkedHashMap<>();
        Map<String, String> circleOwner = new HashMap<>();
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int n = 1; n <= lines.length; n++) {
            String line = lines[n - 1].trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] words = line.split("\\s+");
            if (!words[0].equals("group") || words.length < 2) {
                throw new SwarmRosterException(n, "expected 'group <id> circle=... members=...'");
            }
            String id = words[1];
            if (!SwarmFrame.isValidId(id)) {
                throw new SwarmRosterException(n, "bad group id '" + id + "' (use [A-Za-z0-9_-]{1,16})");
            }
            if (groups.containsKey(id)) {
                throw new SwarmRosterException(n, "duplicate group '" + id + "'");
            }
            Map<String, String> kv = new HashMap<>();
            for (int i = 2; i < words.length; i++) {
                int eq = words[i].indexOf('=');
                if (eq <= 0) {
                    throw new SwarmRosterException(n, "expected key=value, got '" + words[i] + "'");
                }
                String k = words[i].substring(0, eq).toLowerCase(Locale.ROOT);
                if (!(k.equals("circle") || k.equals("members") || k.equals("lead") || k.equals("parent")
                        || k.equals("transport"))) {
                    throw new SwarmRosterException(n, "unknown key '" + k + "'");
                }
                if (kv.put(k, words[i].substring(eq + 1)) != null) {
                    throw new SwarmRosterException(n, "duplicate key '" + k + "'");
                }
            }
            String circle = kv.get("circle");
            if (circle == null || !CIRCLE.matcher(circle).matches()) {
                throw new SwarmRosterException(n, "group '" + id + "' needs circle=<sigil circle name>");
            }
            String prev = circleOwner.put(circle, id);
            if (prev != null) {
                throw new SwarmRosterException(n, "groups '" + prev + "' and '" + id + "' share circle '" + circle
                        + "'; each group needs its own circle");
            }
            String ms = kv.get("members");
            if (ms == null || ms.isEmpty()) {
                throw new SwarmRosterException(n, "group '" + id + "' needs members=<name,name,...>");
            }
            List<String> members = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String m : ms.split(",", -1)) {
                if (!MEMBER.matcher(m).matches()) {
                    throw new SwarmRosterException(n, "bad member name '" + m + "'");
                }
                if (!seen.add(m.toLowerCase(Locale.ROOT))) {
                    throw new SwarmRosterException(n, "member '" + m + "' listed twice in group '" + id + "'");
                }
                members.add(m);
            }
            String lead = kv.get("lead");
            if (lead != null && !members.contains(lead)) {
                throw new SwarmRosterException(n, "lead '" + lead + "' is not a member of '" + id + "'");
            }
            String parent = kv.get("parent");
            if ("-".equals(parent) || "".equals(parent)) {
                parent = null;
            }
            SwarmChannel channel = null;
            String t = kv.get("transport");
            if (t != null) {
                String tl = t.toLowerCase(Locale.ROOT);
                if (!(tl.equals("whisper") || tl.equals("global") || tl.equals("team"))) {
                    throw new SwarmRosterException(n, "transport must be whisper|global|team");
                }
                channel = SwarmChannel.parse(tl);
            }
            groups.put(id, new Group(id, circle, members, lead, parent, channel));
        }
        for (Group g : groups.values()) {
            if (g.parent() != null && !groups.containsKey(g.parent())) {
                throw new SwarmRosterException(0, "group '" + g.id() + "' has unknown parent '" + g.parent() + "'");
            }
            Set<String> path = new HashSet<>();
            for (Group cur = g; cur != null; cur = cur.parent() == null ? null : groups.get(cur.parent())) {
                if (!path.add(cur.id())) {
                    throw new SwarmRosterException(0, "parent cycle through group '" + g.id() + "'");
                }
            }
        }
        return new SwarmRoster(groups);
    }
}
