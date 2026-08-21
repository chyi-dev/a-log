# ALog / Mars xlog 开发密钥（仅 sample / 本地 decode，禁止当生产私钥）

## ALog（RSA + AES-GCM）

- 公钥：`sample/src/main/assets/alog_public.pem`（客户端内置）
- 私钥：`docs/keys/alog_private.pem`（仅开发机 / ingest decode / 明文查看 App）

```
./gradlew :alog-decode:run --args="--key docs/keys/alog_private.pem path/to/file.alog"
```

后续明文日志 App 只依赖 `:alog-decode`：

```kotlin
AlogDecoder.decode(file, privateKeyPem)
```

## Mars xlog（secp256k1 ECDH + TEA）

- 公钥：`sample-xlog/src/main/assets/xlog_public.hex`（`hex(x)+hex(y)`，128 hex 字符，传给 `Xlog.open`）
- 私钥：`docs/keys/xlog_private.hex`（仅开发机 / `xlog-ingest` decode）
- 重新生成：`python docs/keys/gen_xlog_keys.py`

`sample-xlog`：Debug 默认 nocrypt；Release（`BuildConfig.XLOG_ENCRYPT=true`）传公钥加密。  
`xlog-ingest` 默认读仓库内 `docs/keys/xlog_private.hex`，可用环境变量 `XLOG_PRIVATE_KEY` 覆盖为私钥 hex 字符串或文件路径。
