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

模拟器达标带：`writeMs` 宜为数十毫秒或更低（远低于一帧 16ms 的数倍即可），`dropped` 接近 0，`maxFrameMs` 无明显长帧（例如 < 32ms）。Debug 双通道会因 Logcat 同步打印掉帧，不以 Debug 为准。
