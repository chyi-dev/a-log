#!/usr/bin/env python3
"""Minimal ALog ingest + query server. Run: python server.py [port]"""
from __future__ import annotations

import hashlib
import io
import json
import os
import re
import sys
import threading
import time
import uuid
import zipfile
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from decode_alog import decode_file
import gs_serial

ROOT = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(ROOT, "data")
CONSOLE = os.path.join(os.path.dirname(ROOT), "alog-console")
TOKEN = "alog-dev"
CHUNK_SIZE = 2 * 1024 * 1024
lock = threading.RLock()

os.makedirs(DATA, exist_ok=True)


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def json_body(handler: BaseHTTPRequestHandler) -> dict:
    length = int(handler.headers.get("Content-Length") or 0)
    raw = handler.rfile.read(length) if length else b"{}"
    if not raw:
        return {}
    return json.loads(raw.decode("utf-8"))


def check_auth(handler: BaseHTTPRequestHandler) -> bool:
    auth = handler.headers.get("Authorization", "")
    return auth == f"Bearer {TOKEN}"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt: str, *args) -> None:
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _send(self, code: int, body, content_type: str = "application/json", extra_headers=None) -> None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        if extra_headers:
            for key, value in extra_headers.items():
                self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Authorization,Content-Type,Content-SHA256,Content-Range")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,PUT,OPTIONS")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            return self._static("index.html", "text/html")
        if parsed.path.startswith("/static/"):
            return self._static(parsed.path[len("/static/") :], "text/css" if parsed.path.endswith(".css") else "text/javascript")
        if not check_auth(self) and parsed.path.startswith("/logs/"):
            return self._send(401, {"error": "unauthorized"})
        if parsed.path == "/logs/tasks":
            return self._tasks(parse_qs(parsed.query))
        if parsed.path == "/logs/fetch-tasks":
            return self._list_fetch_tasks(parse_qs(parsed.query))
        if parsed.path == "/logs/fetch-pending":
            return self._pending_fetch_tasks(parse_qs(parsed.query))
        m = re.match(r"/logs/tasks/([^/]+)/details/summary$", parsed.path)
        if m:
            return self._details_summary(m.group(1), parse_qs(parsed.query))
        m = re.match(r"/logs/tasks/([^/]+)/export\.txt$", parsed.path)
        if m:
            return self._export_txt(m.group(1), parse_qs(parsed.query))
        m = re.match(r"/logs/tasks/([^/]+)/export\.source$", parsed.path)
        if m:
            return self._export_source(m.group(1))
        m = re.match(r"/logs/tasks/([^/]+)/details$", parsed.path)
        if m:
            return self._details(m.group(1), parse_qs(parsed.query))
        self._send(404, {"error": "not found"})

    def do_POST(self) -> None:
        if not check_auth(self):
            return self._send(401, {"error": "unauthorized"})
        parsed = urlparse(self.path)
        if parsed.path == "/logs/uploads":
            return self._init_upload()
        m = re.match(r"/logs/uploads/([^/]+)/complete$", parsed.path)
        if m:
            return self._complete(m.group(1))
        m = re.match(r"/logs/tasks/([^/]+)/decode$", parsed.path)
        if m:
            return self._decode_task(m.group(1))
        if parsed.path == "/logs/fetch-tasks":
            return self._create_fetch_task()
        if parsed.path == "/logs/fetch-ack":
            return self._fetch_ack()
        self._send(404, {"error": "not found"})

    def do_PUT(self) -> None:
        if not check_auth(self):
            return self._send(401, {"error": "unauthorized"})
        m = re.match(r"/logs/uploads/([^/]+)/files/([^/]+)/chunks/(\d+)$", self.path)
        if not m:
            return self._send(404, {"error": "not found"})
        upload_id, file_id, index = m.group(1), m.group(2), int(m.group(3))
        length = int(self.headers.get("Content-Length") or 0)
        data = self.rfile.read(length)
        expected = (self.headers.get("Content-SHA256") or "").lower()
        if expected and sha256_bytes(data) != expected:
            return self._send(409, {"error": "chunk sha256 mismatch"})
        dest = os.path.join(DATA, "uploads", upload_id, file_id)
        os.makedirs(dest, exist_ok=True)
        with open(os.path.join(dest, f"{index}.part"), "wb") as f:
            f.write(data)
        self._send(200, {"ok": True, "index": index})

    def _static(self, name: str, ctype: str) -> None:
        path = os.path.join(CONSOLE, name)
        if not os.path.isfile(path):
            return self._send(404, {"error": "no console"})
        with open(path, "rb") as f:
            self._send(200, f.read(), ctype)

    def _init_upload(self) -> None:
        body = json_body(self)
        files_in = body.get("files") or []
        if not files_in:
            return self._send(400, {"error": "files required"})
        upload_id = "u-" + uuid.uuid4().hex[:12]
        record = {
            "uploadId": upload_id,
            "status": "negotiating",
            "meta": body,
            "files": [],
            "createdAt": int(time.time() * 1000),
        }
        files_out = []
        for item in files_in:
            file_id = "f-" + uuid.uuid4().hex[:12]
            digest = item.get("sha256")
            skip = False
            index_path = os.path.join(DATA, "hash-index.json")
            with lock:
                index = {}
                if os.path.isfile(index_path):
                    with open(index_path, encoding="utf-8") as f:
                        index = json.load(f)
                if digest and digest in index:
                    skip = True
                    hit = index[digest]
                    file_id = hit["fileId"] if isinstance(hit, dict) else hit
            name = item.get("name")
            files_out.append({
                "fileId": file_id,
                "name": name,
                "path": item.get("path"),
                "sha256": digest,
                "skip": skip,
                "size": item.get("size"),
                "date": parse_date_param(item.get("date")) or extract_yyyymmdd(name or ""),
            })
        record["files"] = files_out
        self._save_task(record)
        self._send(200, {"uploadId": upload_id, "chunkSize": CHUNK_SIZE, "files": files_out})

    def _complete(self, upload_id: str) -> None:
        record = self._load_task(upload_id)
        if not record:
            return self._send(404, {"error": "unknown uploadId"})
        with lock:
            record = self._load_task(upload_id) or record
            if record.get("status") == "done":
                return self._send(200, {
                    "status": "done",
                    "uploadId": upload_id,
                    "lines": len(record.get("details") or []),
                })
            err = self._assemble_and_decode(record)
            if err:
                return self._send(err[0], err[1])
            record = self._load_task(upload_id) or record
            self._send(200, {"status": "done", "uploadId": upload_id, "lines": len(record.get("details") or [])})

    def _decode_task(self, upload_id: str) -> None:
        record = self._load_task(upload_id)
        if not record:
            return self._send(404, {"error": "unknown task"})
        err = self._assemble_and_decode(record, force=True)
        if err:
            return self._send(err[0], err[1])
        self._send(200, {"status": record.get("status"), "uploadId": upload_id, "lines": len(record.get("details") or [])})

    def _assemble_and_decode(self, record: dict, force: bool = False):
        if record.get("status") == "done" and not force:
            return None
        upload_id = record["uploadId"]
        items = record.get("files") or []
        assembled_dir = os.path.join(DATA, "files", upload_id)
        if not items:
            record["status"] = "done"
            record["details"] = []
            self._save_task(record)
            return None
        os.makedirs(assembled_dir, exist_ok=True)
        details = []
        used_names = set()
        for item in items:
            if item.get("skip"):
                continue
            path = None
            parts_dir = os.path.join(DATA, "uploads", upload_id, item["fileId"])
            stored = item.get("storedName")
            existing = os.path.join(assembled_dir, stored) if stored else os.path.join(assembled_dir, item.get("name") or "")
            if os.path.isfile(existing) and os.path.getsize(existing) > 0:
                path = existing
            elif os.path.isdir(parts_dir):
                parts = sorted(os.listdir(parts_dir), key=lambda n: int(n.split(".")[0]))
                blob = b"".join(open(os.path.join(parts_dir, p), "rb").read() for p in parts)
                digest = sha256_bytes(blob)
                if item.get("sha256") and digest != item["sha256"]:
                    return 409, {"error": "file sha256 mismatch", "name": item.get("name")}
                name = item.get("name") or (item["fileId"] + ".alog")
                if name in used_names or os.path.isfile(os.path.join(assembled_dir, name)):
                    name = item["fileId"] + "_" + name
                used_names.add(name)
                path = os.path.join(assembled_dir, name)
                with open(path, "wb") as f:
                    f.write(blob)
                item["storedName"] = name
                self._remember_hash(digest, item["fileId"], path)
            if path:
                details.extend(self._decode(path))
        details = sort_detail_rows(details)
        record["status"] = "done"
        record["details"] = details
        record["logDates"] = sorted(task_log_dates(record))
        self._save_task(record)
        return None

    def _decode(self, alog_path: str) -> list:
        try:
            return decode_file(alog_path)
        except Exception as exc:
            return [{"type": "internal", "msg": "decode failed: %s" % exc, "file": os.path.basename(alog_path)}]

    def _remember_hash(self, digest: str, file_id: str, path: str) -> None:
        index_path = os.path.join(DATA, "hash-index.json")
        with lock:
            index = {}
            if os.path.isfile(index_path):
                with open(index_path, encoding="utf-8") as f:
                    index = json.load(f)
            index[digest] = {"fileId": file_id, "path": path}
            with open(index_path, "w", encoding="utf-8") as f:
                json.dump(index, f)

    def _find_file_by_hash(self, digest: str | None) -> str | None:
        if not digest:
            return None
        index_path = os.path.join(DATA, "hash-index.json")
        if os.path.isfile(index_path):
            with open(index_path, encoding="utf-8") as f:
                index = json.load(f)
            hit = index.get(digest)
            if isinstance(hit, dict) and hit.get("path") and os.path.isfile(hit["path"]):
                return hit["path"]
        files_root = os.path.join(DATA, "files")
        if not os.path.isdir(files_root):
            return None
        for upload_id in os.listdir(files_root):
            folder = os.path.join(files_root, upload_id)
            if not os.path.isdir(folder):
                continue
            for name in os.listdir(folder):
                path = os.path.join(folder, name)
                if os.path.isfile(path) and sha256_bytes(open(path, "rb").read()) == digest:
                    return path
        return None

    def _tasks(self, query: dict) -> None:
        union = _query_first(query, "unionId")
        device = _query_first(query, "deviceId")
        from_date = parse_date_param(_query_first(query, "fromDate"))
        to_date = parse_date_param(_query_first(query, "toDate"))
        typ = _query_first(query, "type")
        page, size = parse_page_size(query, default_size=50, max_size=200)
        tasks = []
        tasks_dir = os.path.join(DATA, "tasks")
        if os.path.isdir(tasks_dir):
            for name in os.listdir(tasks_dir):
                if not name.endswith(".json"):
                    continue
                try:
                    with open(os.path.join(tasks_dir, name), encoding="utf-8") as f:
                        task = json.load(f)
                except (OSError, json.JSONDecodeError, UnicodeDecodeError):
                    continue
                meta = task.get("meta") or {}
                if union and meta.get("unionId") != union:
                    continue
                if device and meta.get("deviceId") != device:
                    continue
                dates = task_log_dates(task)
                if from_date or to_date:
                    in_range = [
                        d for d in dates
                        if (not from_date or d >= from_date) and (not to_date or d <= to_date)
                    ]
                    if not in_range:
                        continue
                if typ and not task_matches_type(task, typ):
                    continue
                tasks.append(summarize_task(task, dates))
        tasks.sort(key=lambda t: (t.get("createdAt") or 0, t.get("uploadId") or ""), reverse=True)
        total = len(tasks)
        start = page * size
        self._send(200, {
            "tasks": tasks[start:start + size],
            "total": total,
            "page": page,
            "size": size,
        })

    def _details(self, upload_id: str, query: dict) -> None:
        task = self._load_task(upload_id)
        if not task:
            return self._send(404, {"error": "unknown task"})
        if not task.get("details"):
            self._assemble_and_decode(task, force=True)
            task = self._load_task(upload_id) or task
        rows = filter_detail_rows(task.get("details") or [], query)
        if (query.get("summary") or [""])[0] in ("1", "true"):
            return self._send(200, summarize_rows(rows, query))
        page, size = parse_page_size(query, default_size=200, max_size=2000)
        start = page * size
        items = rows[start:start + size]
        if _serial_enabled(query):
            enriched = []
            for row in items:
                item = dict(row)
                item["serialNotes"] = gs_serial.annotate_notes(to_legacy_line(row))
                enriched.append(item)
            items = enriched
        self._send(200, {"total": len(rows), "items": items, "page": page, "size": size})

    def _details_summary(self, upload_id: str, query: dict) -> None:
        task = self._load_task(upload_id)
        if not task:
            return self._send(404, {"error": "unknown task"})
        if not task.get("details"):
            self._assemble_and_decode(task, force=True)
            task = self._load_task(upload_id) or task
        rows = filter_detail_rows(task.get("details") or [], query)
        self._send(200, summarize_rows(rows, query))

    def _export_txt(self, upload_id: str, query: dict) -> None:
        task = self._load_task(upload_id)
        if not task:
            return self._send(404, {"error": "unknown task"})
        if not task.get("details"):
            self._assemble_and_decode(task, force=True)
            task = self._load_task(upload_id) or task
        rows = filter_detail_rows(task.get("details") or [], query)
        text = rows_to_legacy_txt(rows)
        filename = "%s.txt" % upload_id
        if _serial_enabled(query):
            text = gs_serial.annotate_text(text)
            filename = "%s-serial.txt" % upload_id
        body = text.encode("utf-8")
        self._send(
            200,
            body,
            content_type="text/plain; charset=utf-8",
            extra_headers={"Content-Disposition": 'attachment; filename="%s"' % filename},
        )

    def _export_source(self, upload_id: str) -> None:
        task = self._load_task(upload_id)
        if not task:
            return self._send(404, {"error": "unknown task"})
        self._assemble_and_decode(task, force=False)
        task = self._load_task(upload_id) or task
        paths = self._collect_source_files(task)
        if not paths:
            return self._send(404, {"error": "no source files"})
        if len(paths) == 1:
            path, name = paths[0]
            with open(path, "rb") as f:
                body = f.read()
            return self._send(
                200,
                body,
                content_type="application/octet-stream",
                extra_headers={"Content-Disposition": 'attachment; filename="%s"' % name},
            )
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
            used = set()
            for path, name in paths:
                arc = name
                if arc in used:
                    arc = os.path.basename(path)
                used.add(arc)
                zf.write(path, arcname=arc)
        return self._send(
            200,
            buf.getvalue(),
            content_type="application/zip",
            extra_headers={"Content-Disposition": 'attachment; filename="%s.zip"' % upload_id},
        )

    def _collect_source_files(self, task: dict) -> list:
        upload_id = task.get("uploadId") or ""
        assembled_dir = os.path.join(DATA, "files", upload_id)
        out = []
        seen = set()
        for item in task.get("files") or []:
            path = None
            name = item.get("storedName") or item.get("name") or (item.get("fileId") or "file") + ".alog"
            if item.get("storedName"):
                candidate = os.path.join(assembled_dir, item["storedName"])
                if os.path.isfile(candidate):
                    path = candidate
            if path is None and item.get("name"):
                candidate = os.path.join(assembled_dir, item["name"])
                if os.path.isfile(candidate):
                    path = candidate
            if path is None and item.get("skip"):
                hit = self._find_file_by_hash(item.get("sha256"))
                if hit and os.path.isfile(hit):
                    path = hit
                    name = item.get("name") or os.path.basename(hit)
            if path is None and item.get("fileId"):
                candidate = os.path.join(assembled_dir, item["fileId"] + ".alog")
                if os.path.isfile(candidate):
                    path = candidate
            if path and path not in seen:
                seen.add(path)
                out.append((path, os.path.basename(name)))
        if not out and os.path.isdir(assembled_dir):
            for fname in sorted(os.listdir(assembled_dir)):
                path = os.path.join(assembled_dir, fname)
                if os.path.isfile(path) and path not in seen:
                    seen.add(path)
                    out.append((path, fname))
        return out

    def _create_fetch_task(self) -> None:
        body = json_body(self)
        union = (body.get("unionId") or "").strip()
        if not union:
            return self._send(400, {"error": "unionId required"})
        record = {
            "taskId": "ft-" + uuid.uuid4().hex[:12],
            "status": "pending",
            "unionId": union,
            "deviceId": (body.get("deviceId") or "").strip(),
            "fromMs": body.get("fromMs"),
            "toMs": body.get("toMs"),
            "maxBytes": int(body.get("maxBytes") or 50 * 1024 * 1024),
            "level": body.get("level") or "",
            "createdAt": int(time.time() * 1000),
            "ackedAt": None,
        }
        self._save_fetch(record)
        self._send(200, record)

    def _list_fetch_tasks(self, query: dict) -> None:
        union = (query.get("unionId") or [None])[0]
        device = (query.get("deviceId") or [None])[0]
        status = (query.get("status") or [None])[0]
        tasks = [
            t for t in self._all_fetch_tasks()
            if (not union or t.get("unionId") == union)
            and self._device_matches(t, device)
            and (not status or t.get("status") == status)
        ]
        self._send(200, {"tasks": tasks})

    def _pending_fetch_tasks(self, query: dict) -> None:
        union = (query.get("unionId") or [None])[0]
        device = (query.get("deviceId") or [None])[0]
        if not union:
            return self._send(400, {"error": "unionId required"})
        tasks = [
            t for t in self._all_fetch_tasks()
            if t.get("status") == "pending"
            and t.get("unionId") == union
            and self._device_matches(t, device)
        ]
        self._send(200, {"tasks": tasks})

    def _fetch_ack(self) -> None:
        body = json_body(self)
        task_id = str(body.get("taskId") or "").strip()
        if not task_id:
            return self._send(400, {"error": "taskId required"})
        with lock:
            task = self._load_fetch(task_id)
            if not task:
                return self._send(404, {"error": "unknown taskId"})
            path = os.path.join(DATA, "acks.jsonl")
            with open(path, "a", encoding="utf-8") as f:
                f.write(json.dumps(body) + "\n")
            ok = body.get("ok", True)
            if isinstance(ok, str):
                ok = ok.lower() in ("1", "true", "yes")
            upload_id = str(body.get("uploadId") or "").strip()
            if task.get("status") == "acked":
                if upload_id and not task.get("uploadId"):
                    task["uploadId"] = upload_id
                    self._save_fetch(task)
                return self._send(200, {
                    "ok": True,
                    "taskId": task_id,
                    "status": "acked",
                    "uploadId": task.get("uploadId"),
                    "ackedAt": task.get("ackedAt"),
                    "idempotent": True,
                })
            task["status"] = "acked" if ok else "failed"
            task["ackedAt"] = int(time.time() * 1000)
            if upload_id:
                task["uploadId"] = upload_id
            self._save_fetch(task)
            self._send(200, {
                "ok": True,
                "taskId": task_id,
                "status": task.get("status"),
                "uploadId": task.get("uploadId"),
                "ackedAt": task.get("ackedAt"),
            })

    def _device_matches(self, task: dict, device: str | None) -> bool:
        if not device:
            return True
        task_dev = task.get("deviceId") or ""
        return not task_dev or task_dev == device

    def _save_task(self, record: dict) -> None:
        path = os.path.join(DATA, "tasks")
        os.makedirs(path, exist_ok=True)
        with lock:
            with open(os.path.join(path, record["uploadId"] + ".json"), "w", encoding="utf-8") as f:
                json.dump(record, f)

    def _load_task(self, upload_id: str):
        path = os.path.join(DATA, "tasks", upload_id + ".json")
        if not os.path.isfile(path):
            return None
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, json.JSONDecodeError, UnicodeDecodeError):
            return None

    def _fetch_dir(self) -> str:
        path = os.path.join(DATA, "fetch-tasks")
        os.makedirs(path, exist_ok=True)
        return path

    def _save_fetch(self, record: dict) -> None:
        with lock:
            with open(os.path.join(self._fetch_dir(), record["taskId"] + ".json"), "w", encoding="utf-8") as f:
                json.dump(record, f)

    def _load_fetch(self, task_id: str):
        if not task_id:
            return None
        path = os.path.join(self._fetch_dir(), task_id + ".json")
        if not os.path.isfile(path):
            return None
        try:
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, json.JSONDecodeError, UnicodeDecodeError):
            return None

    def _all_fetch_tasks(self) -> list:
        folder = self._fetch_dir()
        tasks = []
        for name in os.listdir(folder):
            if not name.endswith(".json"):
                continue
            try:
                with open(os.path.join(folder, name), encoding="utf-8") as f:
                    tasks.append(json.load(f))
            except (OSError, json.JSONDecodeError, UnicodeDecodeError):
                continue
        tasks.sort(key=lambda t: t.get("createdAt") or 0, reverse=True)
        return tasks


