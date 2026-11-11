package baritone.swarm.crypto;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SigilSignetsTest {

    @Test public void pythonWriterFixtureSignsRfc8032() throws Exception { // Ostinato:
        String json; // Ostinato:
        try (java.io.InputStream in = getClass().getResourceAsStream("ed25519-signet-test-only.json")) { // Ostinato:
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); // Ostinato:
        } // Ostinato:
        SigilSignets.Record record = SigilSignets.parse(json); // Ostinato:
        byte[] signature = record.key.sign(new byte[0]); // Ostinato:
        assertEquals("e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" // Ostinato:
                + "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b", // Ostinato:
                java.util.HexFormat.of().formatHex(signature)); // Ostinato:
        assertTrue(SigilEd25519.fromPublic(record.key.publicKey()).verify(new byte[0], signature)); // Ostinato:
    } // Ostinato:

    @Test public void explicitP256SignetIsRejectedEvenWith32ByteMaterial() throws Exception { // Ostinato:
        try { // Ostinato:
            SigilSignets.parse("{\"kind\":\"signet\",\"name\":\"Bob\",\"algorithm\":\"P-256\",\"public\":\"" // Ostinato:
                    + SigilB64.encode(new byte[32]) + "\"}"); // Ostinato:
            fail("P-256 must not be interpreted as Ed25519"); // Ostinato:
        } catch (SigilException expected) { // Ostinato:
            assertTrue(expected.getMessage().contains("Ed25519")); // Ostinato:
        } // Ostinato:
    } // Ostinato:

    @Test
    public void publicOnly() throws Exception {
        SigilEd25519 k = SigilEd25519.testSignet("botA-DO-NOT-USE");
        String json = "{\"kind\":\"signet\",\"name\":\"botA\",\"public\":\""
                + SigilB64.encode(k.publicKey()) + "\"}";
        SigilSignets.Record r = SigilSignets.parse(json);
        assertEquals("botA", r.name);
        assertArrayEquals(k.publicKey(), r.key.publicKey());
        try {
            r.key.sign(new byte[] {1});
            fail("public-only must not sign");
        } catch (SigilException expected) {
        }
    }

    @Test
    public void seedMustMatchPublic() throws Exception {
        SigilEd25519 a = SigilEd25519.testSignet("botA-DO-NOT-USE");
        SigilEd25519 b = SigilEd25519.testSignet("botB-DO-NOT-USE");
        String json = "{\"kind\":\"signet\",\"name\":\"x\",\"seed\":\""
                + SigilB64.encode(a.publicKey()) + "\",\"public\":\""
                + SigilB64.encode(b.publicKey()) + "\"}";
        try {
            SigilSignets.parse(json);
            fail();
        } catch (SigilException expected) {
        }
    }

    @Test
    public void theSeedSurvivesItsOwnPublicPin() throws Exception {
        SigilEd25519 a = SigilEd25519.testSignet("botA-DO-NOT-USE");
        SigilSignets.Record seed = new SigilSignets.Record("botA", a);
        SigilSignets.Record pin = new SigilSignets.Record("botA", SigilEd25519.fromPublic(a.publicKey()));
        assertTrue(SigilSignets.toMap(Arrays.asList(seed, pin)).get("botA").canSign());
        assertTrue(SigilSignets.toMap(Arrays.asList(pin, seed)).get("botA").canSign());
    }

    @Test
    public void twoKeysForOneNameAreRefused() throws Exception {
        SigilSignets.Record a = new SigilSignets.Record("botA", SigilEd25519.testSignet("botA-DO-NOT-USE"));
        SigilSignets.Record other = new SigilSignets.Record("botA", SigilEd25519.testSignet("botB-DO-NOT-USE"));
        try {
            SigilSignets.toMap(Arrays.asList(a, other));
            fail();
        } catch (SigilException expected) {
        }
    }
}
