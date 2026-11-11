package baritone.swarm.accounts;

/**
 * Ostinato: encrypts account material before it touches disk.
 * Tests inject a stand-in. The client uses Windows DPAPI.
 */
public interface SecretProtector {

    byte[] protect(byte[] plain);

    byte[] unprotect(byte[] blob);
}
