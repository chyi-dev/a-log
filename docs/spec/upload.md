# 上传协议

客户端实现在 **`:alog-upload`**，不打进 `:alog`。只读已封块的 `.alog` 原始字节，不解密、不解压。解码在 ingest 或 `:alog-decode`。

业务接入：

```kotlin
implementation(project(":alog"))
implementation(project(":alog-upload"))

ALogUpload.enqueue(context, UploadConfig(logDir, cacheDir, baseUrl, token, meta), reason)
```

`reason`：`manual`（允许蜂窝）/ `fetch`（默认非计费网络）/ 预留 `crash`。用户同意前不得上传。

上传前由 `:alog` 的 `ALog.prepareForUpload` 做 flush 与死进程 mmap 回收。选文件：仅 `.alog`；`logRoot` + 一级子目录；按修改时间新到旧；默认最近 2 天（`recentDays=null` 关闭窗口）；累计不超过 50MB，超出则 `truncated=true`。不上传 `.mm`。

Base URL 由客户端配置，sample 默认同网段 `http://10.0.2.2:8080`（模拟器）。

鉴权：Header `Authorization: Bearer <token>`，内网演示 token 为 `alog-dev`。

## POST /logs/uploads

Body JSON：

```json
{
  "appId": "com.chyi.alog.sample",
  "unionId": "user-1",
  "deviceId": "dev-1",
  "appVer": "1.0",
  "buildVer": "1",
  "platform": "android",
  "reason": "manual|fetch",
  "formatVersion": 1,
  "maxBytes": 52428800,
  "truncated": false,
  "files": [
    { "name": "alog_20260817_0.alog", "size": 1234, "sha256": "...", "date": "20260817" }
  ]
}
```

Return：

```json
{
  "uploadId": "u-...",
  "chunkSize": 2097152,
  "files": [
    { "fileId": "f-...", "name": "...", "sha256": "...", "skip": false }
  ]
}
```

`skip=true` 表示秒传（hash 已存在）。秒传文件不会写入该 uploadId 的 `details`（避免重复展示历史日志）；查看旧内容请打开对应历史任务。

## PUT /logs/uploads/{uploadId}/files/{fileId}/chunks/{index}

- Header：`Content-SHA256`（该分片 sha256 hex）、`Content-Range: bytes start-end/total`
- Body：原始字节
- 幂等：同一 index 重复 PUT 覆盖并返回 200

## POST /logs/uploads/{uploadId}/complete

校验分片齐全与文件 sha256，入队解码。重复 complete 返回已完成状态。

## POST /logs/fetch-ack

```json
{ "taskId": "...", "ok": true, "uploadId": "u-..." }
```

`taskId` 必填，必须是已存在的回捞任务。`ok=true` 将任务标为 `acked`，`ok=false` 标为 `failed`。`uploadId` 可选，写入任务以便控制台跳到对应上传详情。未知 `taskId` 返回 404。

客户端（`:alog-upload` `UploadWorker`）在 `reason=fetch` 时先 `GET /logs/fetch-pending`，再按任务的 `fromMs`/`toMs`/`maxBytes` 选文件上传，成功后带上本次 `uploadId` ack。没有 pending 任务则不上报、不 ack（不再使用占位 `sample-fetch`）。

## 错误码

- 401 鉴权失败
- 404 未知 uploadId/fileId
- 409 sha256 不匹配
- 413 超过 maxBytes

## 查询（M4）

- `GET /logs/tasks?unionId=&deviceId=&fromDate=&toDate=&type=&page=&size=`
  - `fromDate`/`toDate`：`YYYYMMDD` 或 `YYYY-MM-DD`，按任务日志日（文件名中的日期 / 文件 `date` / 明细 ts）过滤
  - `type`：`code|network|action|internal|t10` 或数字 `1..4`/`10`，只保留含该 type 明细的任务（尚未 decode 的任务仍会列出）
  - 分页：`page` 从 0，`size` 默认 50、最大 200；返回 `{ tasks, total, page, size }`，按 `createdAt` 新到旧
- `GET /logs/tasks/{taskId}/details?type=&tag=&q=&fromDate=&toDate=&page=&size=`
  - `type`/`tag`/`q` 过滤明文行；`type` 同时接受名称与数字
  - `fromDate`/`toDate` 按行 `ts` 的日历日过滤（仍可用 `fromTs`/`toTs`）
  - 分页：`page` 从 0，`size` 默认 200、最大 2000
- `GET /logs/tasks/{taskId}/export.txt?type=&tag=&q=&fromDate=&toDate=` — 明文 txt（过滤与详情一致）
- `GET /logs/tasks/{taskId}/export.source` — 源 `.alog`（单文件原样；多文件 zip）
