/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.swarm.crypto;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** SIGIL 0.5.0 S2S: S2 framing, ctx SIGIL.v2.S.name, last-part keyid||Ed25519. */
public final class SigilS2S {

    public static final String VERSION = "S2S";
    public static final int TRAILER = SigilEd25519.KEYID_LEN + SigilEd25519.SIG_LEN;
    static final byte[] SIG_DOMAIN = new byte[] {
            'S', 'I', 'G', 'I', 'L', '.', 'v', '2', '.', 's', 'i', 'g', 0
    };
    private static final SecureRandom RANDOM = new SecureRandom();

    private SigilS2S() {}

    public static byte[] context(String circleName) {
        return ("SIGIL.v2.S." + circleName).getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] signedBytes(byte[] ctx, byte[] keyid, List<byte[]> headers, List<byte[]> pts)
            throws SigilException {
        if (headers.size() != pts.size() || headers.isEmpty() || headers.size() > SigilS2C.MAX_PARTS
                || keyid.length != SigilEd25519.KEYID_LEN) {
            throw new SigilException("bad S2S signing input");
        }
        int n = headers.size();
        int len = SIG_DOMAIN.length + 2 + ctx.length + keyid.length + 1;
        for (int i = 0; i < n; i++) {
            len += 1 + headers.get(i).length + 2 + pts.get(i).length;
        }
        byte[] out = new byte[len];
        int p = 0;
        System.arraycopy(SIG_DOMAIN, 0, out, p, SIG_DOMAIN.length);
        p += SIG_DOMAIN.length;
        out[p++] = (byte) (ctx.length >> 8);
        out[p++] = (byte) ctx.length;
        System.arraycopy(ctx, 0, out, p, ctx.length);
        p += ctx.length;
        System.arraycopy(keyid, 0, out, p, keyid.length);
        p += keyid.length;
        out[p++] = (byte) n;
        for (int i = 0; i < n; i++) {
            byte[] h = headers.get(i);
            byte[] pt = pts.get(i);
            out[p++] = (byte) h.length;
            System.arraycopy(h, 0, out, p, h.length);
            p += h.length;
            out[p++] = (byte) (pt.length >> 8);
            out[p++] = (byte) pt.length;
            System.arraycopy(pt, 0, out, p, pt.length);
            p += pt.length;
        }
        return out;
    }

    public static int maxSingleLinePayloadBytes(int maxLine) {
        return Math.max(0, SigilS2C.payloadRoom(maxLine, false) - TRAILER);
    }

    public static String sealSingle(SigilCircle circle, SigilEd25519 signet, String plaintext, int maxLine)
            throws SigilException {
        List<String> lines = seal(circle, signet, plaintext, "", maxLine);
        if (lines.size() != 1) {
            throw new SigilException("Plaintext needs " + lines.size() + " S2S lines.");
        }
        return lines.get(0);
    }

    public static List<String> seal(SigilCircle circle, SigilEd25519 signet, String plaintext, String sender,
                                   int maxLine) throws SigilException {
        List<String> parts = splitWithTail(plaintext, sender, maxLine);
        byte[] mid = new byte[parts.size() > 1 ? SigilS2C.MID_LEN : 0];
        if (mid.length > 0) {
            RANDOM.nextBytes(mid);
        }
        List<byte[]> nonces = new ArrayList<byte[]>();
        for (int i = 0; i < parts.size(); i++) {
            byte[] n = new byte[SigilS2C.NONCE_LEN];
            RANDOM.nextBytes(n);
            nonces.add(n);
        }
        return sealWithRandom(circle, signet, sender, maxLine, parts, mid, nonces);
    }

    static List<String> sealWithRandom(SigilCircle circle, SigilEd25519 signet, String sender, int maxLine,
                                      List<String> parts, byte[] mid, List<byte[]> nonces) throws SigilException {
        int total = parts.size();
        byte[] sfield = SigilS2C.senderField(sender);
        byte[] ctx = context(circle.name());
        byte[] keyid = signet.keyid();
        List<byte[]> headers = new ArrayList<byte[]>();
        List<byte[]> pts = new ArrayList<byte[]>();
        for (int i = 1; i <= total; i++) {
            int flags = 0;
            byte[] body = parts.get(i - 1).getBytes(StandardCharsets.UTF_8);
            byte[] pt = body;
            if (i == 1 && sfield.length > 0) {
                flags |= SigilS2C.FLAG_S;
                pt = concat(sfield, body);
            }
            headers.add(SigilS2C.header(flags, i, total, mid));
            pts.add(pt);
        }
        byte[] trailer = concat(keyid, signet.sign(signedBytes(ctx, keyid, headers, pts)));
        List<String> lines = new ArrayList<String>(total);
        for (int i = 0; i < total; i++) {
            byte[] pt = i == total - 1 ? concat(pts.get(i), trailer) : pts.get(i);
            byte[] header = headers.get(i);
            byte[] ct = SigilS1C.gcm(Cipher.ENCRYPT_MODE, circle.key(), nonces.get(i), pt, concat(header, ctx));
            String line = VERSION + "." + circle.slug() + "." + SigilB64.encode(concat(concat(header, nonces.get(i)), ct));
            if (line.length() > maxLine) {
                throw new IllegalStateException("S2S line " + line.length() + " > max_line " + maxLine);
            }
            lines.add(line);
        }
        return lines;
    }

