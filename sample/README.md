# ALog sample

## ingest URL

默认 `http://10.0.2.2:8080`（Android 模拟器访问宿主机）。真机请改 `ALogApp.enqueueUpload` 里的 `baseUrl` 为电脑 IP，并保证与 ingest 同网。Token：`alog-dev`。unionId：`demo-user`。

## 上传 / 回捞

1. 宿主机：`cd server/alog-ingest && python3 server.py 8080`，浏览器打开 http://127.0.0.1:8080/ 。
2. Sample 点「单条日志」或「主线程 1 万条」，再点「上传日志」（`reason=manual`）。
3. 控制台「加载上传任务」，点「解密显示」看明文；「下载 txt / 源文件」验证导出。
4. 回捞：**只创建一条** pending → sample **点一次**「模拟回捞」→ ingest 日志必须有 `complete` **和** `POST /logs/fetch-ack`，控制台刷新 `acked` + uploadId。不要在 ~30s 内看到第二次 `POST /logs/uploads`。
   无 pending 再点一次：`adb logcat -s ALogUpload:I` 见 `fetch skipped: no pending task`，ingest 无新的 `/logs/uploads` 或 `/logs/fetch-ack`。
   验证 ack 独立重试：complete 之后立刻断网，应出现 `fetch ack failed:`（不是 `fetch pending lookup failed`）；恢复网络后只有 ack、没有第二次上传。
   不要连点「上传日志」和「模拟回捞」；两者共用唯一工作队列 `alog-upload`，会串行。

## Release APK（QA）

Release 使用 **debug keystore** 签名，便于本机/模拟器直接安装：

```
./gradlew :sample:assembleRelease
adb install -r sample/build/outputs/apk/release/sample-release.apk
```

无需再手动 re-sign。仅演示用，不要当生产签名。

## Burst 掉帧抽样

安装 Release 后点「主线程 1 万条」（主线程只 **一次** `ALog.i(10000) { … }` batch 提交，不再在 UI 上 for 1 万次）。看界面结果或：

```
adb logcat -s ALogBurst:I
```

字段：`writeMs` = 这次 batch 入队耗时（不是 1 万次 UI 循环）；`mmapDropped` = mmap 队列丢行（应为 0）；`mode=batch`；`dropped` / `maxFrameMs` = Choreographer 掉帧。模拟器达标带：`writeMs` &lt; 32ms，`dropped` 为 0 或 1，`maxFrameMs` &lt; 32ms，`mmapDropped=0`。Debug 双通道会因 Logcat 同步打印掉帧，不以 Debug 为准。
