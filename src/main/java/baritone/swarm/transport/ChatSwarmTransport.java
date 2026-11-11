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


package baritone.swarm.transport;

import baritone.swarm.roster.SwarmRoster;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * In-game transport: sealed lines go out as chat commands built from a template
 * ({@code /msg {to} {msg}} by default) through a {@link SwarmRateLimiter}, and come in from
 * received chat lines via {@link ChatTokens}. Nothing here touches the world or the player
 * list: the chat wrapper's sender is never resolved (an out-of-range or unknown sender cannot
 * throw); the authenticated sender is the one inside the sealed envelope.
 */
public final class ChatSwarmTransport implements SwarmTransport {

    /** Received tokens kept while waiting for the next poll; older ones are dropped first. */
    public static final int INBOUND_MAX = 512;

    private final String self;
    private final SwarmChannel defaultChannel;
    private final String customTemplate;
    private final SwarmRoster roster;
    private final SwarmRateLimiter limiter;
    private final int maxLineChars;
    private final ArrayDeque<String> inbound = new ArrayDeque<>();
    private long tooLong;
    private long inboundDropped;

    /**
     * @param customTemplate {@code swarmCommandTemplate}; empty or invalid means the channel default
     */
    public ChatSwarmTransport(String selfId, SwarmChannel channel, String customTemplate, SwarmRoster roster,
                              SwarmRateLimiter limiter, int maxLineChars) {
        this.self = selfId;
        this.defaultChannel = channel;
        this.customTemplate = customTemplate;
        this.roster = roster;
        this.limiter = limiter;
        this.maxLineChars = maxLineChars;
    }

    @Override
    public String selfId() {
        return self;
    }

    /** Channel used for {@code group} (roster override, else {@code swarmChannel}). */
    public SwarmChannel channelFor(String group) {
        SwarmRoster.Group g = group == null ? null : roster.group(group);
        return g != null && g.channel() != null ? g.channel() : defaultChannel;
    }

    /** Template used on {@code channel}: the custom one only if it suits the default channel. */
    public String templateFor(SwarmChannel channel) {
        return channel == defaultChannel ? channel.template(customTemplate) : channel.defaultTemplate();
    }

    /** Characters a template adds around the token, assuming a 16-character recipient. */
    public static int templateOverhead(String template) {
        return template.replace("{to}", "0123456789abcdef").replace("{msg}", "").length();
    }

    @Override
    public void send(String recipient, String sealedLine) throws IOException {
        sendTo(null, recipient, sealedLine, SwarmPriority.NORMAL);
    }

    @Override
    public void sendTo(String group, String recipient, String sealedLine, SwarmPriority priority) throws IOException {
        SwarmTransport.requireSealed(sealedLine);
        SwarmChannel channel = channelFor(group);
        String template = templateFor(channel);
        List<String> commands = new ArrayList<>();
        if (channel == SwarmChannel.WHISPER) {
            for (String to : recipients(group, recipient)) {
                commands.add(template.replace("{to}", to).replace("{msg}", sealedLine));
            }
        } else {
            commands.add(template.replace("{to}", BROADCAST.equals(recipient) ? "" : recipient)
                    .replace("{msg}", sealedLine));
        }
        for (String c : commands) {
            if (c.length() > maxLineChars) {
                synchronized (this) {
                    tooLong++;
                }
                throw new IOException("swarm chat line is " + c.length() + " > " + maxLineChars + " chars");
            }
        }
        for (String c : commands) {
            limiter.offer(priority, c);
        }
    }

    // Ostinato: local routing splits a broadcast into same-PC vs whisper recipients.
    Set<String> recipients(String group, String recipient) {
        Set<String> out = new LinkedHashSet<>();
        if (!BROADCAST.equals(recipient)) {
            out.add(recipient);
            return out;
        }
        List<SwarmRoster.Group> gs = new ArrayList<>();
        if (group != null && roster.group(group) != null) {
            gs.add(roster.group(group));
        } else {
            gs.addAll(roster.groups());
        }
        for (SwarmRoster.Group g : gs) {
            for (String m : g.members()) {
                if (!m.equals(self)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Send whatever the rate limiter allows now. */
    public int flush(long nowMs, Consumer<String> chat) {
        int n = 0;
        for (String line; (line = limiter.poll(nowMs)) != null; n++) {
            chat.accept(line);
        }
        return n;
    }

    /**
     * Feed one received chat line (already flattened to plain text). Only the text is used.
     *
     * @return number of candidate tokens found
     */
    public synchronized int onChat(String text) {
        List<String> tokens = ChatTokens.extract(text);
        for (String t : tokens) {
            if (inbound.size() >= INBOUND_MAX) {
                inbound.pollFirst();
                inboundDropped++;
            }
            inbound.addLast(t);
        }
        return tokens.size();
    }

    @Override
    public synchronized List<String> receive() {
        List<String> out = new ArrayList<>(inbound);
        inbound.clear();
        return out;
    }

    public SwarmRateLimiter limiter() { return limiter; }
    public synchronized long tooLong() { return tooLong; }
    public synchronized long inboundDropped() { return inboundDropped; }

    @Override
    public void close() {
    }
}
