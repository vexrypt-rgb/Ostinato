package baritone.swarm.center.channel;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ostinato: Phase 3 command channel authentication. HMAC-SHA256 with a 32-byte key the command host makes at start and
 * writes to {@code host.key} (user-only permissions). Holding the key is what makes a caller the window owner. A
 * request also carries a fresh id and its time; {@link Replay} refuses a repeated id or a time outside
 * {@link #SKEW_MS}.
 */
public final class CenterAuth {

    public static final int KEY_BYTES = 32;
    /** Largest clock difference between window and host that a request may have. */
    public static final long SKEW_MS = 30_000L;

    private CenterAuth() {}

    public static byte[] newKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    public static String encodeKey(byte[] key) {
        return HexFormat.of().formatHex(key);
    }

    /** The key in {@code host.key}, or null when it is missing or malformed. */
    public static byte[] readKey(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            byte[] key = HexFormat.of().parseHex(text);
            return key.length == KEY_BYTES ? key : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static String sign(byte[] key, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** Constant-time check of a hex MAC. */
    public static boolean verify(byte[] key, String canonical, String macHex) {
        if (key == null || macHex == null) {
            return false;
        }
        byte[] want = sign(key, canonical).getBytes(StandardCharsets.US_ASCII);
        byte[] got = macHex.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(want, got);
    }

    /** Request ids seen in the last {@link #SKEW_MS} x 2. Thread-safe. */
    public static final class Replay {
        private final LinkedHashMap<String, Long> seen = new LinkedHashMap<>();

        /** Null when the request may run; otherwise the refusal reason. Records the id when it may. */
        public synchronized String admit(String id, long atMs, long nowMs) {
            if (id == null || id.isBlank() || id.length() > 64) {
                return "bad id";
            }
            if (Math.abs(nowMs - atMs) > SKEW_MS) {
                return "stale";
            }
            Iterator<Map.Entry<String, Long>> it = seen.entrySet().iterator();
            while (it.hasNext()) {
                if (nowMs - it.next().getValue() > 2 * SKEW_MS) {
                    it.remove();
                } else {
                    break; // insertion order: the rest are newer
                }
            }
            if (seen.containsKey(id)) {
                return "replay";
            }
            if (seen.size() >= 10_000) {
                return "busy";
            }
            seen.put(id, nowMs);
            return null;
        }
    }
}
