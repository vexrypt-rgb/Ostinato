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

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/**
 * Ed25519 via the JDK (Java 15+, no extra dependency; the 1.16.1 branch uses BouncyCastle for Java 8).
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
        return new SigilEd25519(seed.clone(), derivePublic(seed));
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
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey(seed));
            signer.update(message);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new SigilException("Ed25519 sign failed: " + e.getMessage());
        }
    }

    public boolean verify(byte[] message, byte[] sig) {
        if (sig == null || sig.length != SIG_LEN || message == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(pub));
            verifier.update(message);
            return verifier.verify(sig);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false; // e.g. a public key that is not a curve point
        }
    }

    // RFC 8410 DER prefixes: PKCS#8 private key and X.509 SubjectPublicKeyInfo for id-Ed25519, raw key appended
    private static final byte[] PKCS8_PREFIX = {0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65,
            0x70, 0x04, 0x22, 0x04, 0x20};
    private static final byte[] X509_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21,
            0x00};

    private static PrivateKey privateKey(byte[] seed) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(concat(PKCS8_PREFIX, seed)));
    }

    private static PublicKey publicKey(byte[] pub) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(concat(X509_PREFIX, pub)));
    }

    /** The JDK has no public-from-private call; its key generator takes the seed from the random source. */
    private static byte[] derivePublic(byte[] seed) throws SigilException {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            gen.initialize(NamedParameterSpec.ED25519, new SecureRandom() {
                @Override
                public void nextBytes(byte[] bytes) {
                    System.arraycopy(seed, 0, bytes, 0, Math.min(seed.length, bytes.length));
                }
            });
            byte[] der = gen.generateKeyPair().getPublic().getEncoded();
            return Arrays.copyOfRange(der, der.length - KEY_LEN, der.length);
        } catch (GeneralSecurityException e) {
            throw new SigilException("Ed25519 key derivation failed: " + e.getMessage());
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] sha256(byte[] in) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(in);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
