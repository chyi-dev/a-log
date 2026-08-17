# 文件格式 ALG1 / ALGF

## 文件头（每个 `.alog` 文件开头）

| 字段 | 大小 | 说明 |
| --- | --- | --- |
| magic | 4 | ASCII `ALGF` |
| version | 1 | 当前为 1 |
| flags | 1 | bit0=含封装 DEK |
| keyIdLen | 2 | 大端 |
| keyId | keyIdLen | UTF-8，未加密时为空 |
| wrappedDekLen | 2 | 大端 |
| wrappedDek | wrappedDekLen | RSA-OAEP 封装的 AES-256 DEK；未加密时长度为 0 |

随后为 0..n 个日志块。

## 日志块

| 字段 | 大小 | 说明 |
| --- | --- | --- |
| magic | 4 | ASCII `ALG1` |
| version | 1 | 当前为 1 |
| flags | 1 | bit0=deflate 压缩；bit1=AES-256-GCM 加密 |
| seq | 4 | 大端，文件内块序号 |
| unix_ms | 8 | 大端，块内首条时间 |
| payload_len | 4 | 大端，payload 字节数 |
| nonce | 0 或 12 | 仅 flags.bit1=1 时存在，GCM nonce |
| payload | payload_len | 压缩（再可选加密）后的数据 |
| crc32 | 4 | 大端，覆盖 magic 起至 payload 末（不含 crc 自身） |

字节序一律大端。

## 压缩与 mmap

mmap 文件 `{prefix}.mm` 固定 150KB，存放**尚未封块的明文 JSON lines**（每行一条，`\n` 分隔）。封块时对整段明文 `Deflater` **finish** 得到完整 zlib 流，再按 flags 加密，写出一个 ALG1 块后清空 mmap。

禁止跨生命周期的半截 gzip。AES 余数不得只留在堆内存：加密发生在封块瞬间，密文进入块 payload。

## 损坏扫描

解码器从偏移 0 起找 `ALG1`。CRC 失败或 length 越界则从 magic+1 继续扫描，跳过坏块并记录偏移，不中止整个文件。

## mmap 布局

| 偏移 | 大小 | 说明 |
| --- | --- | --- |
| 0 | 4 | ASCII `ALMM` |
| 4 | 1 | version=1 |
| 5 | 3 | 保留 |
| 8 | 4 | used，body 有效字节 |
| 12 | 8 | firstTs |
| 20 | used | UTF-8 JSON lines |

启动时若 `used>0` 且 magic 合法，先把 body 封成一块写入当天文件，再接受新日志。
