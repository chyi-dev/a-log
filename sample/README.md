# ALog sample

## Release APK（QA）

Release 使用 **debug keystore** 签名，便于本机/模拟器直接安装：

```
./gradlew :sample:assembleRelease
adb install -r sample/build/outputs/apk/release/sample-release.apk
```

无需再手动 re-sign。仅演示用，不要当生产签名。

## Burst 掉帧抽样

安装 Release 后点「主线程 1 万条」。看界面结果或：

```
adb logcat -s ALogBurst:I
```

模拟器达标带：`writeMs` 宜 < 32ms，Choreographer `dropped` 为 0 或 1，`maxFrameMs` < 32ms。日志中 `mmapDropped` 应为 0（1 万条全部入队）。Debug 双通道会因 Logcat 同步打印掉帧，不以 Debug 为准。
