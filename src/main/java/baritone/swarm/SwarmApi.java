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
package baritone.swarm;

import baritone.api.swarm.ISwarm;
import baritone.api.swarm.ISwarmGroup;
import baritone.behavior.SwarmBehavior;
import baritone.swarm.roster.SwarmRoster;
import baritone.swarm.transport.SwarmPriority;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Ostinato: {@link ISwarm} over {@link SwarmBehavior}. Delegates only; no behaviour of its own. */
public final class SwarmApi implements ISwarm {

    private final SwarmBehavior behavior;

    public SwarmApi(SwarmBehavior behavior) {
        this.behavior = behavior;
    }

    @Override
    public boolean isOnline() {
        return behavior.control() != null;
    }

    @Override
    public String state() {
        String s = behavior.state();
        return s == null ? "online" : s;
    }

    @Override
    public String self() {
        SwarmControl c = behavior.control();
        return c == null ? null : c.endpoint().selfId();
    }

    @Override
    public List<ISwarmGroup> myGroups() {
        SwarmControl c = behavior.control();
        if (c == null) return Collections.emptyList();
        List<ISwarmGroup> out = new ArrayList<>();
        for (SwarmRoster.Group g : c.roster().groupsOf(c.endpoint().selfId())) out.add(view(g));
        return out;
    }

    @Override
    public ISwarmGroup group(String id) {
        SwarmControl c = behavior.control();
        SwarmRoster.Group g = c == null ? null : c.roster().group(id);
        return g == null ? null : view(g);
    }

    @Override
    public boolean send(String group, String to, String type, String body, baritone.api.swarm.SwarmPriority priority) {
        SwarmControl c = behavior.control();
        if (c == null) return false;
        try {
            c.endpoint().send(group, to, type, body, map(priority));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean registerHandler(String type, SwarmHandler handler) {
        SwarmControl c = behavior.control();
        if (c == null) return false;
        c.registerHandler(type, m -> handler.onMessage(m.from(), m.group(), m.body()));
        return true;
    }

    static SwarmPriority map(baritone.api.swarm.SwarmPriority p) {
        if (p == null) return SwarmPriority.NORMAL;
        switch (p) {
            case HIGH: return SwarmPriority.HIGH;
            case LOW: return SwarmPriority.LOW;
            default: return SwarmPriority.NORMAL;
        }
    }

    private static ISwarmGroup view(SwarmRoster.Group g) {
        return new ISwarmGroup() {
            @Override public String id() { return g.id(); }
            @Override public String lead() { return g.lead(); }
            @Override public List<String> members() { return Collections.unmodifiableList(g.members()); }
            @Override public boolean has(String member) { return g.has(member); }
        };
    }
}
