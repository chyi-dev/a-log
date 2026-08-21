#!/usr/bin/env python3
"""Decode Mars xlog (nocrypt + ECDH/TEA crypt) into detail rows for xlog-ingest."""
from __future__ import annotations

import binascii
import os
import re
import struct
import traceback
import zlib
from datetime import datetime
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.ec import EllipticCurvePublicNumbers

MAGIC_NO_COMPRESS_START = 0x03
MAGIC_NO_COMPRESS_START1 = 0x06
MAGIC_NO_COMPRESS_NO_CRYPT_START = 0x08
MAGIC_COMPRESS_START = 0x04
MAGIC_COMPRESS_START1 = 0x05
MAGIC_COMPRESS_START2 = 0x07
MAGIC_COMPRESS_NO_CRYPT_START = 0x09
MAGIC_SYNC_ZSTD_START = 0x0A
MAGIC_SYNC_NO_CRYPT_ZSTD_START = 0x0B
MAGIC_ASYNC_ZSTD_START = 0x0C
MAGIC_ASYNC_NO_CRYPT_ZSTD_START = 0x0D
MAGIC_END = 0x00

LINE_RE = re.compile(
    r"^\s*\[?(?P<ts>\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?)\]?\s*"
    r"(?:\[(?P<level>[VDIWEF])\]\s*)?"
    r"(?:\[(?P<tag>[^\]]+)\]\s*)?"
    r"(?P<msg>.*)$"
)

LEVEL_MAP = {
    "V": "VERBOSE",
    "D": "DEBUG",
    "I": "INFO",
    "W": "WARN",
    "E": "ERROR",
    "F": "FATAL",
}

_REPO_ROOT = Path(__file__).resolve().parents[2]
_DEFAULT_PRIV = _REPO_ROOT / "docs" / "keys" / "xlog_private.hex"


def _crypt_key_len(magic: int):
    if magic in (MAGIC_NO_COMPRESS_START, MAGIC_COMPRESS_START, MAGIC_COMPRESS_START1):
        return 4
    if magic in (
        MAGIC_COMPRESS_START2,
        MAGIC_NO_COMPRESS_START1,
        MAGIC_NO_COMPRESS_NO_CRYPT_START,
        MAGIC_COMPRESS_NO_CRYPT_START,
        MAGIC_SYNC_ZSTD_START,
        MAGIC_SYNC_NO_CRYPT_ZSTD_START,
        MAGIC_ASYNC_ZSTD_START,
        MAGIC_ASYNC_NO_CRYPT_ZSTD_START,
    ):
        return 64
    return None


def tea_decipher(block8: bytes, key16: bytes) -> bytes:
    op = 0xFFFFFFFF
    v0, v1 = struct.unpack("<II", block8[0:8])
    k1, k2, k3, k4 = struct.unpack("<IIII", key16[0:16])
    delta = 0x9E3779B9
    s = (delta << 4) & op
    for _ in range(16):
        v1 = (v1 - (((v0 << 4) + k3) ^ (v0 + s) ^ ((v0 >> 5) + k4))) & op
        v0 = (v0 - (((v1 << 4) + k1) ^ (v1 + s) ^ ((v1 >> 5) + k2))) & op
        s = (s - delta) & op
    return struct.pack("<II", v0, v1)


def tea_encipher(block8: bytes, key16: bytes) -> bytes:
    op = 0xFFFFFFFF
    v0, v1 = struct.unpack("<II", block8[0:8])
    k1, k2, k3, k4 = struct.unpack("<IIII", key16[0:16])
    delta = 0x9E3779B9
    s = 0
    for _ in range(16):
        s = (s + delta) & op
        v0 = (v0 + (((v1 << 4) + k1) ^ (v1 + s) ^ ((v1 >> 5) + k2))) & op
        v1 = (v1 + (((v0 << 4) + k3) ^ (v0 + s) ^ ((v0 >> 5) + k4))) & op
    return struct.pack("<II", v0, v1)


def tea_decrypt(data: bytes, key16: bytes) -> bytes:
    num = len(data) // 8 * 8
    out = bytearray()
    for i in range(0, num, 8):
        out.extend(tea_decipher(data[i : i + 8], key16))
    out.extend(data[num:])
    return bytes(out)


def tea_encrypt(data: bytes, key16: bytes) -> bytes:
    num = len(data) // 8 * 8
    out = bytearray()
    for i in range(0, num, 8):
        out.extend(tea_encipher(data[i : i + 8], key16))
    out.extend(data[num:])
    return bytes(out)