def _query_first(query: dict, key: str, default=None):
    values = query.get(key) or [default]
    return values[0]


def _serial_enabled(query: dict) -> bool:
    return str(_query_first(query, "serial", "") or "").lower() in ("1", "true", "yes")


DATE_IN_NAME = re.compile(r"(?<!\d)(\d{8})(?!\d)")
_TYPE_ALIASES = {
    "1": "code",
    "code": "code",
    "2": "network",
    "network": "network",
    "3": "action",
    "action": "action",
    "4": "internal",
    "internal": "internal",
}


def parse_date_param(value) -> str | None:
    if value in (None, ""):
        return None
    digits = re.sub(r"\D", "", str(value))
    if len(digits) < 8:
        return None
    stamp = digits[:8]
    try:
        datetime.strptime(stamp, "%Y%m%d")
    except ValueError:
        return None
    return stamp


def extract_yyyymmdd(text: str) -> str | None:
    if not text:
        return None
    match = DATE_IN_NAME.search(str(text))
    return parse_date_param(match.group(1)) if match else None


def yyyymmdd_of_ts(ts) -> str | None:
    try:
        millis = int(ts)
    except (TypeError, ValueError):
        return None
    if millis <= 0:
        return None
    return datetime.fromtimestamp(millis / 1000.0).strftime("%Y%m%d")


