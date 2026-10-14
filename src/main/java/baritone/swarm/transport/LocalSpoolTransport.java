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

import baritone.swarm.SwarmConfig;
import baritone.swarm.frame.SwarmFrame;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Same-machine transport: one inbox directory per member under a shared
 * spool root ({@code swarmLocalSpoolDir}). A send writes a temp file and
 * atomically renames it into the recipient's inbox; receive reads and deletes
 * {@code *.frame} files in name order. Only sealed S1C/S2C tokens are written, so
 * the spool never holds plaintext. Member ids are restricted to
 * {@code [A-Za-z0-9_-]{1,16}}, which keeps paths inside the root.
 */
public final class LocalSpoolTransport implements SwarmTransport {

    private static final String SUFFIX = ".frame";

    private final Path root;
    private final String self;
    private final Path inbox;
    private final AtomicLong counter = new AtomicLong();

    public LocalSpoolTransport(Path root, String selfId) throws IOException {
        if (!SwarmFrame.isValidId(selfId)) {
            throw new IllegalArgumentException("bad member id");
        }
        this.root = root.toAbsolutePath().normalize();
        this.self = selfId;
        this.inbox = inboxOf(selfId);
        Files.createDirectories(inbox);
    }

    /** Open at {@code swarmLocalSpoolDir}, or {@code defaultRoot} when that setting is empty. */
    public static LocalSpoolTransport open(SwarmConfig cfg, Path defaultRoot, String selfId) throws IOException {
        Path root = cfg.localSpoolDir().isEmpty() ? defaultRoot : defaultRoot.getFileSystem().getPath(cfg.localSpoolDir());
        return new LocalSpoolTransport(root, selfId);
    }

    private Path inboxOf(String id) {
        return root.resolve(id);
    }

    @Override
    public String selfId() {
        return self;
    }

    @Override
    public void send(String recipient, String sealedLine) throws IOException {
        SwarmTransport.requireSealed(sealedLine);
        if (BROADCAST.equals(recipient)) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root)) {
                for (Path d : dirs) {
                    String id = d.getFileName().toString();
                    if (!id.equals(self) && SwarmFrame.isValidId(id) && Files.isDirectory(d)) {
                        deliver(d, sealedLine);
                    }
                }
            }
            return;
        }
        if (!SwarmFrame.isValidId(recipient)) {
            throw new IllegalArgumentException("bad recipient id");
        }
        Path dir = inboxOf(recipient);
        Files.createDirectories(dir); // mail waits for a member that has not started yet
        deliver(dir, sealedLine);
    }

    private void deliver(Path dir, String line) throws IOException {
        Path tmp = dir.resolve(".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, line.getBytes(StandardCharsets.US_ASCII));
            String name = String.format("%016x-%s-%08x%s", System.currentTimeMillis(), self, counter.incrementAndGet(), SUFFIX);
            Files.move(tmp, dir.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp); // only still there when the write or the rename failed
        }
    }

    @Override
    public List<String> receive() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inbox, "*" + SUFFIX)) {
            for (Path p : ds) {
                files.add(p);
            }
        }
        Collections.sort(files);
        List<String> out = new ArrayList<>(files.size());
        for (Path p : files) {
            String line;
            try {
                line = new String(Files.readAllBytes(p), StandardCharsets.US_ASCII).trim();
                Files.delete(p);
            } catch (NoSuchFileException raced) {
                continue;
            } catch (IOException busy) {
                // held open by something else for a moment; it stays in the inbox for the next poll, and the
                // lines already taken out of it are not lost with this one
                continue;
            }
            out.add(line);
        }
        return out;
    }

    @Override
    public void close() {
        // Nothing held open; the inbox stays so queued mail survives a restart.
    }
}
