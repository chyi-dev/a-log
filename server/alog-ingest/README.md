# alog-ingest

独立进程，不进入 Android `settings.gradle.kts`。

```
pip install -r requirements.txt
python server.py 8080
```

演示 token：`alog-dev`。控制台：打开 http://127.0.0.1:8080/ 。

- 上传任务点「解密显示」会解 `.alog` 并列出明文。
- 未加密 `.alog` 只需标准库；加密 `.alog` 需要 `cryptography`，以及仓库内私钥 `docs/keys/alog_private.pem`（ingest 默认自动读取）。
- 「创建回捞任务」后，在 sample 点「模拟回捞」会拉取 pending、上传并以该 `taskId` 做 fetch-ack。

模拟器访问宿主机用 `http://10.0.2.2:8080`。
