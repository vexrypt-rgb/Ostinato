package baritone.swarm.crypto;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SigilSignetsTest {

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
