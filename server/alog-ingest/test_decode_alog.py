#!/usr/bin/env python3
import json
import struct
import unittest
import zlib
from pathlib import Path

from decode_alog import decode_file, decode_bytes


def crc32_signed_bytes(data: bytes) -> bytes:
    return struct.pack(">I", zlib.crc32(data) & 0xFFFFFFFF)


def make_unencrypted_alog(lines: list[str]) -> bytes:
    body = ("\n".join(lines) + "\n").encode("utf-8")
    payload = zlib.compress(body)
    header = b"ALGF" + bytes([1])
    block = bytearray()
    block.extend(b"ALG1")
    block.append(1)
    block.append(0x01)  # compressed
    block.extend(struct.pack(">I", 0))
    block.extend(struct.pack(">Q", 1_724_000_000_000))
    block.extend(struct.pack(">I", len(payload)))
    block.extend(payload)
    block.extend(crc32_signed_bytes(bytes(block)))
    return header + bytes(block)


class DecodeAlogTest(unittest.TestCase):
    def test_decodes_unencrypted_json_lines(self):
        raw = make_unencrypted_alog(
            ['{"ts":1,"level":"INFO","type":"code","tag":"T","msg":"hello"}']
        )
        rows = decode_bytes(raw)
        self.assertEqual(1, len(rows))
        self.assertEqual("hello", rows[0]["msg"])
        self.assertEqual("INFO", rows[0]["level"])

    def test_decodes_plain_jsonl(self):
        raw = b'{"ts":1,"level":"I","type":"code","tag":"T","msg":"plain"}\n'
        rows = decode_bytes(raw)
        self.assertEqual("plain", rows[0]["msg"])

    def test_decode_file_reads_disk(self):
        path = Path(__file__).with_name("_tmp_decode.alog")
        try:
            path.write_bytes(make_unencrypted_alog(['{"msg":"from-file"}']))
            rows = decode_file(str(path))
            self.assertEqual("from-file", rows[0]["msg"])
        finally:
            if path.exists():
                path.unlink()


if __name__ == "__main__":
    unittest.main()
