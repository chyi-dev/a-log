# ALog 开发密钥（仅 sample / 本地 decode，禁止当生产私钥）

- 公钥：`sample/src/main/assets/alog_public.pem`（客户端内置）
- 私钥：`docs/keys/alog_private.pem`（仅开发机 / ingest decode / 明文查看 App）

```
./gradlew :alog-decode:run --args="--key docs/keys/alog_private.pem path/to/file.alog"
```

后续明文日志 App 只依赖 `:alog-decode`：

```kotlin
AlogDecoder.decode(file, privateKeyPem)
```
