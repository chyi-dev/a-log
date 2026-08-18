# ALog API

门面类：`com.chyi.alog.ALog`。必须先 `init`，否则打日志抛 `IllegalStateException`。

## 初始化

```kotlin
ALog.init(config: LogConfiguration, vararg printers: Printer)
```

每个 Printer 都会收到同一条（经拦截器处理后的）`LogItem`。不传入的通道不会有输出。

- Debug 默认：`AndroidPrinter` + `FilePrinter`
- Release 默认：仅 `FilePrinter`

## LogConfiguration.Builder

| 方法 | 含义 |
| --- | --- |
| `logLevel(Int)` | 低于此级别丢弃。`LogLevel.ALL..NONE` |
| `tag(String)` | 默认 tag，默认 `"ALog"` |
| `enableThreadInfo()` | 仅 `AndroidPrinter` 展示线程名 |
| `enableStackTrace(depth)` | 仅 `AndroidPrinter` 展示调用栈 |
| `enableBorder()` | 仅 `AndroidPrinter` 画边框 |
| `addInterceptor(Interceptor)` | 可改写或丢弃 `LogItem`（返回 null 即丢弃） |

装饰字段禁止写入 `.alog` 文件。

## 打日志

```
ALog.v/d/i/w/e/f(msg)
ALog.v/d/i/w/e/f(tag, msg)
ALog.v/d/i/w/e/f(msg, throwable)
ALog.t(LogType.NETWORK).e(tag, msg)
ALog.tag("Http").d("ok")
ALog.flush(sync = true)
```

`t(type)` 设置业务类型，不是 tag。

## LogType

- 1 `CODE`（默认）
- 2 `NETWORK`
- 3 `ACTION`
- 4 `INTERNAL`（框架告警）
- 业务自定义从 10 起

## Printer

```kotlin
interface Printer {
    fun println(item: LogItem)
    fun flush(sync: Boolean) {}
    fun attach(config: LogConfiguration) {}
}
```

## Interceptor

```kotlin
fun intercept(item: LogItem): LogItem?
```

顺序：按 `addInterceptor` 注册顺序执行。任一返回 null 则不再往后、不打印。

## 默认值

- mmap 150KB
- 单条 16KB（超出截断并打 INTERNAL 告警）
- 单文件 8MB，按天 + seq
- 保留 7 天且总容量 64MB
- 刷盘：明文约 50KB 或 mmap 约 1/3、Fatal、进后台、`flush(sync)`、上传前
