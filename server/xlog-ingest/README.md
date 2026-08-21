# xlog-ingest

独立进程，对照 ALog 的 `alog-ingest`。默认端口 **8081**，token `xlog-dev`。

```bash
pip install -r requirements.txt
python server.py 8081
```

控制台：http://127.0.0.1:8081/

## 与 ALog 对照清单

1. 分别安装 `sample`（ALog）与 `sample-xlog`（Mars xlog）。
2. 启动 `alog-ingest:8080` 与 `xlog-ingest:8081`。
3. 两边各点「单条 / 主线程 1 万条 / 极限压测 / :push 写日志」，再 flush。
4. 对照 toast 耗时与文件体积（xlog 报 `fileSize`；ALog 报 `dropped`）。
5. 各自点「上传日志」，在对应控制台解密查看条数与内容。
6. force-stop：先写日志不 flush，`adb shell am force-stop <pkg>`，重开后上传，确认 mmap 残留可解。

模拟器访问宿主机：`http://10.0.2.2:8081`。
