# ALog sample

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
