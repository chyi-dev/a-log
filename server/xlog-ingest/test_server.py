#!/usr/bin/env python3
import json
import os
import tempfile
import threading
import unittest
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
from pathlib import Path

import server


class XlogIngestServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.data = self.tmp.name
        self._orig = server.DATA
        server.DATA = self.data
        os.makedirs(self.data, exist_ok=True)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        server.DATA = self._orig
        self.tmp.cleanup()

    def _json(self, method, path, body=None, auth=True):
        conn = HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer xlog-dev"
        raw = json.dumps(body).encode("utf-8") if body is not None else b""
        if method in ("POST", "PUT") or body is not None:
            headers["Content-Length"] = str(len(raw))
        conn.request(method, path, body=raw if method != "GET" else None, headers=headers)
        res = conn.getresponse()
        text = res.read().decode("utf-8")
        conn.close()
        return res.status, (json.loads(text) if text else {})

    def test_auth_required(self):
        code, _ = self._json("GET", "/logs/tasks", auth=False)
        self.assertEqual(401, code)

    def test_fetch_create_pending_ack(self):
        code, created = self._json(
            "POST",
            "/logs/fetch-tasks",
            {
                "unionId": "demo-user",
                "deviceId": "dev-1",
                "fromMs": 1,
                "toMs": 2,
                "maxBytes": 1024,
            },
        )
        self.assertEqual(200, code)
        task_id = created["taskId"]
        code, pending = self._json(
            "GET", "/logs/fetch-pending?unionId=demo-user&deviceId=dev-1"
        )
        self.assertEqual(200, code)
        self.assertEqual(1, len(pending["tasks"]))
        code, ack = self._json("POST", "/logs/fetch-ack", {"taskId": task_id, "ok": True})
        self.assertEqual(200, code)
        self.assertTrue(ack.get("ok"))
        code, pending = self._json(
            "GET", "/logs/fetch-pending?unionId=demo-user&deviceId=dev-1"
        )
        self.assertEqual([], pending["tasks"])

    def test_init_upload_rejects_empty(self):
        code, body = self._json("POST", "/logs/uploads", {"files": []})
        self.assertEqual(400, code)

    def test_decode_uploaded_placeholder(self):
        upload_id = "u-test1"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        assembled = Path(self.data) / "files" / upload_id
        assembled.mkdir(parents=True)
        (assembled / "a.xlog").write_bytes(b"not-an-xlog")
        (tasks / (upload_id + ".json")).write_text(
            json.dumps(
                {
                    "uploadId": upload_id,
                    "status": "negotiating",
                    "meta": {"unionId": "demo-user", "reason": "manual"},
                    "files": [{"fileId": "f-1", "name": "a.xlog", "skip": False}],
                }
            ),
            encoding="utf-8",
        )
        code, decoded = self._json("POST", "/logs/tasks/%s/decode" % upload_id, {})
        self.assertEqual(200, code)
        code, details = self._json("GET", "/logs/tasks/%s/details" % upload_id)
        self.assertEqual(200, code)
        self.assertGreaterEqual(details["total"], 1)

    def test_details_pagination(self):
        upload_id = "u-page"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        rows = [
            {"msg": "line-%s" % i, "type": "code", "ts": i, "tag": "T", "level": "I"}
            for i in range(5)
        ]
        (tasks / (upload_id + ".json")).write_text(
            json.dumps(
                {
                    "uploadId": upload_id,
                    "status": "done",
                    "meta": {"unionId": "demo-user"},
                    "files": [],
                    "details": rows,
                }
            ),
            encoding="utf-8",
        )
        code, page0 = self._json(
            "GET", "/logs/tasks/%s/details?page=0&size=2" % upload_id
        )
        self.assertEqual(200, code)
        self.assertEqual(5, page0["total"])
        self.assertEqual(["line-0", "line-1"], [r["msg"] for r in page0["items"]])


if __name__ == "__main__":
    unittest.main()
