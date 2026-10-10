/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.swarm.crypto;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code signet-*.json} from a sigil home. Public keys are pins.
 * A record may include a 32-byte seed (local member only). Never commit seeds.
 */
public final class SigilSignets {

    private SigilSignets() {}

    public static final class Record {
        public final String name;
        public final SigilEd25519 key;

        Record(String name, SigilEd25519 key) {
            this.name = name;
            this.key = key;
        }
    }

    public static Record parse(String json) throws SigilException {
        JsonObject o;
        try {
            JsonElement e = new JsonParser().parse(json);
            if (!e.isJsonObject()) {
                throw new SigilException("Signet record is not a JSON object.");
            }
            o = e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new SigilException("Signet record is not valid JSON.");
        }
        String kind = str(o, "kind");
        if (kind != null && !"signet".equals(kind)) {
            throw new SigilException("Keyring record is not a signet (kind=" + kind + ").");
        }
        String name = str(o, "name");
        if (name == null || name.trim().isEmpty()) {
            throw new SigilException("Signet record missing name.");
        }
        String seedB64 = str(o, "seed");
        String pubB64 = str(o, "public");
        SigilEd25519 key;
        if (seedB64 != null && !seedB64.isEmpty()) {
            key = SigilEd25519.fromSeed(SigilB64.decode(seedB64));
            if (pubB64 != null && !java.util.Arrays.equals(key.publicKey(), SigilB64.decode(pubB64))) {
                throw new SigilException("Signet '" + name + "' seed does not match public.");
            }
        } else if (pubB64 != null && !pubB64.isEmpty()) {
            key = SigilEd25519.fromPublic(SigilB64.decode(pubB64));
        } else {
            throw new SigilException("Signet '" + name + "' has neither seed nor public.");
        }
        return new Record(name.trim(), key);
    }

    public static List<Record> load(Path sigilHome) throws SigilException {
        List<Path> files = new ArrayList<Path>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(sigilHome, "signet-*.json")) {
            for (Path p : ds) {
                files.add(p);
            }
        } catch (IOException e) {
            throw new SigilException("Cannot list signet directory.", e);
        }
        files.sort(null);
        List<Record> out = new ArrayList<Record>();
        for (Path p : files) {
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                out.add(parse(json));
            } catch (IOException e) {
                throw new SigilException("Cannot read signet " + p.getFileName() + ".", e);
            }
        }
        return out;
    }

    /**
     * name -> key. A member may have two records for one key (the public pin beside the local seed); the one
     * that can sign is kept. Two records naming one member with different keys are refused: which is pinned
     * must not depend on file order.
     */
    public static Map<String, SigilEd25519> loadMap(Path sigilHome) throws SigilException {
        return toMap(load(sigilHome));
    }

    static Map<String, SigilEd25519> toMap(List<Record> records) throws SigilException {
        Map<String, SigilEd25519> out = new LinkedHashMap<String, SigilEd25519>();
        for (Record r : records) {
            SigilEd25519 had = out.get(r.name);
            if (had != null && !java.util.Arrays.equals(had.publicKey(), r.key.publicKey())) {
                throw new SigilException("Two signets for '" + r.name + "' with different keys.");
            }
            if (had == null || !had.canSign()) {
                out.put(r.name, r.key);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