def load_private_key_hex(explicit: str | None = None) -> str | None:
    env = os.environ.get("XLOG_PRIVATE_KEY")
    raw = explicit if explicit is not None else env
    if raw:
        candidate = Path(raw)
        if candidate.is_file():
            return candidate.read_text(encoding="utf-8").strip()
        return raw.strip()
    if _DEFAULT_PRIV.is_file():
        return _DEFAULT_PRIV.read_text(encoding="utf-8").strip()
    return None


def ecdh_tea_key(priv_hex: str, client_pub_xy: bytes) -> bytes:
    if len(client_pub_xy) != 64:
        raise ValueError("client pubkey must be 64 bytes")
    priv_int = int(priv_hex, 16)
    private_key = ec.derive_private_key(priv_int, ec.SECP256K1())
    x = int.from_bytes(client_pub_xy[:32], "big")
    y = int.from_bytes(client_pub_xy[32:], "big")
    public_key = EllipticCurvePublicNumbers(x, y, ec.SECP256K1()).public_key()
    shared = private_key.exchange(ec.ECDH(), public_key)
    if len(shared) < 16:
        raise ValueError("ECDH shared secret too short")
    return shared[:16]


def _is_good(buf: bytes, offset: int, count: int):
    if offset == len(buf):
        return True, ""
    magic = buf[offset]
    key_len = _crypt_key_len(magic)
    if key_len is None:
        return False, "bad magic at %d" % offset
    header_len = 1 + 2 + 1 + 1 + 4 + key_len
    if offset + header_len + 1 + 1 > len(buf):
        return False, "short header"
    length = struct.unpack_from("<I", buf, offset + header_len - 4 - key_len)[0]
    end = offset + header_len + length
    if end + 1 > len(buf):
        return False, "short body"
    if buf[end] != MAGIC_END:
        return False, "bad end magic"
    if count <= 1:
        return True, ""
    return _is_good(buf, end + 1, count - 1)


def _start_pos(buf: bytes, count: int) -> int:
    for offset in range(len(buf)):
        if _crypt_key_len(buf[offset]) is not None and _is_good(buf, offset, count)[0]:
            return offset
    return -1


def _decode_block(
    buf: bytes,
    offset: int,
    out: bytearray,
    lastseq: list,
    priv_hex: str | None,
) -> int:
    if offset >= len(buf):
        return -1
    ok, reason = _is_good(buf, offset, 1)
    if not ok:
        fix = _start_pos(buf[offset:], 1)
        if fix < 0:
            return -1
        out.extend(("[F]decode skip %d: %s\n" % (fix, reason)).encode("utf-8", "replace"))
        offset += fix
    magic = buf[offset]
    key_len = _crypt_key_len(magic)
    if key_len is None:
        return -1
    header_len = 1 + 2 + 1 + 1 + 4 + key_len
    length = struct.unpack_from("<I", buf, offset + header_len - 4 - key_len)[0]
    seq = struct.unpack_from("<H", buf, offset + header_len - 4 - key_len - 2 - 2)[0]
    raw = bytes(buf[offset + header_len : offset + header_len + length])
    if seq not in (0, 1) and lastseq[0] != 0 and seq != lastseq[0] + 1:
        out.extend(("[F]seq missing %d-%d\n" % (lastseq[0] + 1, seq - 1)).encode())
    if seq != 0:
        lastseq[0] = seq
    try:
        if magic in (MAGIC_COMPRESS_START2, MAGIC_ASYNC_ZSTD_START):
            if not priv_hex:
                out.extend(b"[F]encrypted block but private key missing\n")
                return offset + header_len + length + 1
            client_pub = bytes(
                buf[offset + header_len - key_len : offset + header_len]
            )
            tea_key = ecdh_tea_key(priv_hex, client_pub)
            raw = tea_decrypt(raw, tea_key)
            if magic == MAGIC_COMPRESS_START2:
                raw = zlib.decompressobj(-zlib.MAX_WBITS).decompress(raw)
            else:
                import zstandard as zstd  # type: ignore

                raw = zstd.ZstdDecompressor().decompress(raw, max_output_size=64 * 1024 * 1024)
        elif magic in (MAGIC_NO_COMPRESS_START1, MAGIC_SYNC_ZSTD_START):
            # Official script leaves body as-is for these sync/encrypted-header variants.
            pass
        elif magic in (MAGIC_ASYNC_NO_CRYPT_ZSTD_START, MAGIC_SYNC_NO_CRYPT_ZSTD_START):
            try:
                import zstandard as zstd  # type: ignore

                raw = zstd.ZstdDecompressor().decompress(raw, max_output_size=64 * 1024 * 1024)
            except Exception as exc:
                out.extend(("[F]zstd err %s\n" % exc).encode())
                return offset + header_len + length + 1
        elif magic in (MAGIC_COMPRESS_START, MAGIC_COMPRESS_NO_CRYPT_START):
            raw = zlib.decompressobj(-zlib.MAX_WBITS).decompress(raw)
        elif magic == MAGIC_COMPRESS_START1:
            pieces = bytearray()
            tmp = raw
            while len(tmp) > 0:
                single = struct.unpack_from("<H", tmp, 0)[0]
                pieces.extend(tmp[2 : single + 2])
                tmp = tmp[single + 2 :]
            raw = zlib.decompressobj(-zlib.MAX_WBITS).decompress(bytes(pieces))
        out.extend(raw)
    except Exception as exc:
        out.extend(("[F]decompress %s\n" % exc).encode())
        traceback.print_exc()
    return offset + header_len + length + 1


