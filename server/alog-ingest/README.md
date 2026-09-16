# alog-ingest

独立进程，不进入 Android `settings.gradle.kts`。

```
python3 server.py 8080
```

演示 token：`alog-dev`。控制台：打开 http://127.0.0.1:8080/ 。

## 控制台

- 上传任务：按 unionId / deviceId / 日期 / type 检索，分页；点「解密显示」inflate `.alog` 列出明文（仅 zlib）。
- 详情：type / tag / 关键字 / 日期过滤；导出 txt 或源 `.alog`（多文件 zip）。
- 「创建回捞任务」后，sample 点「模拟回捞」会拉 pending、按时间窗上传，并以该 `taskId` + `uploadId` 做 `POST /logs/fetch-ack`。刷新列表后 status 应为 `acked`。

模拟器访问宿主机用 `http://10.0.2.2:8080`。真机改 sample `ALogApp.enqueueUpload` 的 `baseUrl` 为电脑局域网 IP。

## 单测

```
python3 -m unittest test_server.py
```
