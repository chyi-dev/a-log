#!/usr/bin/env python3
"""Decode Mars xlog (nocrypt) into detail rows for xlog-ingest."""
from __future__ import annotations

import os
import re
import struct
import traceback
import zlib
from datetime import datetime

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


def _decode_block(buf: bytes, offset: int, out: bytearray, lastseq: list) -> int:
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
        if magic in (
            MAGIC_NO_COMPRESS_START1,
            MAGIC_COMPRESS_START2,
            MAGIC_SYNC_ZSTD_START,
            MAGIC_ASYNC_ZSTD_START,
        ):
            out.extend(b"[F]encrypted/wrong script block skipped\n")
            return offset + header_len + length + 1
        if magic in (MAGIC_ASYNC_NO_CRYPT_ZSTD_START, MAGIC_SYNC_NO_CRYPT_ZSTD_START):
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


def decode_bytes(data: bytes) -> bytes:
    start = _start_pos(data, 2)
    if start < 0:
        start = _start_pos(data, 1)
    if start < 0:
        return b""
    out = bytearray()
    lastseq = [0]
    pos = start
    while True:
        pos = _decode_block(data, pos, out, lastseq)
        if pos < 0:
            break
    return bytes(out)


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
    del private_key_pem  # nocrypt sample only
    with open(path, "rb") as f:
        data = f.read()
    if not data:
        return []
    plain = decode_bytes(data)
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
