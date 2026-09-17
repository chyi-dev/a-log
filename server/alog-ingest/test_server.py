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

    def test_export_txt_serial_annotates(self):
        upload_id = "u-export-serial"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        rows = [
            {"ts": 100, "type": "code", "tag": "Coffee-Machine", "msg": "/dev/ttyS3---发送：AA 55 02 20 21", "level": "info"},
            {"ts": 200, "type": "code", "tag": "Coffee-Machine", "msg": "/dev/ttyS4---发送：AA 55 02 1E 1F", "level": "info"},
        ]
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "done",
            "meta": {},
            "files": [],
            "details": rows,
        }), encoding="utf-8")

        status, headers, body = self._raw_get("/logs/tasks/%s/export.txt?serial=1" % upload_id)
        self.assertEqual(200, status)
        self.assertIn('filename="u-export-serial-serial.txt"', headers.get("content-disposition", ""))
        text = body.decode("utf-8")
        self.assertIn("无 Modbus 载荷", text)
        self.assertIn("查询主控运行状态", text)

    def test_details_serial_notes(self):
        upload_id = "u-details-serial"
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        rows = [
            {"ts": 100, "type": "code", "tag": "Coffee-Machine", "msg": "/dev/ttyS4---发送：AA 55 02 1E 1F", "level": "info"},
            {"ts": 200, "type": "code", "tag": "ALog", "msg": "hello", "level": "info"},
        ]
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "done",
            "meta": {},
            "files": [],
            "details": rows,
        }), encoding="utf-8")

        code, data = self._json("GET", "/logs/tasks/%s/details?serial=1" % upload_id)
        self.assertEqual(200, code)
        items = data["items"]
        self.assertEqual(2, len(items))
        self.assertIn("serialNotes", items[0])
        self.assertTrue(any("查询主控运行状态" in n for n in items[0]["serialNotes"]))
        self.assertEqual([], items[1]["serialNotes"])

        code, plain = self._json("GET", "/logs/tasks/%s/details" % upload_id)
        self.assertEqual(200, code)
        self.assertNotIn("serialNotes", plain["items"][0])

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

    def test_export_source_single_and_zip(self):
        import io
        import zipfile

        upload_one = "u-src-one"
        assembled = Path(self.data) / "files" / upload_one
        assembled.mkdir(parents=True)
        blob = make_unencrypted_alog(['{"ts":1,"level":"INFO","type":"code","tag":"T","msg":"one"}'])
        (assembled / "a.alog").write_bytes(blob)
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        (tasks / (upload_one + ".json")).write_text(json.dumps({
            "uploadId": upload_one,
            "status": "done",
            "meta": {},
            "files": [{"fileId": "f-1", "name": "a.alog", "storedName": "a.alog", "skip": False}],
            "details": [],
        }), encoding="utf-8")

        status, headers, body = self._raw_get("/logs/tasks/%s/export.source" % upload_one)
        self.assertEqual(200, status)
        self.assertEqual("application/octet-stream", headers.get("content-type"))
        self.assertIn('filename="a.alog"', headers.get("content-disposition", ""))
        self.assertEqual(blob, body)

        upload_multi = "u-src-multi"
        multi_dir = Path(self.data) / "files" / upload_multi
        multi_dir.mkdir(parents=True)
        b1 = make_unencrypted_alog(['{"ts":1,"level":"INFO","type":"code","tag":"T","msg":"a"}'])
        b2 = make_unencrypted_alog(['{"ts":2,"level":"INFO","type":"code","tag":"T","msg":"b"}'])
        (multi_dir / "one.alog").write_bytes(b1)
        (multi_dir / "two.alog").write_bytes(b2)
        (tasks / (upload_multi + ".json")).write_text(json.dumps({
            "uploadId": upload_multi,
            "status": "done",
            "meta": {},
            "files": [
                {"fileId": "f-1", "name": "one.alog", "storedName": "one.alog", "skip": False},
                {"fileId": "f-2", "name": "two.alog", "storedName": "two.alog", "skip": False},
            ],
            "details": [],
        }), encoding="utf-8")

        status, headers, body = self._raw_get("/logs/tasks/%s/export.source" % upload_multi)
        self.assertEqual(200, status)
        self.assertEqual("application/zip", headers.get("content-type"))
        self.assertIn('filename="u-src-multi.zip"', headers.get("content-disposition", ""))
        with zipfile.ZipFile(io.BytesIO(body)) as zf:
            names = sorted(zf.namelist())
            self.assertEqual(["one.alog", "two.alog"], names)
            self.assertEqual(b1, zf.read("one.alog"))
            self.assertEqual(b2, zf.read("two.alog"))

        status, _, _ = self._raw_get("/logs/tasks/%s/export.source" % upload_one, auth=False)
        self.assertEqual(401, status)

        status, _, _ = self._raw_get("/logs/tasks/u-missing/export.source")
        self.assertEqual(404, status)

    def test_complete_already_done_is_idempotent(self):
        upload_id = "u-done-again"
        assembled = Path(self.data) / "files" / upload_id
        assembled.mkdir(parents=True)
        blob = make_unencrypted_alog(['{"ts":1,"level":"INFO","type":"code","tag":"T","msg":"x"}'])
        (assembled / "a.alog").write_bytes(blob)
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        (tasks / (upload_id + ".json")).write_text(json.dumps({
            "uploadId": upload_id,
            "status": "done",
            "meta": {},
            "files": [{"fileId": "f-1", "name": "a.alog", "storedName": "a.alog", "skip": False}],
            "details": [{"ts": 1, "msg": "x", "type": "code"}],
        }), encoding="utf-8")
        code, body = self._json("POST", "/logs/uploads/%s/complete" % upload_id, {})
        self.assertEqual(200, code)
        self.assertEqual("done", body["status"])
        self.assertEqual(1, body["lines"])

    def _write_task(self, upload_id, meta, files=None, details=None, status="done", created_at=None):
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True, exist_ok=True)
        record = {
            "uploadId": upload_id,
            "status": status,
            "meta": meta,
            "files": files or [],
            "details": details or [],
        }
        if created_at is not None:
            record["createdAt"] = created_at
        (tasks / (upload_id + ".json")).write_text(json.dumps(record), encoding="utf-8")

    def test_tasks_filter_by_user_device_date_and_type(self):
        self._write_task("u-a", {"unionId": "user-1", "deviceId": "dev-1", "reason": "manual"}, files=[
            {"fileId": "f-1", "name": "alog_20260915_0.alog", "date": "20260915"},
        ], details=[
            {"type": "code", "tag": "A", "msg": "day15-code"},
        ], created_at=100)
        self._write_task("u-b", {"unionId": "user-1", "deviceId": "dev-1", "reason": "manual"}, files=[
            {"fileId": "f-2", "name": "alog_push_20260916_0.alog"},
        ], details=[
            {"type": "network", "tag": "Http", "msg": "day16-net"},
        ], created_at=200)
        self._write_task("u-c", {"unionId": "user-2", "deviceId": "dev-9", "reason": "fetch"}, files=[
            {"fileId": "f-3", "name": "alog_20260916.alog", "date": "20260916"},
        ], created_at=300)

        code, all_user = self._json("GET", "/logs/tasks?unionId=user-1&deviceId=dev-1")
        self.assertEqual(200, code)
        self.assertEqual(2, all_user["total"])
        self.assertEqual(["u-b", "u-a"], [t["uploadId"] for t in all_user["tasks"]])
        self.assertEqual("dev-1", all_user["tasks"][0]["deviceId"])

        code, day16 = self._json("GET", "/logs/tasks?unionId=user-1&fromDate=20260916&toDate=2026-09-16")
        self.assertEqual(200, code)
        self.assertEqual(["u-b"], [t["uploadId"] for t in day16["tasks"]])

        code, typed = self._json("GET", "/logs/tasks?unionId=user-1&type=network")
        self.assertEqual(200, code)
        self.assertEqual(["u-b"], [t["uploadId"] for t in typed["tasks"]])

        code, page0 = self._json("GET", "/logs/tasks?unionId=user-1&page=0&size=1")
        self.assertEqual(200, code)
        self.assertEqual(2, page0["total"])
        self.assertEqual(1, len(page0["tasks"]))
        self.assertEqual("u-b", page0["tasks"][0]["uploadId"])
        code, page1 = self._json("GET", "/logs/tasks?unionId=user-1&page=1&size=1")
        self.assertEqual(["u-a"], [t["uploadId"] for t in page1["tasks"]])

        code, empty = self._json("GET", "/logs/tasks?unionId=nobody")
        self.assertEqual(200, code)
        self.assertEqual(0, empty["total"])
        self.assertEqual([], empty["tasks"])

    def test_tasks_skips_corrupt_json_and_clamps_page(self):
        tasks = Path(self.data) / "tasks"
        tasks.mkdir(parents=True)
        (tasks / "u-ok.json").write_text(json.dumps({
            "uploadId": "u-ok",
            "status": "done",
            "meta": {"unionId": "demo-user"},
            "files": [{"name": "alog_20260916_0.alog"}],
            "createdAt": 1,
        }), encoding="utf-8")
        (tasks / "u-bad.json").write_text("{not-json", encoding="utf-8")
        (tasks / "readme.txt").write_text("ignore", encoding="utf-8")

        code, data = self._json("GET", "/logs/tasks?page=-3&size=9999")
        self.assertEqual(200, code)
        self.assertEqual(1, data["total"])
        self.assertEqual("u-ok", data["tasks"][0]["uploadId"])
        self.assertEqual(0, data["page"])
        self.assertEqual(200, data["size"])

        code, _details = self._json("GET", "/logs/tasks/u-bad/details")
        self.assertEqual(404, code)

    def test_details_type_aliases_and_from_date(self):
        upload_id = "u-type-alias"
        self._write_task(upload_id, {}, details=[
            {"ts": 1757952000000, "type": 2, "tag": "Http", "msg": "numeric-net"},
            {"ts": 1758038400000, "type": "code", "tag": "A", "msg": "named-code"},
            {"ts": 1758124800000, "type": "t10", "tag": "Biz", "msg": "biz"},
        ])
        code, net = self._json("GET", "/logs/tasks/%s/details?type=network" % upload_id)
        self.assertEqual(200, code)
        self.assertEqual(["numeric-net"], [r["msg"] for r in net["items"]])
        code, net2 = self._json("GET", "/logs/tasks/%s/details?type=2" % upload_id)
        self.assertEqual(["numeric-net"], [r["msg"] for r in net2["items"]])
        code, biz = self._json("GET", "/logs/tasks/%s/details?type=10" % upload_id)
        self.assertEqual(["biz"], [r["msg"] for r in biz["items"]])
        day = server.yyyymmdd_of_ts(1758038400000)
        code, dated = self._json("GET", "/logs/tasks/%s/details?fromDate=%s&toDate=%s" % (upload_id, day, day))
        self.assertEqual(200, code)
        self.assertEqual(["named-code"], [r["msg"] for r in dated["items"]])
        self.assertEqual(0, dated["page"])
        self.assertEqual(200, dated["size"])

        status, _, body = self._raw_get("/logs/tasks/%s/export.txt?type=2" % upload_id)
        self.assertEqual(200, status)
        text = body.decode("utf-8")
        self.assertIn("Http:numeric-net", text)
        self.assertNotIn("named-code", text)

    def test_details_invalid_page_does_not_500(self):
        self._write_task("u-page-bad", {}, details=[{"msg": "x", "type": "code", "ts": 1}])
        code, data = self._json("GET", "/logs/tasks/u-page-bad/details?page=nope&size=oops")
        self.assertEqual(200, code)
        self.assertEqual(1, data["total"])
        self.assertEqual(["x"], [r["msg"] for r in data["items"]])

    def test_fetch_ack_requires_known_task_and_records_upload(self):
        code, created = self._json("POST", "/logs/fetch-tasks", {"unionId": "demo-user"})
        self.assertEqual(200, code)
        task_id = created["taskId"]

        code, missing = self._json("POST", "/logs/fetch-ack", {"ok": True})
        self.assertEqual(400, code)
        self.assertEqual("taskId required", missing["error"])

        code, unknown = self._json("POST", "/logs/fetch-ack", {"taskId": "ft-missing", "ok": True})
        self.assertEqual(404, code)
        self.assertEqual("unknown taskId", unknown["error"])

        code, ack = self._json("POST", "/logs/fetch-ack", {
            "taskId": task_id,
            "ok": True,
            "uploadId": "u-from-device",
        })
        self.assertEqual(200, code)
        self.assertEqual("acked", ack["status"])
        self.assertEqual("u-from-device", ack["uploadId"])
        self.assertIsNotNone(ack.get("ackedAt"))

        code, listed = self._json("GET", "/logs/fetch-tasks?unionId=demo-user")
        self.assertEqual(200, code)
        self.assertEqual(1, len(listed["tasks"]))
        self.assertEqual("acked", listed["tasks"][0]["status"])
        self.assertEqual("u-from-device", listed["tasks"][0]["uploadId"])

        code, pending = self._json("GET", "/logs/fetch-pending?unionId=demo-user")
        self.assertEqual(200, code)
        self.assertEqual([], pending["tasks"])

        code, again = self._json("POST", "/logs/fetch-ack", {
            "taskId": task_id,
            "ok": True,
            "uploadId": "u-from-device",
        })
        self.assertEqual(200, code)
        self.assertEqual("acked", again["status"])
        self.assertTrue(again.get("idempotent"))
        self.assertEqual("u-from-device", again["uploadId"])

    def test_responses_send_connection_close(self):
        status, headers, _ = self._raw_get("/logs/fetch-pending?unionId=demo-user&deviceId=dev-1")
        self.assertEqual(200, status)
        self.assertEqual("close", headers.get("connection"))

    def test_complete_then_ack_on_reused_http_connection(self):
        code, created = self._json("POST", "/logs/fetch-tasks", {
            "unionId": "demo-user",
            "deviceId": "dev-1",
        })
        self.assertEqual(200, code)
        task_id = created["taskId"]
        upload_id = "u-keepalive"
        self._write_task(upload_id, {"unionId": "demo-user"}, details=[
            {"ts": 1, "msg": "x", "type": "code"},
        ])

        conn = HTTPConnection("127.0.0.1", self.port, timeout=5)
        headers = {
            "Content-Type": "application/json",
            "Authorization": "Bearer alog-dev",
            "Connection": "keep-alive",
        }
        conn.request("POST", "/logs/uploads/%s/complete" % upload_id, body=b"{}", headers=headers)
        complete_res = conn.getresponse()
        complete_headers = {k.lower(): v for k, v in complete_res.getheaders()}
        complete_body = complete_res.read()
        self.assertEqual(200, complete_res.status)
        self.assertEqual("close", complete_headers.get("connection"))
        self.assertTrue(complete_body)

        ack_raw = json.dumps({
            "taskId": task_id,
            "ok": True,
            "uploadId": upload_id,
        }).encode("utf-8")
        conn.request("POST", "/logs/fetch-ack", body=ack_raw, headers=headers)
        ack_res = conn.getresponse()
        ack_body = json.loads(ack_res.read().decode("utf-8"))
        self.assertEqual(200, ack_res.status)
        self.assertEqual("acked", ack_body["status"])
        self.assertEqual(upload_id, ack_body["uploadId"])
        conn.close()

    def test_negotiate_stores_file_date_from_push_filename(self):
        code, body = self._json("POST", "/logs/uploads", {
            "appId": "a",
            "unionId": "user-1",
            "deviceId": "dev-1",
            "files": [{"name": "alog_push_20260916_0.alog", "size": 4, "sha256": "aa"}],
        })
        self.assertEqual(200, code)
        upload_id = body["uploadId"]
        code, listed = self._json("GET", "/logs/tasks?unionId=user-1&fromDate=20260916&toDate=20260916")
        self.assertEqual(200, code)
        self.assertEqual([upload_id], [t["uploadId"] for t in listed["tasks"]])
        self.assertEqual(["20260916"], listed["tasks"][0]["logDates"])


if __name__ == "__main__":
    unittest.main()
