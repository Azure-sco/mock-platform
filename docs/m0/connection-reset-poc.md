# Connection Reset PoC

## 目标

Runtime 在完整读取并记录 `/__m0/reset` 请求后，提交部分 HTTP/1.1 响应并中断连接。此时请求可能已经产生副作用：若客户端抛出传输异常，SDK 必须把它分类为 `POSSIBLY_DELIVERED`；若客户端接受部分响应，也不得访问真实目标。

## 实现

- Runtime 显式使用 Reactor Netty，Response 声明比实际写入更长的 Content-Length，再以 `IOException` 结束已提交响应。
- JDK 8 Failsafe 在 JDK 8 JVM 中运行 Sample，并使用 toolchains 中的 JDK 17 启动打包后的真实 Runtime 进程。
- JDK 17 E2E 同时启动 Fake Real、真实 Reactor Netty Runtime 和 Sample context。
- Fake Real 提供同路径 sentinel；测试以调用计数断言它没有被访问。

## 客户端矩阵

| 客户端 | 观察结果 | Runtime 已接收 | Fake Real 调用 | 结论 |
|---|---|---:|---:|---|
| JDK 8 OpenFeign（默认 `HttpURLConnection`） | 返回部分 body `runtime-received` | 是 | 0 | 不回退 |
| JDK 8 RestTemplate（默认 `HttpURLConnection`） | 返回部分 body `runtime-received` | 是 | 0 | 不回退 |
| JDK 17 OpenFeign（默认 `HttpURLConnection`） | 返回部分 body `runtime-received` | 是 | 0 | 不回退 |
| JDK 17 RestTemplate（默认 `HttpURLConnection`） | 返回部分 body `runtime-received` | 是 | 0 | 不回退 |

独立的 `RuntimeM0IntegrationTest` 还使用 Reactor Netty `HttpClient` 证明客户端观察到连接级失败，并断言 Runtime 已记录 Request ID。日志中“response already committed”是本 PoC 的预期服务端信号。

这项实测说明不能假定底层客户端一定会把 Content-Length 不完整转换为 I/O 异常。生产调用仍需按既有契约校验或反序列化响应；若部分 body 导致解码失败，该失败发生在请求可能已经送达之后，同样不得自动回退 REAL。

## 边界

PoC 只证明 HTTP/1.1 Reactor Netty 与当前四类客户端组合。代理、Service Mesh、HTTP/2 或不同底层 Client 可能改变异常形态，新增组合必须重新实测。无论异常类如何变化，无法证明发生在连接前时都必须 fail fast。
