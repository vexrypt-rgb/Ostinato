package baritone.swarm.center.channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.LongSupplier;

/**
 * Ostinato: Phase 3 command channel, the listening side. Pure JDK so it is tested without Minecraft; the bot wraps it
 * ({@code CenterHost}) and supplies the {@link Handler} that runs registry calls on the client thread.
 *
 * <p>Binds 127.0.0.1 only, on an ephemeral port. One request line per connection, then one reply line, then close.
 * Order of checks: loopback peer, size, parse, MAC, replay/time; only then the handler. A refused request never
 * reaches the handler. One daemon accept thread; each connection is handled on it in turn (requests are tiny and the
 * handler bounds its own wait), so a flood cannot start threads.
 */
public final class CenterHostCore implements AutoCloseable {

    /** Runs one verified request. May block up to its own timeout (the bot waits for its client thread). */
    @FunctionalInterface
    public interface Handler {
        CenterReply handle(CenterRequest request);
    }

    /** Socket read timeout per connection. */
    public static final int READ_TIMEOUT_MS = 3_000;

    private final byte[] key;
    private final Handler handler;
    private final LongSupplier clock;
    private final CenterAuth.Replay replay = new CenterAuth.Replay();
    private final ServerSocket server;
    private final Thread thread;
    private volatile boolean closed;
    private volatile long served;
    private volatile long refused;

    public CenterHostCore(byte[] key, Handler handler, LongSupplier clock) throws IOException {
        if (key == null || key.length != CenterAuth.KEY_BYTES || handler == null) {
            throw new IllegalArgumentException("key and handler");
        }
        this.key = key.clone();
        this.handler = handler;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
        this.thread = new Thread(this::loop, "ostinato-center-host");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public long served() {
        return served;
    }

    public long refused() {
        return refused;
    }

    private void loop() {
        while (!closed) {
            try (Socket s = server.accept()) {
                serve(s);
            } catch (IOException | RuntimeException e) {
                if (closed) {
                    return;
                }
            }
        }
    }

    private void serve(Socket s) throws IOException {
        if (!s.getInetAddress().isLoopbackAddress()) {
            refused++;
            return; // never answer a non-loopback peer
        }
        s.setSoTimeout(READ_TIMEOUT_MS);
        String wire = readLine(s);
        CenterReply reply = answer(wire);
        OutputStream out = s.getOutputStream();
        out.write((reply.toWire(key) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** Everything after the socket: visible for tests that drive the checks without a connection. */
    CenterReply answer(String wire) {
        long now = clock.getAsLong();
        if (wire == null) {
            refused++;
            return CenterReply.refused("", "too long", now);
        }
        CenterRequest req;
        try {
            req = CenterRequest.fromWire(wire);
        } catch (IllegalArgumentException e) {
            refused++;
            return CenterReply.refused("", "malformed", now);
        }
        if (!CenterRequest.verified(wire, req, key)) {
            refused++;
            return CenterReply.refused(req.id(), "bad mac", now);
        }
        String bad = replay.admit(req.id(), req.atMs(), now);
        if (bad != null) {
            refused++;
            return CenterReply.refused(req.id(), bad, now);
        }
        if (req.op() == CenterRequest.Op.PING) {
            served++;
            return new CenterReply(req.id(), now, true, false, "pong", "", "");
        }
        CenterReply r;
        try {
            r = handler.handle(req);
        } catch (RuntimeException e) {
            r = CenterReply.refused(req.id(), "failed", now);
        }
        served++;
        return r == null ? CenterReply.refused(req.id(), "failed", now) : r;
    }

    /** One line, at most {@link CenterRequest#MAX_CHARS}; null when it is longer. */
    private static String readLine(Socket s) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (sb.length() >= CenterRequest.MAX_CHARS) {
                return null;
            }
            sb.append((char) c);
        }
        return sb.toString();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