def task_log_dates(task: dict) -> list:
    dates = set()
    cached = task.get("logDates")
    if isinstance(cached, list):
        for item in cached:
            parsed = parse_date_param(item)
            if parsed:
                dates.add(parsed)
    for item in task.get("files") or []:
        parsed = parse_date_param(item.get("date")) or extract_yyyymmdd(item.get("name") or "")
        if parsed:
            dates.add(parsed)
    for row in task.get("details") or []:
        parsed = yyyymmdd_of_ts(row.get("ts"))
        if parsed:
            dates.add(parsed)
    if not dates:
        parsed = yyyymmdd_of_ts(task.get("createdAt"))
        if parsed:
            dates.add(parsed)
    return sorted(dates)


def summarize_task(task: dict, dates: list | None = None) -> dict:
    meta = task.get("meta") or {}
    dates = list(dates) if dates is not None else task_log_dates(task)
    files = [x.get("name") for x in task.get("files") or []]
    return {
        "uploadId": task.get("uploadId"),
        "status": task.get("status"),
        "unionId": meta.get("unionId"),
        "deviceId": meta.get("deviceId"),
        "reason": meta.get("reason"),
        "appVer": meta.get("appVer"),
        "createdAt": task.get("createdAt"),
        "logDates": dates,
        "fromDate": dates[0] if dates else None,
        "toDate": dates[-1] if dates else None,
        "files": files,
        "fileCount": len(files),
    }


