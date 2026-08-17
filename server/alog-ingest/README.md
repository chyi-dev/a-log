# alog-ingest

独立进程，不进入 Android `settings.gradle.kts`。

```
python server.py 8080
```

演示 token：`alog-dev`。控制台：打开 http://127.0.0.1:8080/ 。

- 上传任务点「解密显示」会解 `.alog` 并列出明文。
- 「创建回捞任务」后，在 sample 点「模拟回捞」会拉取 pending、上传并以该 `taskId` 做 fetch-ack。

模拟器访问宿主机用 `http://10.0.2.2:8080`。
