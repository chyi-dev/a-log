#!/usr/bin/env python3
import os
import tempfile
import unittest
from pathlib import Path

from decode_xlog import (
    build_crypt_zlib_block,
    decode_bytes,
    decode_file,
    lines_to_details,
    load_private_key_hex,
    tea_decrypt,
    tea_encrypt,
)


class DecodeXlogTest(unittest.TestCase):
    def test_lines_to_details(self):
        text = "2026-08-21 08:00:00.123 [I][Http] GET /ping 200\nbad line\n"
        rows = lines_to_details(text, "a.xlog")
        self.assertEqual(2, len(rows))
        self.assertEqual("Http", rows[0]["tag"])
        self.assertIn("GET /ping", rows[0]["msg"])
        self.assertEqual("INFO", rows[0]["level"])
        self.assertEqual("bad line", rows[1]["msg"])

    def test_empty_bytes(self):
        self.assertEqual(b"", decode_bytes(b""))
        self.assertEqual(b"", decode_bytes(b"\x00\x01\x02"))

    def test_decode_file_no_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "a.xlog")
            with open(path, "wb") as f:
                f.write(b"not-xlog")
            rows = decode_file(path)
            self.assertEqual(1, len(rows))
            self.assertEqual("internal", rows[0]["type"])

    def test_tea_roundtrip(self):
        key = b"0123456789abcdef"
        plain = b"abcdefghijklmnop"  # 16 bytes
        enc = tea_encrypt(plain, key)
        self.assertNotEqual(enc, plain)
        self.assertEqual(plain, tea_decrypt(enc, key))

    def test_crypt_zlib_block_roundtrip(self):
        priv = load_private_key_hex()
        self.assertTrue(priv)
        line = b"2026-08-21 09:00:00.001 [I][Crypt] hello-encrypted-xlog\n"
        blob = build_crypt_zlib_block(line, priv, seq=1)
        plain = decode_bytes(blob, private_key_hex=priv)
        self.assertIn(b"hello-encrypted-xlog", plain)

    def test_crypt_fixture_file(self):
        fixture = Path(__file__).resolve().parent / "testdata" / "crypt_sample.xlog"
        self.assertTrue(fixture.is_file(), "run _make_crypt_fixture.py first")
        rows = decode_file(str(fixture))
        msgs = [r.get("msg") for r in rows]
        self.assertTrue(any("hello-encrypted-xlog" in str(m) for m in msgs), msgs)

    def test_crypt_missing_key_reports_internal(self):
        priv = load_private_key_hex()
        line = b"2026-08-21 09:00:00.001 [I][Crypt] secret\n"
        blob = build_crypt_zlib_block(line, priv, seq=1)
        plain = decode_bytes(blob, private_key_hex="")
        self.assertIn(b"private key missing", plain)


if __name__ == "__main__":
    unittest.main()
