#!/usr/bin/env python3
import unittest

from decode_xlog import lines_to_details, decode_bytes, decode_file
import tempfile
import os


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


if __name__ == "__main__":
    unittest.main()
