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

    def _raw_get(self, path, auth=True):
        conn = HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {}
        if auth:
            headers["Authorization"] = "Bearer alog-dev"
        conn.request("GET", path, headers=headers)
        res = conn.getresponse()
        body = res.read()
        headers_out = {k.lower(): v for k, v in res.getheaders()}
        status = res.status
        conn.close()
        return status, headers_out, body

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

    def test_details_tag_type_and_time(self):
        upload_id = "u-filter"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        rows = [
            {"ts": 100, "type": "code", "tag": "A", "msg": "a", "level": "I"},
            {"ts": 200, "type": "network", "tag": "B", "msg": "b", "level": "W"},
            {"ts": 300, "type": "code", "tag": "A", "msg": "c", "level": "E"},
        ]
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "done",
            "meta": {},
            "files": [],
            "details": rows,
        }), encoding="utf-8")
        code, data = self._json("GET", "/logs/tasks/%s/details?type=code&tag=A" % upload_id)
        self.assertEqual(200, code)
        self.assertEqual(["a", "c"], [r["msg"] for r in data["items"]])
        code, timed = self._json("GET", "/logs/tasks/%s/details?fromTs=150&toTs=250" % upload_id)
        self.assertEqual(["b"], [r["msg"] for r in timed["items"]])
        code, summary = self._json("GET", "/logs/tasks/%s/details/summary?bucket=100" % upload_id)
        self.assertEqual(200, code)
        self.assertEqual(3, len(summary["buckets"]))

    def test_export_txt_legacy_format_and_filter(self):
        upload_id = "u-export"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        rows = [
            {"ts": 100, "type": "code", "tag": "Coffee-Machine", "msg": "/dev/ttyS3---发送：AA 55 02 20 21", "level": "info"},
            {"ts": 200, "type": "network", "tag": "Http", "msg": "GET /ping 200", "level": "info"},
            {"ts": 300, "type": "code", "tag": "ALog", "msg": "line\nwith\nbreaks", "level": "info"},
        ]
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "done",
            "meta": {},
            "files": [],
            "details": rows,
        }), encoding="utf-8")

        status, headers, body = self._raw_get("/logs/tasks/%s/export.txt" % upload_id)
        self.assertEqual(200, status)
        self.assertTrue(headers.get("content-type", "").startswith("text/plain"))
        self.assertIn('filename="u-export.txt"', headers.get("content-disposition", ""))
        text = body.decode("utf-8")
        self.assertEqual(server.rows_to_legacy_txt(rows), text)
        first = text.splitlines()[0]
        self.assertRegex(first, r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} Coffee-Machine:/dev/ttyS3---发送：AA 55 02 20 21$")
        self.assertIn(" ALog:line with breaks", text)

        status, _, filtered = self._raw_get("/logs/tasks/%s/export.txt?type=code&tag=Coffee-Machine" % upload_id)
        self.assertEqual(200, status)
        filtered_text = filtered.decode("utf-8")
        self.assertEqual(1, len([ln for ln in filtered_text.splitlines() if ln]))
        self.assertIn("Coffee-Machine:", filtered_text)
        self.assertNotIn("Http:", filtered_text)

        status, _, _ = self._raw_get("/logs/tasks/%s/export.txt" % upload_id, auth=False)
        self.assertEqual(401, status)

    def test_init_upload_rejects_empty_files(self):
        code, body = self._json("POST", "/logs/uploads", {
            "appId": "a",
            "unionId": "u",
            "deviceId": "d",
            "files": [],
        })
        self.assertEqual(400, code)
        self.assertFalse((Path(self.data) / "files").exists())
        self.assertFalse((Path(self.data) / "tasks").exists() and any((Path(self.data) / "tasks").iterdir()))

    def test_multi_file_details_sorted_by_ts(self):
        upload_id = "u-multiproc"
        assembled = Path(self.data) / "files" / upload_id
        assembled.mkdir(parents=True)
        (assembled / "alog_main.alog").write_bytes(
            make_unencrypted_alog(['{"ts":200,"level":"INFO","type":"code","tag":"Main","msg":"main"}'])
        )
        (assembled / "alog_push.alog").write_bytes(
            make_unencrypted_alog(['{"ts":100,"level":"INFO","type":"internal","tag":"Push","msg":"push"}'])
        )
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "negotiating",
            "meta": {"unionId": "demo-user", "reason": "manual"},
            "files": [
                {"fileId": "f-main", "name": "alog_main.alog", "skip": False},
                {"fileId": "f-push", "name": "alog_push.alog", "skip": False},
            ],
        }), encoding="utf-8")

        code, decoded = self._json("POST", "/logs/tasks/%s/decode" % upload_id, {})
        self.assertEqual(200, code)
        self.assertGreaterEqual(decoded["lines"], 2)

        code, details = self._json("GET", "/logs/tasks/%s/details" % upload_id)
        self.assertEqual(200, code)
        self.assertEqual(["push", "main"], [r["msg"] for r in details["items"]])

        status, _, body = self._raw_get("/logs/tasks/%s/export.txt" % upload_id)
        self.assertEqual(200, status)
        lines = [ln for ln in body.decode("utf-8").splitlines() if ln]
        self.assertEqual(2, len(lines))
        self.assertIn("Push:push", lines[0])
        self.assertIn("Main:main", lines[1])

    def test_filter_sorts_legacy_unsorted_details(self):
        upload_id = "u-legacy-sort"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        rows = [
            {"ts": 300, "type": "code", "tag": "Main", "msg": "late-main"},
            {"ts": 100, "type": "internal", "tag": "Push", "msg": "early-push"},
            {"ts": 200, "type": "code", "tag": "Main", "msg": "mid-main"},
        ]
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "done",
            "meta": {},
            "files": [],
            "details": rows,
        }), encoding="utf-8")

        code, details = self._json("GET", "/logs/tasks/%s/details" % upload_id)
        self.assertEqual(200, code)
        self.assertEqual(
            ["early-push", "mid-main", "late-main"],
            [r["msg"] for r in details["items"]],
        )

    def test_skip_files_not_decoded_into_new_task(self):
        upload_old = "u-old"
        assembled_old = Path(self.data) / "files" / upload_old
        assembled_old.mkdir(parents=True)
        (assembled_old / "old.alog").write_bytes(
            make_unencrypted_alog(['{"ts":100,"level":"INFO","type":"code","tag":"T","msg":"old-line"}'])
        )
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        (tasks / (upload_old + ".json")).write_text(json.dumps({
            "uploadId": upload_old,
            "status": "done",
            "meta": {"unionId": "demo-user"},
            "files": [{"fileId": "f-old", "name": "old.alog", "skip": False}],
            "details": [{"ts": 100, "msg": "old-line", "type": "code", "tag": "T", "level": "I"}],
        }), encoding="utf-8")

        upload_new = "u-new"
        assembled_new = Path(self.data) / "files" / upload_new
        assembled_new.mkdir(parents=True)
        (assembled_new / "new.alog").write_bytes(
            make_unencrypted_alog(['{"ts":200,"level":"INFO","type":"code","tag":"T","msg":"new-line"}'])
        )
        (tasks / (upload_new + ".json")).write_text(json.dumps({
            "uploadId": upload_new,
            "status": "negotiating",
            "meta": {"unionId": "demo-user"},
            "files": [
                {"fileId": "f-old", "name": "old.alog", "skip": True, "sha256": "deadbeef"},
                {"fileId": "f-new", "name": "new.alog", "skip": False},
            ],
        }), encoding="utf-8")

        code, decoded = self._json("POST", "/logs/tasks/%s/decode" % upload_new, {})
        self.assertEqual(200, code)
        self.assertEqual(1, decoded["lines"])

        code, details = self._json("GET", "/logs/tasks/%s/details" % upload_new)
        self.assertEqual(200, code)
        msgs = [r["msg"] for r in details["items"]]
        self.assertEqual(["new-line"], msgs)
        self.assertNotIn("old-line", msgs)

    def test_all_skip_files_yield_empty_details(self):
        upload_id = "u-all-skip"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "negotiating",
            "meta": {},
            "files": [
                {"fileId": "f-1", "name": "a.alog", "skip": True, "sha256": "abc"},
                {"fileId": "f-2", "name": "b.alog", "skip": True, "sha256": "def"},
            ],
        }), encoding="utf-8")

        code, decoded = self._json("POST", "/logs/tasks/%s/decode" % upload_id, {})
        self.assertEqual(200, code)
        self.assertEqual(0, decoded["lines"])

        code, details = self._json("GET", "/logs/tasks/%s/details" % upload_id)
        self.assertEqual(200, code)
        self.assertEqual([], details["items"])


if __name__ == "__main__":
    unittest.main()