def normalize_log_type(value) -> str | None:
    if value in (None, ""):
        return None
    key = str(value).strip().lower()
    if not key:
        return None
    if key in _TYPE_ALIASES:
        return _TYPE_ALIASES[key]
    if key.startswith("t") and key[1:].isdigit():
        return "t" + str(int(key[1:]))
    if key.isdigit():
        return "t" + str(int(key))
    return key


def types_match(row_type, wanted) -> bool:
    want = normalize_log_type(wanted)
    if not want:
        return True
    have = normalize_log_type(row_type)
    return have == want


def task_matches_type(task: dict, typ) -> bool:
    want = normalize_log_type(typ)
    if not want:
        return True
    details = task.get("details")
    if not details:
        return True
    return any(types_match(row.get("type"), want) for row in details)


def parse_page_size(query: dict, default_size: int, max_size: int) -> tuple[int, int]:
    try:
        page = int(_query_first(query, "page", "0") or 0)
    except (TypeError, ValueError):
        page = 0
    try:
        size = int(_query_first(query, "size", str(default_size)) or default_size)
    except (TypeError, ValueError):
        size = default_size
    page = max(0, page)
    size = min(max(1, size), max_size)
    return page, size


def to_legacy_line(row: dict) -> str:
    ts = row.get("ts")
    if ts in (None, ""):
        ts = 0
    millis = int(ts)
    dt = datetime.fromtimestamp(millis / 1000.0)
    stamp = dt.strftime("%Y-%m-%d %H:%M:%S") + ".%03d" % (millis % 1000)
    tag = str(row.get("tag") or "ALog")
    msg = str(row.get("msg") or "").replace("\r\n", " ").replace("\n", " ").replace("\r", " ")
    return "%s %s:%s" % (stamp, tag, msg)


