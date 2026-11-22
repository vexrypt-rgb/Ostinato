package baritone.swarm.center.channel;

import baritone.swarm.center.feed.CenterFeed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * Ostinato: Phase 3 command channel, the window's side. Reads {@code host.json} and {@code host.key} from the feed
 * directory on every send (a restarted host has a new port and key), connects to 127.0.0.1 only, and returns the
 * host's signed reply. Never throws: every failure is a refused {@link CenterReply} whose reason the window shows.
 */
public final class CenterClient implements CommandPort {

    public static final int TIMEOUT_MS = 8_000;

    private final Path dir;
    private final LongSupplier clock;

    public CenterClient(Path dir, LongSupplier clock) {
        this.dir = dir;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    @Override
    public CenterReply send(CenterRequest request) {
        long now = clock.getAsLong();
        String hostText = CenterFeed.read(CenterFeed.hostFile(dir));
        if (hostText == null) {
            return CenterReply.refused(request.id(), "no command host (host.json missing)", now);
        }
        HostInfo host;
        try {
            host = HostInfo.fromJson(hostText);
        } catch (IllegalArgumentException e) {
            return CenterReply.refused(request.id(), "host.json: " + e.getMessage(), now);
        }
        byte[] key = CenterAuth.readKey(CenterFeed.keyFile(dir));
        if (key == null) {
            return CenterReply.refused(request.id(), "no host key", now);
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), host.port()), TIMEOUT_MS);
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write((request.toWire(key) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) {
                return CenterReply.refused(request.id(), "host closed the connection", clock.getAsLong());
            }
            return CenterReply.fromWire(line, key, clock.getAsLong());
        } catch (IOException | RuntimeException e) {
            return CenterReply.refused(request.id(), "host unreachable on port " + host.port(), clock.getAsLong());
        }
    }
}