    static List<String> splitWithTail(String text, String sender, int maxLine) throws SigilException {
        byte[] sfield = SigilS2C.senderField(sender);
        int single = SigilS2C.payloadRoom(maxLine, false);
        if (sfield.length + text.getBytes(StandardCharsets.UTF_8).length + TRAILER <= single) {
            return Collections.singletonList(text);
        }
        List<String> parts = new ArrayList<String>(SigilS2C.split(text, sender, maxLine));
        int room = SigilS2C.payloadRoom(maxLine, true);
        if (TRAILER > room) {
            throw new SigilException("max_line is too small for an S2S signature part.");
        }
        int last = parts.get(parts.size() - 1).getBytes(StandardCharsets.UTF_8).length
                + (parts.size() == 1 ? sfield.length : 0);
        if (parts.size() == 1 || last + TRAILER > room) {
            parts.add("");
        }
        if (parts.size() > SigilS2C.MAX_PARTS) {
            throw new SigilException("S2S needs " + parts.size() + " parts.");
        }
        return parts;
    }

    public static final class Opened {
        public final SigilCircle circle;
        public final String text;
        public final String sender;
        public final byte[] keyid;
        public final int index;
        public final int total;
        public final byte[] header;
        public final byte[] plaintext;

        Opened(SigilCircle circle, SigilS2C.Frame fr, byte[] pt, String sender, String text, byte[] keyid) {
            this.circle = circle;
            this.text = text;
            this.sender = sender;
            this.keyid = keyid;
            this.index = fr.index;
            this.total = fr.total;
            this.header = fr.header;
            this.plaintext = pt;
        }
    }

    public static Opened open(String line, Collection<SigilCircle> keyring, Collection<SigilEd25519> verifiers)
            throws SigilException {
        String token = SigilCodec.extractToken(line);
        String[] fields = token.split("\\.", -1);
        if (fields.length < 3 || !VERSION.equals(fields[0])) {
            throw new SigilException("Not a SIGIL S2S message.");
        }
        SigilS2C.Frame fr = SigilS2C.parseFrame(SigilB64.decode(fields[fields.length - 1]), false);
        SigilException lastErr = new SigilException("Could not open signed circle message.");
        for (SigilCircle circle : keyring) {
            if (!circle.slug().equals(fields[1]) && !circle.name().toLowerCase(java.util.Locale.ROOT).equals(fields[1])) {
                continue;
            }
            byte[] ctx = context(circle.name());
            byte[] pt;
            try {
                pt = SigilS1C.gcm(Cipher.DECRYPT_MODE, circle.key(), fr.nonce, fr.ct, concat(fr.header, ctx));
            } catch (SigilException e) {
                lastErr = e;
                continue;
            }
            byte[] keyid = null;
            if (fr.index == fr.total) {
                if (pt.length < TRAILER) {
                    throw new SigilException("S2S last part is too short for its signature trailer.");
                }
                keyid = Arrays.copyOfRange(pt, pt.length - TRAILER, pt.length - SigilEd25519.SIG_LEN);
                byte[] sig = Arrays.copyOfRange(pt, pt.length - SigilEd25519.SIG_LEN, pt.length);
                pt = Arrays.copyOfRange(pt, 0, pt.length - TRAILER);
                if (verifiers != null) {
                    SigilEd25519 match = null;
                    for (SigilEd25519 v : verifiers) {
                        if (Arrays.equals(v.keyid(), keyid)) {
                            match = v;
                            break;
                        }
                    }
                    if (match == null) {
                        throw new SigilException("S2S signer is not a pinned key.");
                    }
                    if (fr.total == 1
                            && !match.verify(signedBytes(ctx, keyid,
                            Collections.singletonList(fr.header), Collections.singletonList(pt)), sig)) {
                        throw new SigilException("S2S signature does not verify.");
                    }
                }
            }
            String sender = null;
            byte[] body = pt;
            if ((fr.flags & SigilS2C.FLAG_S) != 0) {
                int n = pt.length > 0 ? (pt[0] & 0xFF) : 999;
                if (1 + n > pt.length) {
                    throw new SigilException("Bad S2 sender field.");
                }
                sender = SigilCodebookV2.strictUtf8(Arrays.copyOfRange(pt, 1, 1 + n));
                body = Arrays.copyOfRange(pt, 1 + n, pt.length);
            }
            String text = (fr.flags & SigilS2C.FLAG_Z) != 0
                    ? SigilCodebookV2.expand(body)
                    : SigilCodebookV2.strictUtf8(body);
            return new Opened(circle, fr, pt, sender, text, keyid);
        }
        throw lastErr;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
