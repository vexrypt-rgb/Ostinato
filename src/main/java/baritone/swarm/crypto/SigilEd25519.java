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

package baritone.swarm.crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * Ed25519 via BouncyCastle lightweight API (no JCE provider registration).
 * Matches RFC 8032 / sigil 0.5.0. Keys are 32-byte seeds / 32-byte public.
 */
public final class SigilEd25519 {

    public static final int KEY_LEN = 32;
    public static final int SIG_LEN = 64;
    public static final int KEYID_LEN = 8;

    private final byte[] seed;
    private final byte[] pub;

    private SigilEd25519(byte[] seed, byte[] pub) {
        this.seed = seed;
        this.pub = pub;
    }

    public static SigilEd25519 fromSeed(byte[] seed) throws SigilException {
        if (seed == null || seed.length != KEY_LEN) {
            throw new SigilException("Ed25519 seed must be 32 bytes.");
        }
        Ed25519PrivateKeyParameters sk = new Ed25519PrivateKeyParameters(seed, 0);
        return new SigilEd25519(seed.clone(), sk.generatePublicKey().getEncoded());
    }

    public static SigilEd25519 fromPublic(byte[] pub) throws SigilException {
        if (pub == null || pub.length != KEY_LEN) {
            throw new SigilException("Ed25519 public key must be 32 bytes.");
        }
        return new SigilEd25519(null, pub.clone());
    }

    /** Deterministic TEST-ONLY signet: SHA-256(label + "-ed25519"). Label must contain DO-NOT-USE. */
    public static SigilEd25519 testSignet(String label) throws SigilException {
        if (label == null || !label.contains("DO-NOT-USE")) {
            throw new SigilException("Test signets must be labelled DO-NOT-USE.");
        }
        return fromSeed(sha256((label + "-ed25519").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public byte[] publicKey() {
        return pub.clone();
    }

    /** Whether this holds the private seed, not only the public key. */
    public boolean canSign() {
        return seed != null;
    }

    public byte[] keyid() {
        return Arrays.copyOf(sha256(pub), KEYID_LEN);
    }

    public static byte[] keyidOf(byte[] pub) {
        return Arrays.copyOf(sha256(pub), KEYID_LEN);
    }

    public byte[] sign(byte[] message) throws SigilException {
        if (seed == null) {
            throw new SigilException("Cannot sign without a private seed.");
        }
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, new Ed25519PrivateKeyParameters(seed, 0));
        signer.update(message, 0, message.length);
        return signer.generateSignature();
    }

    public boolean verify(byte[] message, byte[] sig) {
        if (sig == null || sig.length != SIG_LEN || message == null) {
            return false;
        }
        Ed25519Signer signer = new Ed25519Signer();
        signer.init(false, new Ed25519PublicKeyParameters(pub, 0));
        signer.update(message, 0, message.length);
        return signer.verifySignature(sig);
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
