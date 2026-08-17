# 上传协议

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
  "reason": "manual|fetch|crash",
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

`skip=true` 表示秒传（hash 已存在）。

## PUT /logs/uploads/{uploadId}/files/{fileId}/chunks/{index}

- Header：`Content-SHA256`（该分片 sha256 hex）、`Content-Range: bytes start-end/total`
- Body：原始字节
- 幂等：同一 index 重复 PUT 覆盖并返回 200

## POST /logs/uploads/{uploadId}/complete

校验分片齐全与文件 sha256，入队解码。重复 complete 返回已完成状态。

## POST /logs/fetch-ack

```json
{ "taskId": "...", "ok": true }
```

## 错误码

- 401 鉴权失败
- 404 未知 uploadId/fileId
- 409 sha256 不匹配
- 413 超过 maxBytes

## 查询（M4）

- `GET /logs/tasks?unionId=&deviceId=&fromDate=&toDate=`
- `GET /logs/tasks/{taskId}/details?type=&q=&page=&size=`