def decode_bytes(data: bytes, private_key_hex: str | None = None) -> bytes:
    if private_key_hex == "":
        priv = None
    elif private_key_hex is not None:
        priv = load_private_key_hex(private_key_hex)
    else:
        priv = load_private_key_hex()
    start = _start_pos(data, 2)
    if start < 0:
        start = _start_pos(data, 1)
    if start < 0:
        return b""
    out = bytearray()
    lastseq = [0]
    pos = start
    while True:
        pos = _decode_block(data, pos, out, lastseq, priv)
        if pos < 0:
            break
    return bytes(out)


def build_crypt_zlib_block(
    plaintext: bytes,
    server_priv_hex: str,
    seq: int = 1,
    begin_hour: int = 0,
    end_hour: int = 23,
) -> bytes:
    """Build one MAGIC_COMPRESS_START2 block (for tests)."""
    client = ec.generate_private_key(ec.SECP256K1())
    nums = client.public_key().public_numbers()
    client_xy = nums.x.to_bytes(32, "big") + nums.y.to_bytes(32, "big")
    tea_key = ecdh_tea_key(server_priv_hex, client_xy)
    co = zlib.compressobj(level=zlib.Z_DEFAULT_COMPRESSION, wbits=-zlib.MAX_WBITS)
    compressed = co.compress(plaintext) + co.flush()
    encrypted = tea_encrypt(compressed, tea_key)
    header = bytearray()
    header.append(MAGIC_COMPRESS_START2)
    header.extend(struct.pack("<H", seq))
    header.append(begin_hour & 0xFF)
    header.append(end_hour & 0xFF)
    header.extend(struct.pack("<I", len(encrypted)))
    header.extend(client_xy)
    return bytes(header) + encrypted + bytes([MAGIC_END])


def _parse_ts(stamp: str) -> int:
    if "." in stamp:
        base, frac = stamp.split(".", 1)
        frac = (frac + "000")[:3]
        dt = datetime.strptime(base + "." + frac, "%Y-%m-%d %H:%M:%S.%f")
    else:
        dt = datetime.strptime(stamp, "%Y-%m-%d %H:%M:%S")
    return int(dt.timestamp() * 1000)


def lines_to_details(text: str, filename: str = "") -> list[dict]:
    rows: list[dict] = []
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if not line:
            continue
        m = LINE_RE.match(line)
        if not m:
            rows.append(
                {
                    "ts": 0,
                    "level": "?",
                    "type": "code",
                    "tag": "xlog",
                    "msg": line,
                    "file": filename,
                }
            )
            continue
        g = m.groupdict()
        level = LEVEL_MAP.get((g.get("level") or "I").upper()[:1], g.get("level") or "INFO")
        try:
            ts = _parse_ts(g["ts"]) if g.get("ts") else 0
        except Exception:
            ts = 0
        rows.append(
            {
                "ts": ts,
                "level": level,
                "type": "code",
                "tag": (g.get("tag") or "xlog").strip() or "xlog",
                "msg": (g.get("msg") or "").strip(),
                "file": filename,
            }
        )
    return rows


def decode_file(path: str, private_key_pem: str | None = None) -> list[dict]:
    # private_key_pem kept for call-site compatibility with alog-style hooks;
    # for xlog it is private key hex or a path (also via XLOG_PRIVATE_KEY).
    with open(path, "rb") as f:
        data = f.read()
    if not data:
        return []
    plain = decode_bytes(data, private_key_hex=private_key_pem)
    name = os.path.basename(path)
    if not plain:
        return [
            {
                "ts": 0,
                "level": "?",
                "type": "internal",
                "tag": "decode",
                "msg": "no xlog blocks in %s" % name,
                "file": name,
            }
        ]
    text = plain.decode("utf-8", "replace")
    return lines_to_details(text, name)
