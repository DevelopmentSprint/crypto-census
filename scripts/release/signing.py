#!/usr/bin/env python3
"""Import CI signing material without writing the armored key or passphrase to disk."""

import os
from pathlib import Path
import re
import subprocess
import sys


def run(args, data=None):
    result = subprocess.run(["gpg", "--batch", "--pinentry-mode", "loopback"] + args,
                            input=data, capture_output=True, check=False)
    if result.returncode:
        raise ValueError("Signing-key setup failed; GPG output withheld")
    return result.stdout.decode()


def main():
    os.umask(0o077)
    fingerprint = os.environ.get("GPG_FINGERPRINT", "")
    key = os.environ.get("GPG_PRIVATE_KEY", "")
    phrase = os.environ.get("MAVEN_GPG_PASSPHRASE", "")
    if not re.fullmatch(r"[0-9A-F]{40}", fingerprint) or not key or not phrase:
        raise ValueError("Configure GPG_FINGERPRINT, GPG_PRIVATE_KEY and GPG_PASSPHRASE in maven-central")
    home = Path(os.environ["GNUPGHOME"])
    home.mkdir(mode=0o700, parents=True, exist_ok=False)
    run(["--import"], key.encode())
    listing = run(["--with-colons", "--list-secret-keys"])
    fingerprints = [line.split(":")[9] for line in listing.splitlines() if line.startswith("fpr:")]
    if fingerprints != [fingerprint]:
        raise ValueError("Expected exactly the configured primary signing key, without additional keys")
    payload = home / "signing-check.txt"
    payload.write_text("Crypto Census signing-key check; not a release artifact.\n")
    run(["--passphrase-fd", "0", "--local-user", fingerprint + "!", "--armor",
         "--detach-sign", str(payload)], (phrase + "\n").encode())
    status = run(["--status-fd", "1", "--verify", str(payload) + ".asc", str(payload)])
    if not any(line.startswith(f"[GNUPG:] VALIDSIG {fingerprint} ") for line in status.splitlines()):
        raise ValueError("Signing check returned an unexpected signer")
    print("Configured signing identity verified: " + fingerprint)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print(str(error) if isinstance(error, ValueError) else "Signing-key setup failed", file=sys.stderr)
        raise SystemExit(1) from None
