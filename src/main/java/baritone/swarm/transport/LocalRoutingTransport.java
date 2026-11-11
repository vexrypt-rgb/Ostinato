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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

// Ostinato: same-PC members get a local spool; everyone else still uses whispers, and whispers are the fallback.
public final class LocalRoutingTransport implements SwarmTransport {

    public static final String ALIVE_FILE = ".alive";
    public static final long ALIVE_TTL_MS = 30_000L;
    private static final long TOUCH_EVERY_MS = 5_000L;

    private final String self;
    private final SwarmTransport fallback;
    private final ChatSwarmTransport chat;
    private final SwarmTransport local;
    private final Path root;
    private final boolean enabled;
    private final LongSupplier clock;
    private final long aliveTtlMs;
    private final Function<String, Set<String>> broadcastTargets;
    private long lastTouchMs;

    public static LocalRoutingTransport open(String self, ChatSwarmTransport chat, boolean enabled,
            String spoolDir, Path gameDir, Consumer<String> log) {
        LocalSpoolTransport local = null;
        Path root = null;
        if (enabled) {
            try {
                Path defaultRoot = gameDir.resolve("swarm-spool");
                root = (spoolDir == null || spoolDir.isBlank())
                        ? defaultRoot
                        : gameDir.getFileSystem().getPath(spoolDir);
                local = new LocalSpoolTransport(root, self);
                root = root.toAbsolutePath().normalize();
            } catch (Exception e) {
                local = null;
                root = null;
                if (log != null) {
                    log.accept("swarm: local routing unavailable (" + e.getMessage() + "); using whispers");
                }
            }
        }
        LocalRoutingTransport transport = new LocalRoutingTransport(self, chat, local, root, enabled,
                System::currentTimeMillis, ALIVE_TTL_MS, null);
        transport.touchAlive();
        return transport;
    }

    LocalRoutingTransport(String self, SwarmTransport fallback, SwarmTransport local, Path root,
            boolean enabled, LongSupplier clock, long aliveTtlMs, Function<String, Set<String>> broadcastTargets) {
        this.self = self;
        this.fallback = fallback;
        this.chat = fallback instanceof ChatSwarmTransport ? (ChatSwarmTransport) fallback : null;
        this.local = local;
        this.root = root;
        this.enabled = enabled;
        this.clock = clock;
        this.aliveTtlMs = aliveTtlMs;
        this.broadcastTargets = broadcastTargets;
    }

    public int flush(long nowMs, Consumer<String> chatSink) {
        touchAlive();
        return chat == null ? 0 : chat.flush(nowMs, chatSink);
    }

    public int onChat(String text) {
        return chat == null ? 0 : chat.onChat(text);
    }

    public SwarmRateLimiter limiter() {
        return chat == null ? null : chat.limiter();
    }

    public long tooLong() {
        return chat == null ? 0L : chat.tooLong();
    }

    public void touchAlive() {
        if (!enabled || local == null || root == null) {
            return;
        }
        long now = clock.getAsLong();
        if (lastTouchMs != 0L && now - lastTouchMs < TOUCH_EVERY_MS) {
            return;
        }
        lastTouchMs = now;
        try {
            Path inbox = root.resolve(self);
            Files.createDirectories(inbox);
            Files.writeString(inbox.resolve(ALIVE_FILE), Long.toString(now));
        } catch (IOException ignored) {
            // Next send will fall back to whispers if the marker cannot be refreshed.
        }
    }

    public boolean isLocal(String name) {
        return liveMember(name);
    }

    @Override
    public String selfId() {
        return self;
    }

    @Override
    public void send(String recipient, String sealedLine) throws IOException {
        sendTo(null, recipient, sealedLine, SwarmPriority.NORMAL);
    }

    @Override
    public void sendTo(String group, String recipient, String sealedLine, SwarmPriority priority) throws IOException {
        SwarmTransport.requireSealed(sealedLine);
        if (BROADCAST.equals(recipient)) {
            sendBroadcast(group, sealedLine, priority);
            return;
        }
        if (liveMember(recipient)) {
            try {
                local.sendTo(group, recipient, sealedLine, priority);
                return;
            } catch (IOException ignored) {
                // Local channel is down; the whisper path below is the documented fallback.
            }
        }
        fallback.sendTo(group, recipient, sealedLine, priority);
    }

    @Override
    public List<String> receive() throws IOException {
        touchAlive();
        List<String> out = new ArrayList<>();
        if (local != null) {
            try {
                out.addAll(local.receive());
            } catch (IOException ignored) {
                // Keep the whisper inbox even if the spool cannot be read this tick.
            }
        }
        out.addAll(fallback.receive());
        return out;
    }

    @Override
    public void close() {
        if (local != null) {
            try {
                local.close();
            } catch (Exception ignored) {
            }
        }
        try {
            fallback.close();
        } catch (Exception ignored) {
        }
    }

    private void sendBroadcast(String group, String sealedLine, SwarmPriority priority) throws IOException {
        Set<String> targets = broadcastTargets(group);
        Set<String> sentLocal = new LinkedHashSet<>();
        for (String name : targets) {
            if (!liveMember(name)) {
                continue;
            }
            try {
                local.sendTo(group, name, sealedLine, priority);
                sentLocal.add(name);
            } catch (IOException ignored) {
                // Leave the name for the whisper pass.
            }
        }
        for (String name : targets) {
            if (!name.equals(self) && !sentLocal.contains(name)) {
                fallback.sendTo(group, name, sealedLine, priority);
            }
        }
    }

    private Set<String> broadcastTargets(String group) {
        if (broadcastTargets != null) {
            return new LinkedHashSet<>(broadcastTargets.apply(group));
        }
        if (chat != null) {
            return new LinkedHashSet<>(chat.recipients(group, BROADCAST));
        }
        return listLiveMembers();
    }

    private Set<String> listLiveMembers() {
        Set<String> out = new LinkedHashSet<>();
        if (!enabled || local == null || root == null || !Files.isDirectory(root)) {
            return out;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                if (liveMember(id)) {
                    out.add(id);
                }
            }
        } catch (IOException ignored) {
            return out;
        }
        return out;
    }

    private boolean liveMember(String name) {
        if (!enabled || local == null || root == null || name == null || name.equals(self) || BROADCAST.equals(name)) {
            return false;
        }
        try {
            Path alive = root.resolve(name).resolve(ALIVE_FILE);
            if (!Files.isRegularFile(alive)) {
                return false;
            }
            String raw = Files.readString(alive).trim();
            long written = Long.parseLong(raw);
            return clock.getAsLong() - written <= aliveTtlMs;
        } catch (IOException | NumberFormatException e) {
            return false;
        }
    }
}
