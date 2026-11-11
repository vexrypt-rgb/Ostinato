package baritone.swarm.accounts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/** Ostinato: the plaintext layout inside a protected account file. Callers wipe the bytes they no longer need. */
public final class AuthBlob {

    private AuthBlob() {}

    public record Decoded(String username, String uuid, String access, String refresh, long expiry) {
        @Override
        public String toString() {
            return "auth [redacted]";
        }
    }

    public static byte[] encode(String username, String uuid, String access, String refresh, long expiry) throws IOException {
        try {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(raw);
            out.writeInt(1);
            out.writeUTF(username == null ? "" : username);
            out.writeUTF(uuid == null ? "" : uuid);
            out.writeUTF(access == null ? "" : access);
            out.writeUTF(refresh == null ? "" : refresh);
            out.writeLong(expiry);
            out.flush();
            return raw.toByteArray();
        } catch (IOException e) {
            throw new IOException("could not write account");
        }
    }

    public static Decoded decode(byte[] plain) throws IOException {
        if (plain == null) {
            throw new IOException("could not read account");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain))) {
            int version = in.readInt();
            if (version != 1) {
                throw new IOException("could not read account");
            }
            return new Decoded(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong());
        } catch (IOException e) {
            throw new IOException("could not read account");
        }
    }
}
