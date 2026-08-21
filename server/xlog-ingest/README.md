# xlog-ingest

独立进程，对照 ALog 的 `alog-ingest`。默认端口 **8081**，token `xlog-dev`。

```bash
pip install -r requirements.txt
python server.py 8081
```

控制台：http://127.0.0.1:8081/

## 加密

- **Debug**：`BuildConfig.XLOG_ENCRYPT=false`，`Xlog.open` 公钥为空 → nocrypt（只压缩）。
- **Release**：`XLOG_ENCRYPT=true`，客户端读 `sample-xlog/src/main/assets/xlog_public.hex`。
- **解码私钥**：默认 `docs/keys/xlog_private.hex`；可用环境变量 `XLOG_PRIVATE_KEY`（hex 或文件路径）覆盖。
- 重新生成密钥：`python docs/keys/gen_xlog_keys.py`（开发钥，禁止当生产钥）。
- 本地验证加密：在 [`sample-xlog/build.gradle.kts`](../../sample-xlog/build.gradle.kts) 把 debug 的 `XLOG_ENCRYPT` 临时改为 `"true"`，打日志上传后在控制台「解密显示」。

## 与 ALog 对照清单

1. 分别安装 `sample`（ALog）与 `sample-xlog`（Mars xlog）。
2. 启动 `alog-ingest:8080` 与 `xlog-ingest:8081`。
3. 两边各点「单条 / 主线程 1 万条 / 极限压测 / :push 写日志」，再 flush。
4. 对照 toast 耗时与文件体积（xlog 报 `fileSize`；ALog 报 `dropped`）。
5. 各自点「上传日志」，在对应控制台解密查看条数与内容。
6. force-stop：先写日志不 flush，`adb shell am force-stop <pkg>`，重开后上传，确认 mmap 残留可解。
7. 加密对照：Release（或强制 `XLOG_ENCRYPT=true`）写日志并上传；控制台能解出明文；去掉/换错 `xlog_private.hex` 时应看到 decode 失败信息。

模拟器访问宿主机：`http://10.0.2.2:8081`。
