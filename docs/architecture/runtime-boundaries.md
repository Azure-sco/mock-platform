# Runtime 边界

## 当前实现

Runtime 是 Spring Boot 3 + WebFlux 服务，并显式选择 Reactor Netty，避免测试或宿主 Classpath 中的 Servlet 容器改变服务实现。入口接受任意业务 path，但要求可信身份及显式 `X-Mock-Provider`、`X-Mock-Api`。

处理顺序：

1. 验证 `MockApp` token，服务端派生 `appCode/environment`；
2. 校验 Provider/API，并获得或生成 `X-Mock-Request-Id`；
3. 在 1 MiB 上限内读取 Body；
4. 记录不含凭证与正文的 M0 安全元数据；
5. 由 `M0FixedResponseDispatcher` 返回四个固定 Fixture；
6. 在 Response Header 回传同一 Request ID。

`/__m0/reset` 是专用 PoC 端点：Runtime 读取并记录请求后故意中断响应，用于证明“可能已送达”时 SDK 不会回退真实接口。它不是生产故障注入 API。

## 明确不做

- Runtime 不访问真实第三方 URL。
- M0 不读取数据库 Scenario，不实现 Match、Template、Fault、Flow、Callback 或 RequestLog。
- Runtime 不信任 `X-Mock-App` 等自报身份，不保存 Authorization 值、Cookie、签名或完整业务 Body。
- 阻塞 JDBC 不得在 Reactor event loop 执行；M1 若引入查询，必须显式切换到已定义的有界 Scheduler。

## 身份模式

- `local`/`test`：`LocalMockAppVerifier` 使用配置中的测试 token 映射。
- 其他 Profile：`FailClosedMockAppVerifier` 拒绝所有请求。
- 正式 Service Identity/mTLS 尚未接入，见 `docs/m0/external-dependencies.md`。
