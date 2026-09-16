# 验收

## M1

1. 未 `init` 调用 `ALog.d` 抛 `IllegalStateException`。
2. 同时传入 `AndroidPrinter` 与 `FilePrinter` 时，同一条日志 Logcat 与 decode 后的文件都能看到。
3. 只传其中一个 Printer 时另一通道无输出。
4. 边框/线程/堆栈只出现在 Logcat，不出现在 JSON 字段。
5. 拦截器可丢掉指定 tag；手机号脱敏后文件通道为打码字符串。
6. 主线程 1 万条约 200B，Release 异步无明显掉帧。
7. `adb shell am force-stop com.chyi.alog.sample` 后重启，decode 能解出杀之前 mmap 中的完整明文（封成一块）。不允许整段无法解压。
8. 破坏文件中部 1KB，前后块仍可解；decode 打印坏块偏移且不崩溃。
9. 独占目录 `files/alog/{process}/`，清理不扫目录外文件。

## M3–M5

10. 弱网失败后续传；重复 complete 幂等；hash 秒传。
11. 按用户/设备 + 日期 + type 可查询。
12. 多进程文件不交叉损坏。
13. mmap 失败降级堆内存并 INTERNAL 告警。
14. Release 不注册 AndroidPrinter。

## P0 验证步骤

自动化（JVM unit，无需设备）：

```
./gradlew :alog-upload:testDebugUnitTest --tests com.chyi.alog.upload.protocol.LogUploaderProtocolTest
./gradlew :alog:testDebugUnitTest --tests com.chyi.alog.ALogPrintersTest --tests com.chyi.alog.store.MmapLimitTest.tenThousandApprox200BAppendsReturnQuicklyOnCallerThread
./gradlew :sample:testDebugUnitTest --tests com.chyi.alog.sample.FrameJankStatsTest --tests com.chyi.alog.sample.SampleLogPolicyTest
./gradlew :sample:testReleaseUnitTest --tests com.chyi.alog.sample.SampleLogPolicyTest
```

### 弱网续传

- 单元：`LogUploaderProtocolTest.chunkPutRetriesThenSucceeds`（分片 PUT 失败后指数退避重试）、`failedUploadResumesRemainingChunksWithoutRenegotiate`（已成功分片写入 `ChunkStateStore`，同文件再次 `upload()` 复用 uploadId，跳过已传 index）。
- 设备（可选对照）：连 ingest，上传中断网络后再恢复；服务端同一 `uploadId` 分片齐全后 `complete` 成功。Sample 点「上传日志」。

### complete 幂等

- 单元：`completeIsIdempotent`（成功后再调 `complete` 仍 200/`status=done`，不重复组装）、`completeRetriesThenSucceeds`（complete 短暂失败后重试）。
- 设备（可选）：同一 `POST /logs/uploads/{id}/complete` 连打两次，均 200。

### hash 秒传

- 单元：`hashSkipDoesNotPutChunks`（第一次 PUT+complete 后 hash 入索引；第二次 negotiate `skip=true`，不再 PUT）。
- 设备（可选）：同一 `.alog` 再上传，ingest 返回 `skip=true`，任务 details 不含历史重复行（见 `docs/spec/upload.md`）。

### Release 无 Logcat

- 单元：`ALogPrintersTest.releaseDoesNotIncludeAndroidPrinter`；`:sample:testReleaseUnitTest` 中 `SampleLogPolicy.consoleOnLaunch()==false`，且 `enableAndroidPrinter(true)==false`（Release 即使点「仅控制台」也不注入）。
- 设备：`./gradlew :sample:assembleRelease`，安装 Release APK，启动后不要用 Debug 控制台按钮（Release 已禁用）。打「单条日志」/ burst，然后：

```
adb logcat -c
# 操作 sample
adb logcat -d -s ALog:V ALog:*
```

不应出现 AndroidPrinter 输出（tag=`ALog` 的 Logcat 行）。`ALogBurst` 是抽样结果的 `android.util.Log`，不是 ALog 通道。`ALogInternal` 是 mmap 告警回调，同样不是 AndroidPrinter。

### burst 掉帧抽样

- 单元：`FrameJankStatsTest`（时间戳 → jank/dropped）；`MmapLimitTest.tenThousandApprox200BAppendsReturnQuicklyOnCallerThread`（调用线程 enqueue 应明显快于同步写盘，断言 < 1s）。
- 设备（必做观感）：安装 **Release** sample（无 AndroidPrinter）。点「主线程 1 万条」。界面结果 TextView / toast / `adb logcat -s ALogBurst:I` 打印 `burst10k writeMs=… frames=… jank=… dropped=… maxFrameMs=…`。Release 异步落盘时 `dropped` 应接近 0、`maxFrameMs` 无明显长帧；Debug 双通道会因 Logcat 同步打印而掉帧，不作为本项达标依据。
