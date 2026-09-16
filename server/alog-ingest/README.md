# alog-ingest

独立进程，不进入 Android `settings.gradle.kts`。

```
python3 server.py 8080
```

演示 token：`alog-dev`。控制台：打开 http://127.0.0.1:8080/ 。

## 控制台

- 上传任务：按 unionId / deviceId / 日期 / type 检索，分页；点「解密显示」inflate `.alog` 列出明文（仅 zlib）。
- 详情：type / tag / 关键字 / 日期过滤；导出 txt 或源 `.alog`（多文件 zip）。
- 「创建回捞任务」后，sample **点一次**「模拟回捞」：先 `GET /logs/fetch-pending`，仅当确有 pending 才上传，成功后再 `POST /logs/fetch-ack`（带真实 `taskId` + `uploadId`）。无 pending 则跳过（logcat `fetch skipped: no pending task`），不 ack。
- 重复 ack 已 `acked` 的任务返回 200（幂等），未知 taskId 仍 404。`POST /complete` 在 status=done 时同样幂等，避免并行 Worker 409。

模拟器访问宿主机用 `http://10.0.2.2:8080`。真机改 sample `ALogApp.enqueueUpload` 的 `baseUrl` 为电脑局域网 IP。

## 单测

```
python3 -m unittest test_server.py
```
