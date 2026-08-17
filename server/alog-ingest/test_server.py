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
from test_decode_alog import make_unencrypted_alog


class IngestServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.data = self.tmp.name
        self._orig_data = server.DATA
        server.DATA = self.data
        os.makedirs(self.data, exist_ok=True)
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        server.DATA = self._orig_data
        self.tmp.cleanup()

    def _json(self, method, path, body=None, auth=True):
        conn = HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {"Content-Type": "application/json"}
        if auth:
            headers["Authorization"] = "Bearer alog-dev"
        raw = json.dumps(body).encode("utf-8") if body is not None else b""
        if method in ("POST", "PUT") or body is not None:
            headers["Content-Length"] = str(len(raw))
        conn.request(method, path, body=raw if method != "GET" else None, headers=headers)
        res = conn.getresponse()
        text = res.read().decode("utf-8")
        conn.close()
        parsed = json.loads(text) if text else {}
        return res.status, parsed

    def test_create_fetch_task_and_pending_then_ack(self):
        code, created = self._json("POST", "/logs/fetch-tasks", {
            "unionId": "demo-user",
            "deviceId": "dev-1",
            "fromMs": 1,
            "toMs": 2,
            "maxBytes": 1024,
        })
        self.assertEqual(200, code)
        task_id = created["taskId"]
        self.assertTrue(task_id.startswith("ft-"))
        self.assertEqual("pending", created["status"])

        code, pending = self._json("GET", "/logs/fetch-pending?unionId=demo-user&deviceId=dev-1")
        self.assertEqual(200, code)
        self.assertEqual(1, len(pending["tasks"]))
        self.assertEqual(task_id, pending["tasks"][0]["taskId"])

        code, ack = self._json("POST", "/logs/fetch-ack", {"taskId": task_id, "ok": True})
        self.assertEqual(200, code)
        self.assertTrue(ack["ok"])

        code, pending = self._json("GET", "/logs/fetch-pending?unionId=demo-user&deviceId=dev-1")
        self.assertEqual(200, code)
        self.assertEqual([], pending["tasks"])

    def test_decode_uploaded_file_into_details(self):
        upload_id = "u-test1"
        assembled = Path(self.data) / "files" / upload_id
        assembled.mkdir(parents=True)
        (assembled / "a.alog").write_bytes(
            make_unencrypted_alog(['{"ts":9,"level":"INFO","type":"code","tag":"T","msg":"shown"}'])
        )
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "done",
            "meta": {"unionId": "demo-user", "reason": "manual"},
            "files": [{"fileId": "f-1", "name": "a.alog", "skip": False}],
        }), encoding="utf-8")

        code, decoded = self._json("POST", "/logs/tasks/%s/decode" % upload_id, {})
        self.assertEqual(200, code)
        self.assertGreaterEqual(decoded["lines"], 1)

        code, details = self._json("GET", "/logs/tasks/%s/details" % upload_id)
        self.assertEqual(200, code)
        msgs = [row.get("msg") for row in details["items"]]
        self.assertIn("shown", msgs)

    def test_details_pagination(self):
        upload_id = "u-page"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        rows = [{"msg": "line-%s" % i, "type": "code"} for i in range(5)]
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "done",
            "meta": {"unionId": "demo-user"},
            "files": [],
            "details": rows,
        }), encoding="utf-8")
        code, page0 = self._json("GET", "/logs/tasks/%s/details?page=0&size=2" % upload_id)
        self.assertEqual(200, code)
        self.assertEqual(5, page0["total"])
        self.assertEqual(["line-0", "line-1"], [r["msg"] for r in page0["items"]])
        code, page1 = self._json("GET", "/logs/tasks/%s/details?page=1&size=2" % upload_id)
        self.assertEqual(200, code)
        self.assertEqual(["line-2", "line-3"], [r["msg"] for r in page1["items"]])


if __name__ == "__main__":
    unittest.main()
