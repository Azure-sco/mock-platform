# SDK 边界

## SDK 负责

- AOP 在业务方法入口建立 `MockContext`，使用 `ThreadLocal<Deque<MockContext>>` 支持嵌套与 `finally` 清理。
- 每次逻辑调用生成一次 `MockRequestId`；同一次底层 Feign Retry 复用，不同业务调用重新生成。
- 从单个不可变 `RoutingSnapshot` 决定 REAL、MOCK 或 CANARY；Snapshot 以 `AtomicReference` 整体替换且版本严格递增。
- Feign 由 `Capability` 注入底层 Client decorator；RestTemplate 由 `ClientHttpRequestFactory` decorator 接管真实传输边界。
- MOCK 路由复制原请求，保留 method、raw path/query、body、content type 和显式允许的业务 Header。
- 默认删除 `Authorization`、`Proxy-Authorization`、`Cookie`、`Set-Cookie`、`X-Api-Key`、`X-App-Secret` 及 signature 类 Header，再写入平台生成的 Mock Header。
- 处理 `FAST_FAIL`、`FALLBACK_RESPONSE` 和严格受限的 `FALLBACK_REAL`。

## SDK 不负责

- 不解析 Contract，不匹配 Scenario，不推进 Flow，不创建 Callback。
- 不读取平台数据库或 Redis，不依赖 Control/Runtime Java 模块。
- 不猜测 Provider/API；二者来自 `@ThirdPartyMock` 建立的可信 Context。
- 不在生产环境接受请求覆盖来强制 Mock；生产快照解析结果强制 REAL。
- 不把 Runtime 作为真实接口代理，也不在“可能已送达”时尝试真实回退。

## FALLBACK_REAL 判定

只有同时满足以下条件才允许：

1. 策略显式配置为 `FALLBACK_REAL`；
2. 当前不是生产环境；
3. 请求可安全重放；
4. 异常可证明发生在连接前；
5. 原始 Host 与精确白名单一致。

Connection reset、read timeout、已有 HTTP 响应或未知异常均按“可能已送达”处理并 fail fast。
