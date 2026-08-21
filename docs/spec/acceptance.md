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
