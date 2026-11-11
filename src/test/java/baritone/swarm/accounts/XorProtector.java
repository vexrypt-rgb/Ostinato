package baritone.swarm.accounts;

/** Ostinato: test stand-in for DPAPI. Not used by the client. */
public final class XorProtector implements SecretProtector {

    @Override
    public byte[] protect(byte[] plain) {
        byte[] out = new byte[plain.length + 1];
        out[0] = 0x5A;
        for (int i = 0; i < plain.length; i++) {
            out[i + 1] = (byte) (plain[i] ^ 0xA5);
        }
        return out;
    }

    @Override
    public byte[] unprotect(byte[] blob) {
        if (blob == null || blob.length == 0 || blob[0] != 0x5A) {
            throw new IllegalStateException("could not read account");
        }
        byte[] out = new byte[blob.length - 1];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (blob[i + 1] ^ 0xA5);
        }
        return out;
    }
}
