#!/usr/bin/env python3
"""Decode ALog .alog files (ALGF / ALG1). Uses stdlib only (zlib inflate)."""
from __future__ import annotations

import json
import struct
import zlib

MAGIC_FILE = b"ALGF"
MAGIC_BLOCK = b"ALG1"
FLAG_COMPRESSED = 0x01
FIXED_HEADER = 22
FILE_HEADER_SIZE = 5


def decode_file(path: str) -> list[dict]:
    with open(path, "rb") as f:
        data = f.read()
    return decode_bytes(data)


def decode_bytes(data: bytes) -> list[dict]:
    if len(data) < 4 or data[:4] != MAGIC_FILE:
        return _decode_jsonl(data)
    if len(data) < FILE_HEADER_SIZE:
        return []
    pos = FILE_HEADER_SIZE
    rows: list[dict] = []
    for block in _scan_blocks(data, pos):
        payload = block["payload"]
        try:
            if block["flags"] & FLAG_COMPRESSED:
                payload = zlib.decompress(payload)
            text = payload.decode("utf-8", "replace")
            for line in text.splitlines():
                line = line.strip()
                if not line:
                    continue
                rows.append(_parse_line(line))
        except Exception as exc:
            rows.append({"type": "internal", "msg": "skip block seq=%s: %s" % (block["seq"], exc)})
    return rows


def _decode_jsonl(data: bytes) -> list[dict]:
    text = data.decode("utf-8", "replace")
    rows = []
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        rows.append(_parse_line(line))
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
        i = parsed["offset"] + FIXED_HEADER + len(parsed["payload"]) + 4
    return blocks


def _parse_block(data: bytes, offset: int) -> dict | None:
    if offset + FIXED_HEADER > len(data):
        return None
    version = data[offset + 4]
    flags = data[offset + 5]
    seq, unix_ms, payload_len = struct.unpack_from(">IQI", data, offset + 6)
    end = offset + FIXED_HEADER + payload_len + 4
    if payload_len < 0 or end > len(data):
        return None
    payload = data[offset + FIXED_HEADER : offset + FIXED_HEADER + payload_len]
    crc = struct.unpack_from(">I", data, end - 4)[0]
    crc_src = data[offset : offset + FIXED_HEADER + payload_len]
    if (zlib.crc32(crc_src) & 0xFFFFFFFF) != crc:
        return None
    return {
        "version": version,
        "flags": flags,
        "seq": seq,
        "unixMs": unix_ms,
        "payload": payload,
        "offset": offset,
    }
