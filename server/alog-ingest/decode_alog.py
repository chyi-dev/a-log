#!/usr/bin/env python3
"""Decode ALog .alog files (ALGF / ALG1). Unencrypted uses stdlib; encrypted needs cryptography or gradle.bat."""
from __future__ import annotations

import json
import os
import struct
import subprocess
import zlib

MAGIC_FILE = b"ALGF"
MAGIC_BLOCK = b"ALG1"
FLAG_HAS_DEK = 0x01
FLAG_COMPRESSED = 0x01
FLAG_ENCRYPTED = 0x02
GCM_NONCE_BYTES = 12
FIXED_HEADER = 22

_REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
_DEFAULT_KEY = os.path.join(_REPO, "docs", "keys", "alog_private.pem")
_GRADLE = os.path.join(
    os.path.expanduser("~"),
    ".gradle",
    "wrapper",
    "dists",
    "gradle-8.7-all",
    "aan3ydargesu18aqyqjwhr3pc",
    "gradle-8.7",
    "bin",
    "gradle.bat" if os.name == "nt" else "gradle",
)


def decode_file(path: str, private_key_pem: str | None = None) -> list[dict]:
    with open(path, "rb") as f:
        data = f.read()
    try:
        return decode_bytes(data, private_key_pem=private_key_pem)
    except EncryptedWithoutCrypto:
        return _decode_via_gradle(path, private_key_pem)


def decode_bytes(data: bytes, private_key_pem: str | None = None) -> list[dict]:
    if len(data) < 8 or data[:4] != MAGIC_FILE:
        raise ValueError("not an ALGF file")
    flags = data[5]
    key_len = struct.unpack_from(">H", data, 6)[0]
    pos = 8
    key_id = data[pos : pos + key_len].decode("utf-8", "replace")
    pos += key_len
    dek_len = struct.unpack_from(">H", data, pos)[0]
    pos += 2
    wrapped_dek = data[pos : pos + dek_len]
    pos += dek_len
    dek = None
    if flags & FLAG_HAS_DEK:
        dek = _unwrap_dek(wrapped_dek, private_key_pem, key_id)
    rows: list[dict] = []
    for block in _scan_blocks(data, pos):
        payload = block["payload"]
        try:
            if block["flags"] & FLAG_ENCRYPTED:
                if dek is None:
                    raise EncryptedWithoutCrypto("block encrypted but no DEK")
                payload = _aes_gcm_decrypt(dek, block["nonce"], payload)
            if block["flags"] & FLAG_COMPRESSED:
                payload = zlib.decompress(payload)
            text = payload.decode("utf-8", "replace")
            for line in text.splitlines():
                line = line.strip()
                if not line:
                    continue
                rows.append(_parse_line(line))
        except EncryptedWithoutCrypto:
            raise
        except Exception as exc:
            rows.append({"type": "internal", "msg": "skip block seq=%s: %s" % (block["seq"], exc)})
    return rows


def _parse_line(line: str) -> dict:
    if line.startswith("{"):
        try:
            obj = json.loads(line)
            if isinstance(obj, dict):
                return obj
        except json.JSONDecodeError:
            pass
    return {"type": "code", "msg": line}


def _scan_blocks(data: bytes, start: int) -> list[dict]:
    blocks = []
    i = start
    n = len(data)
    while i <= n - 4:
        if data[i : i + 4] != MAGIC_BLOCK:
            i += 1
            continue
        parsed = _parse_block(data, i)
        if parsed is None:
            i += 1
            continue
        blocks.append(parsed)
        i = parsed["offset"] + FIXED_HEADER + len(parsed["nonce"]) + len(parsed["payload"]) + 4
    return blocks


def _parse_block(data: bytes, offset: int) -> dict | None:
    if offset + FIXED_HEADER > len(data):
        return None
    version = data[offset + 4]
    flags = data[offset + 5]
    seq, unix_ms, payload_len = struct.unpack_from(">IQI", data, offset + 6)
    nonce_len = GCM_NONCE_BYTES if flags & FLAG_ENCRYPTED else 0
    end = offset + FIXED_HEADER + nonce_len + payload_len + 4
    if payload_len < 0 or end > len(data):
        return None
    nonce = data[offset + FIXED_HEADER : offset + FIXED_HEADER + nonce_len]
    payload = data[offset + FIXED_HEADER + nonce_len : offset + FIXED_HEADER + nonce_len + payload_len]
    crc = struct.unpack_from(">I", data, end - 4)[0]
    crc_src = data[offset : offset + FIXED_HEADER + nonce_len + payload_len]
    if (zlib.crc32(crc_src) & 0xFFFFFFFF) != crc:
        return None
    return {
        "version": version,
        "flags": flags,
        "seq": seq,
        "unixMs": unix_ms,
        "nonce": nonce,
        "payload": payload,
        "offset": offset,
    }


class EncryptedWithoutCrypto(RuntimeError):
    pass


def _load_private_pem(private_key_pem: str | None) -> str:
    if private_key_pem:
        if private_key_pem.strip().startswith("-----"):
            return private_key_pem
        with open(private_key_pem, encoding="utf-8") as f:
            return f.read()
    if os.path.isfile(_DEFAULT_KEY):
        with open(_DEFAULT_KEY, encoding="utf-8") as f:
            return f.read()
    raise EncryptedWithoutCrypto("encrypted file requires private key")


def _unwrap_dek(wrapped: bytes, private_key_pem: str | None, key_id: str) -> bytes:
    pem = _load_private_pem(private_key_pem)
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import padding
        from cryptography.hazmat.backends import default_backend

        key = serialization.load_pem_private_key(pem.encode("utf-8"), password=None, backend=default_backend())
        return key.decrypt(wrapped, padding.PKCS1v15())
    except ImportError:
        raise EncryptedWithoutCrypto("cryptography not installed, keyId=%s" % key_id)


def _aes_gcm_decrypt(dek: bytes, nonce: bytes, cipher: bytes) -> bytes:
    try:
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM

        return AESGCM(dek).decrypt(nonce, cipher, None)
    except ImportError:
        raise EncryptedWithoutCrypto("cryptography not installed")


def _decode_via_gradle(path: str, private_key_pem: str | None) -> list[dict]:
    key = private_key_pem if private_key_pem and os.path.isfile(str(private_key_pem)) else _DEFAULT_KEY
    gradle = os.environ.get("ALOG_GRADLE", _GRADLE)
    if os.name == "nt" and not gradle.lower().endswith(".bat"):
        gradle = gradle + ".bat"
    args = "--key %s %s" % (key, path) if os.path.isfile(key) else path
    cmd = [gradle, ":alog-decode:run", "--quiet", "--args=%s" % args]
    proc = subprocess.run(cmd, cwd=_REPO, capture_output=True, text=True, timeout=180)
    rows = []
    for line in (proc.stdout or "").splitlines():
        line = line.strip()
        if not line.startswith("{"):
            continue
        rows.append(_parse_line(line))
    if not rows:
        err = (proc.stderr or proc.stdout or "decode failed").strip()
        return [{"type": "internal", "msg": "decode failed: %s" % err[:500]}]
    return rows
