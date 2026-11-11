/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package baritone.swarm;

import baritone.swarm.crypto.SigilCircle;
import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.crypto.SigilException;
import baritone.swarm.crypto.SigilKeyring;
import baritone.swarm.crypto.SigilSignets;
import baritone.swarm.roster.SwarmRoster;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Resolves roster groups to circles and optional Ed25519 signets from $SIGIL_HOME. */
public final class SwarmKeys {

    private SwarmKeys() {}

    public static Path sigilHome(String setting, String envSigilHome) {
        if (setting != null && !setting.trim().isEmpty()) {
            return Paths.get(setting.trim());
        }
        if (envSigilHome != null && !envSigilHome.trim().isEmpty()) {
            return Paths.get(envSigilHome.trim());
        }
        return null;
    }

    public static Map<String, SigilCircle> circlesFor(SwarmRoster roster, String self, Path home)
            throws SigilException {
        if (home == null) {
            throw new SigilException("No sigil home: set swarmSigilHome or SIGIL_HOME.");
        }
        if (!Files.isDirectory(home)) {
            throw new SigilException("Sigil home " + home + " is not a directory.");
        }
        List<SwarmRoster.Group> mine = roster.groupsOf(self);
        if (mine.isEmpty()) {
            throw new SigilException("You (" + self + ") are not a member of any roster group.");
        }
        Set<String> wanted = new HashSet<String>();
        for (SwarmRoster.Group g : mine) {
            wanted.add(g.circle());
        }
        Map<String, SigilCircle> byName = new HashMap<String, SigilCircle>();
        for (SigilCircle c : SigilKeyring.loadCircles(home, wanted::contains)) {
            if (byName.put(c.name(), c) != null) {
                throw new SigilException("Keyring has two records for circle '" + c.name() + "'.");
            }
        }
        Map<String, SigilCircle> out = new LinkedHashMap<String, SigilCircle>();
        for (SwarmRoster.Group g : mine) {
            SigilCircle c = byName.get(g.circle());
            if (c == null) {
                throw new SigilException("Circle '" + g.circle() + "' for group '" + g.id() + "' is not in the keyring.");
            }
            out.put(g.id(), c);
        }
        return out;
    }

    /** All signets in the home (public pins; seed present only on the local member file). */
    public static Map<String, SigilEd25519> signets(Path home) throws SigilException {
        if (home == null || !Files.isDirectory(home)) {
            return new LinkedHashMap<String, SigilEd25519>();
        }
        // Ostinato: spawned members share public pins, but load their seed from a private external file.
        Map<String, SigilEd25519> keys = new LinkedHashMap<String, SigilEd25519>(SigilSignets.loadMap(home));
        String local = System.getProperty("ostinato.swarm.spawnSignet", "");
        if (!local.isBlank()) {
            try {
                SigilSignets.Record record = SigilSignets.parse(Files.readString(Paths.get(local)));
                SigilEd25519 pinned = keys.get(record.name);
                if (pinned == null || !record.key.canSign()
                        || !java.util.Arrays.equals(pinned.publicKey(), record.key.publicKey())) {
                    throw new SigilException("Spawn signet does not match its shared public pin");
                }
                keys.put(record.name, record.key);
            } catch (java.io.IOException e) {
                throw new SigilException("Cannot read private spawn signet", e);
            }
        }
        return keys;
    }

    /**
     * Pin every signet in the home and use {@code self}'s seed (if present) to sign outbound S2S.
     * No-op when the home has no {@code signet-*.json}.
     */
    public static void bindSigning(SwarmEndpoint endpoint, String self, Path home) throws SigilException {
        Map<String, SigilEd25519> all = signets(home);
        if (all.isEmpty()) {
            return;
        }
        endpoint.setSigning(all.get(self), all);
    }
}
