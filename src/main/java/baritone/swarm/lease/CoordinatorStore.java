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

package baritone.swarm.lease;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Ostinato: durable coordinator state as one atomic text snapshot.
 *
 * <p>Why not SQLite: it would add a native library (and ProGuard rules) for a few hundred rows written by one
 * thread. A whole-file snapshot written to a temp file, fsynced, then moved over the old file with
 * {@code ATOMIC_MOVE} is crash-safe for that size: a reader sees either the old snapshot or the new one, never
 * half of one. A torn temp file left by a crash is ignored. A main file that does not parse is an error, and the
 * coordinator refuses to grant rather than guess.
 *
 * <pre>
 * ostinato-coordinator v1
 * hw|resource|gen
 * lease|leaseId|resource|gen|commandId|bot|state|notBeforeWall|exec|obs|endReason|b64(commandLine)
 * end
 * </pre>
 */
public final class CoordinatorStore {

    public static final String HEADER = "ostinato-coordinator v1";
    /** Terminal records kept for dedup and audit; live ones are always kept. */
    public static final int KEEP_TERMINAL = 200;

    public static final class Snapshot {
        public final Map<String, Long> highWater = new LinkedHashMap<>();
        public final List<LeaseRecord> leases = new ArrayList<>();
    }

    private final Path file;

    public CoordinatorStore(Path file) {
        this.file = file;
    }

    public Path path() {
        return file;
    }

    public String describe() {
        return "store=" + file + " (atomic text snapshot: temp+fsync+ATOMIC_MOVE; SQLite skipped: native libs,"
                + " ProGuard, single writer, few hundred rows)";
    }

    /** An empty snapshot when no file exists yet. Throws when the file exists but is corrupt. */
    public Snapshot load() throws IOException {
        Snapshot s = new Snapshot();
        if (!Files.exists(file)) {
            return s;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
            throw new IOException("bad coordinator store header in " + file);
        }
        boolean ended = false;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                continue;
            }
            if ("end".equals(line)) {
                ended = true;
                break;
            }
            String[] f = line.split("\\|", -1);
            try {
                if ("hw".equals(f[0]) && f.length == 3) {
                    s.highWater.put(f[1], Long.parseLong(f[2]));
                } else if ("lease".equals(f[0]) && f.length == 12) {
                    LeaseRecord r = new LeaseRecord(UUID.fromString(f[1]), f[2], Long.parseLong(f[3]),
                            UUID.fromString(f[4]), f[5],
                            new String(Base64.getDecoder().decode(f[11]), StandardCharsets.UTF_8),
                            LeaseRecord.State.valueOf(f[6].toUpperCase(Locale.ROOT)));
                    r.notBeforeWall = Long.parseLong(f[7]);
                    r.exec = "-".equals(f[8]) ? null : ExecutionState.valueOf(f[8].toUpperCase(Locale.ROOT));
                    r.obs = ObservationState.valueOf(f[9].toUpperCase(Locale.ROOT));
                    r.endReason = "-".equals(f[10]) ? null : f[10];
                    s.leases.add(r);
                } else {
                    throw new IOException("bad coordinator store line " + (i + 1) + " in " + file);
                }
            } catch (IllegalArgumentException e) {
                throw new IOException("bad coordinator store line " + (i + 1) + " in " + file + ": " + e.getMessage());
            }
        }
        if (!ended) {
            throw new IOException("coordinator store " + file + " has no end marker");
        }
        return s;
    }

    /** Write {@code highWater} and {@code leases} atomically. Keeps every live record and the newest terminal ones. */
    public void save(Map<String, Long> highWater, List<LeaseRecord> leases) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Map.Entry<String, Long> e : highWater.entrySet()) {
            sb.append("hw|").append(e.getKey()).append('|').append(e.getValue()).append('\n');
        }
        int terminal = 0;
        for (LeaseRecord r : leases) {
            if (!r.state.live()) {
                terminal++;
            }
        }
        int skip = Math.max(0, terminal - KEEP_TERMINAL);
        for (LeaseRecord r : leases) {
            if (!r.state.live() && skip > 0) {
                skip--;
                continue;
            }
            sb.append("lease|").append(r.leaseId).append('|').append(r.resource).append('|').append(r.gen)
                    .append('|').append(r.commandId).append('|').append(r.bot).append('|')
                    .append(r.state.name().toLowerCase(Locale.ROOT)).append('|').append(r.notBeforeWall).append('|')
                    .append(r.exec == null ? "-" : r.exec.name().toLowerCase(Locale.ROOT)).append('|')
                    .append(r.obs.name().toLowerCase(Locale.ROOT)).append('|')
                    .append(r.endReason == null ? "-" : r.endReason).append('|')
                    .append(Base64.getEncoder().encodeToString(r.commandLine.getBytes(StandardCharsets.UTF_8)))
                    .append('\n');
        }
        sb.append("end\n");
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(sb.toString().getBytes(StandardCharsets.UTF_8));
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // No atomic rename here means no crash safety: refuse rather than write a file that can tear.
            Files.deleteIfExists(tmp);
            throw new IOException("atomic move unsupported for " + file, e);
        }
    }
}
