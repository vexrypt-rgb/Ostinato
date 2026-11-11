import base64
import json
import tempfile
import unittest
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from sigil_signets import records, write_new

# RFC 8032 test 1: public test-only material, never a real identity.
SEED = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
PUBLIC = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")

class SignetsTest(unittest.TestCase):
    def test_rfc8032_record_matches_java_fixture(self):
        private, pin = records("TestOnly", SEED)
        fixture = Path(__file__).parents[1] / "src/test/resources/baritone/swarm/crypto/ed25519-signet-test-only.json"
        self.assertEqual(json.loads(fixture.read_text()), private)
        self.assertNotIn("seed", pin)
        self.assertEqual(PUBLIC, base64.urlsafe_b64decode(pin["public"] + "="))

    def test_random_keys_and_exclusive_write(self):
        private, pin = records("Bob")
        self.assertNotEqual(private["seed"], records("Bob")[0]["seed"])
        self.assertEqual(32, len(base64.urlsafe_b64decode(private["seed"] + "=")))
        Ed25519PublicKey.from_public_bytes(base64.urlsafe_b64decode(pin["public"] + "="))
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "signet-Bob.json"
            write_new(path, private)
            with self.assertRaises(FileExistsError):
                write_new(path, pin)
            self.assertEqual(private, json.loads(path.read_text()))

if __name__ == "__main__":
    unittest.main()
