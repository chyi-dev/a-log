# Mars xlog 开发密钥（仅 sample-xlog / 本地 decode，禁止当生产私钥）

## Mars xlog（secp256k1 ECDH + TEA）

- 公钥：`sample-xlog/src/main/assets/xlog_public.hex`（`hex(x)+hex(y)`，128 hex 字符，传给 `Xlog.open`）
- 私钥：`docs/keys/xlog_private.hex`（仅开发机 / `xlog-ingest` decode）
- 重新生成：`python docs/keys/gen_xlog_keys.py`

`sample-xlog`：Debug 默认 nocrypt；Release（`BuildConfig.XLOG_ENCRYPT=true`）传公钥加密。  
`xlog-ingest` 默认读仓库内 `docs/keys/xlog_private.hex`，可用环境变量 `XLOG_PRIVATE_KEY` 覆盖为私钥 hex 字符串或文件路径。
