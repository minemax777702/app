#!/usr/bin/env python3
"""Assert that reflectively loaded SSH implementations survived release shrinking."""
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REQUIRED = {
    "com.jcraft.jsch.DHEC256",
    "com.jcraft.jsch.UserAuthNone",
    "com.jcraft.jsch.UserAuthPublicKey",
    "com.jcraft.jsch.jce.ECDHN",
    "com.jcraft.jsch.jce.SHA256",
    "com.jcraft.jsch.jce.SignatureECDSA256",
    "com.jcraft.jsch.jce.HMACSHA256",
    "com.jcraft.jsch.jce.HMACSHA1",
    "com.jcraft.jsch.jce.SHA1",
    "com.jcraft.jsch.jce.KeyPairGenECDSA",
    "com.jcraft.jsch.jce.AES128CTR",
    "com.jcraft.jsch.jce.Random",
    "com.jcraft.jsch.jce.MD5",
}

def main():
    apk, dexdump = sys.argv[1:]
    found = set()
    with tempfile.TemporaryDirectory() as temp, zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if not re.fullmatch(r"classes[0-9]*\.dex", name):
                continue
            dex = Path(temp) / name
            dex.write_bytes(archive.read(name))
            result = subprocess.run([dexdump, str(dex)], check=True, capture_output=True, text=True)
            found.update(match.replace("/", ".") for match in re.findall(
                r"Class descriptor\s*:\s*'L([^']+);'", result.stdout))
    missing = sorted(REQUIRED - found)
    if missing:
        sys.exit("Missing SSH classes in release APK: " + ", ".join(missing))
    print("Release APK: all required SSH implementations present")

if __name__ == "__main__":
    main()