def rows_to_legacy_txt(rows: list) -> str:
    return "".join(to_legacy_line(row) + "\n" for row in rows)


def filter_detail_rows(rows: list, query: dict) -> list:
    typ = _query_first(query, "type")
    tag = _query_first(query, "tag")
    q = _query_first(query, "q", "") or ""
    from_ts = _query_first(query, "fromTs")
    to_ts = _query_first(query, "toTs")
    from_date = parse_date_param(_query_first(query, "fromDate"))
    to_date = parse_date_param(_query_first(query, "toDate"))
    from_ms = int(from_ts) if from_ts not in (None, "") else None
    to_ms = int(to_ts) if to_ts not in (None, "") else None
    out = rows
    if typ:
        out = [r for r in out if types_match(r.get("type"), typ)]
    if tag:
        out = [r for r in out if str(r.get("tag")) == tag]
    if q:
        out = [r for r in out if q in json.dumps(r, ensure_ascii=False)]
    if from_ms is not None:
        out = [r for r in out if int(r.get("ts") or 0) >= from_ms]
    if to_ms is not None:
        out = [r for r in out if int(r.get("ts") or 0) <= to_ms]
    if from_date:
        out = [r for r in out if (yyyymmdd_of_ts(r.get("ts")) or "") >= from_date]
    if to_date:
        out = [r for r in out if (yyyymmdd_of_ts(r.get("ts")) or "") <= to_date]
    return sort_detail_rows(out)


def sort_detail_rows(rows: list) -> list:
    return sorted(rows, key=lambda r: (
        int(r.get("ts") or 0),
        str(r.get("tag") or ""),
        str(r.get("msg") or ""),
    ))


def summarize_rows(rows: list, query: dict) -> dict:
    bucket = int(_query_first(query, "bucket", "60000") or 60000)
    if bucket <= 0:
        bucket = 60000
    grouped = {}
    for row in rows:
        ts = int(row.get("ts") or 0)
        key = (ts // bucket) * bucket
        item = grouped.setdefault(key, {"bucketMs": key, "count": 0, "levels": {}})
        item["count"] += 1
        lv = str(row.get("level") or "?")
        item["levels"][lv] = item["levels"].get(lv, 0) + 1
    buckets = [grouped[k] for k in sorted(grouped)]
    return {"bucket": bucket, "buckets": buckets}


def main() -> None:
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    httpd = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print("alog-ingest on http://127.0.0.1:%s" % port)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
