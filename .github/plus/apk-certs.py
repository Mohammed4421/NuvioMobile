#!/usr/bin/env python3
"""Prints the SHA-256 digest of every signing certificate in an APK's APK Signing Block.

One line per scheme and signer: `<scheme> <sha256>` with scheme v2, v3 or v3.1, read straight from
the signed data, so it does not depend on apksigner's text output (which changes between versions).
This only reads the certificates; `apksigner verify` checks that the signatures are valid.
Exit status 1 when the APK has no APK Signing Block or no certificate.

Usage: apk-certs.py <apk>              all lines
       apk-certs.py --current <apk>    the one certificate signing the APK (fails if not exactly one)
"""
import hashlib
import struct
import sys

SCHEMES = {0x7109871A: "v2", 0xF05368C0: "v3", 0x1B93AD61: "v3.1"}


def u32(data, offset):
    return struct.unpack_from("<I", data, offset)[0]


def prefixed(data, offset):
    size = u32(data, offset)
    if offset + 4 + size > len(data):
        raise ValueError("length prefix runs past the data")
    return data[offset + 4:offset + 4 + size], offset + 4 + size


def certificates(path):
    with open(path, "rb") as file:
        data = file.read()
    eocd = data.rfind(b"PK\x05\x06")
    if eocd < 0:
        raise ValueError("not a ZIP file")
    central_directory = u32(data, eocd + 16)
    if data[central_directory - 16:central_directory] != b"APK Sig Block 42":
        raise ValueError("no APK Signing Block (v1-only or unsigned APK)")
    block_size = struct.unpack_from("<Q", data, central_directory - 24)[0]
    offset, end = central_directory - block_size - 8 + 8, central_directory - 24
    found = []
    while offset < end:
        pair_size = struct.unpack_from("<Q", data, offset)[0]
        pair_id = u32(data, offset + 8)
        value = data[offset + 12:offset + 8 + pair_size]
        offset += 8 + pair_size
        scheme = SCHEMES.get(pair_id)
        if scheme is None:
            continue
        signers, _ = prefixed(value, 0)
        position = 0
        while position < len(signers):
            signer, position = prefixed(signers, position)
            signed_data, _ = prefixed(signer, 0)
            _digests, after = prefixed(signed_data, 0)
            certs, _ = prefixed(signed_data, after)
            # The first certificate of a signer is the signing certificate.
            first, _ = prefixed(certs, 0)
            found.append((scheme, hashlib.sha256(first).hexdigest()))
    return found


def main(argv):
    current_only = "--current" in argv
    paths = [a for a in argv[1:] if a != "--current"]
    if len(paths) != 1:
        print(__doc__, file=sys.stderr)
        return 2
    try:
        found = certificates(paths[0])
    except (OSError, ValueError, struct.error) as error:
        print(f"{paths[0]}: {error}", file=sys.stderr)
        return 1
    if not found:
        print(f"{paths[0]}: no signing certificate", file=sys.stderr)
        return 1
    if current_only:
        digests = sorted({digest for _, digest in found})
        if len(digests) != 1:
            print(f"{paths[0]}: {len(digests)} different signing certificates: {found}", file=sys.stderr)
            return 1
        print(digests[0])
    else:
        for scheme, digest in found:
            print(scheme, digest)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
