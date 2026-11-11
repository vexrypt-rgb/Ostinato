"""Create Ostinato S2S Ed25519 signets (requires Python cryptography).

This is a signing-key writer, not the P-256 ECDH encryption identity writer.
Existing P-256 keys cannot be converted: generate a new signing key and repin peers.
"""
import argparse
import base64
import json
import os
import re
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


def encode(raw):
    return base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")


def records(name, seed=None):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,16}", name):
        raise ValueError("name must be a swarm member id (1-16 letters, digits, _ or -)")
    key = Ed25519PrivateKey.generate() if seed is None else Ed25519PrivateKey.from_private_bytes(seed)
    public = key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    private = key.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw,
                                serialization.NoEncryption())
    pin = {"kind": "signet", "algorithm": "Ed25519", "name": name, "public": encode(public)}
    return dict(pin, seed=encode(private)), pin


def write_new(path, record):
    # Exclusive creation prevents accidental replacement of pinned identities.
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as out:
        json.dump(record, out, indent=2)
        out.write("\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("name")
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    private, pin = records(args.name)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    write_new(args.out_dir / ("signet-" + args.name + ".json"), private)
    write_new(args.out_dir / ("signet-" + args.name + "-public.json"), pin)


if __name__ == "__main__":
    main()
