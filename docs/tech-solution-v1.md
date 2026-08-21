# 巡天第三方接口 Mock 平台 MVP 技术方案 V1.1（评审修订版）

> 文档状态：独立架构复审通过（PASS：Critical 0 / Important 0 / Minor 0，2026-08-16），可进入 M0 外部依赖确认
> 适用范围：OA、e 签宝、共享平台等第三方 HTTP/HTTPS 接口
> 目标：以一个季度内可交付、可联调、可维护为约束，完成真正可投入团队测试的 MVP
> 替代关系：本文件用于替代原 `v1.1.md` 的实施设计；原需求文档仍是需求来源，范围冲突以本文件“需求基线”章节为准

---

## 1. 结论

本项目技术上可行，但 MVP 必须控制为“受限能力的第三方 HTTP Mock 平台”，不能同时建设通用 Mock 引擎、工作流平台、AI 平台和流量治理平台。

MVP 的核心闭环为：

```text
业务服务
  -> SDK 判断 REAL / MOCK / CANARY
  -> Runtime 认证、选择 Release、匹配 Scenario
  -> Runtime 原子推进 Flow
  -> Runtime 渲染受限模板并返回 HTTP 响应
  -> Runtime 创建 Callback Task、记录调用日志

测试人员
  -> Web Console 创建 Contract / Scenario
  -> Validate
  -> 审批
  -> 发布不可变 Release
  -> MySQL 权威切换 Active Release，Runtime 集群有界收敛
  -> 可回滚到历史 Release
```

MVP 的首要成功标准不是功能数量，而是以下能力稳定闭环：

1. 老巡天 JDK8 和新巡天 JDK17 都能接入。
2. 业务代码不出现 `if mock` 分支。
3. REAL/MOCK/CANARY 可动态切换，切换不重启。
4. 场景匹配确定、隔离正确、发布可回滚。
5. 同一业务单号的状态和回调可重复复现。
6. Mock 不可用或未匹配时绝不静默访问真实生产接口。
7. 管理操作、审批、发布和回滚可审计。

---

## 2. 需求基线与范围说明

### 2.1 本版本承诺范围

#### SDK

- `@ThirdPartyMock(provider, api)` 注解。
- JDK8 Legacy Starter、JDK17 Boot3 Starter。
- Feign、RestTemplate。
- REAL、MOCK、CANARY。
- Apollo、Nacos 动态刷新。
- TraceId、MockRequestId、ScenarioId、ReleaseId 透传。
- `FAST_FAIL`、受限 `FALLBACK_REAL`、`FALLBACK_RESPONSE`。
- MOCK 请求剥离真实 Token、Cookie、AppSecret、第三方 Signature。

#### Contract 与 Scenario

- Provider、API、Contract Version 管理。
- JSON Schema 导入。
- OpenAPI 3.0 文件导入的最小能力：解析 Path、Method、Schema 和 Example，生成草稿，不支持在线编辑器。
- 固定响应、受限模板响应。
- Header、Query、JSONPath、Regex、BusinessNo、显式场景匹配。
- 环境、应用、租户、测试账号、有效期隔离。
- 场景优先级、冲突检查、版本化。
- HTTP Status、Delay、Read Timeout、Connection Reset、HTTP Error。

#### Flow 与 Callback

- INIT、PROCESSING、SUCCESS、FAILED、REJECTED、TIMEOUT、CANCELLED。
- QUERY_COUNT、TIME、REQUEST_FIELD、MANUAL 状态推进。
- Flow 按环境、应用、Provider、FlowCode、租户、测试账号、业务键隔离，参与 API 作为事件来源。
- Flow TTL、查询、事件、人工推进、重置和清理。
- Callback 延迟、失败重试、主动重复、简单乱序、签名。
- Callback URL 白名单和任务可恢复。

#### 管理面

- Vue3 + TypeScript + Vite + Element Plus。
- Provider/API、Contract、Scenario、Flow、Callback、Request Log、Release、Audit 页面。
- 巡天统一 SSO/RBAC。
- Draft、Validate、Approval、Publish、Rollback 生命周期。
- 可配置双人复核。

#### 非功能

- 常规无故意延迟请求 P95 小于 50ms，按明确压测口径验收。
- 测试环境目标可用性 99.9%，依赖公司 MySQL、Redis 和容器平台高可用能力。
- Runtime 无会话状态，可水平扩展；Flow 等业务状态存储在外部存储。
- 请求/响应脱敏、SSRF 防护、模板无任意代码执行能力。

### 2.2 明确后置

以下能力不进入本 MVP：

```text
AI Agent
Request Replay
测试报告导出
Forest
WebClient
MQ Mock
文件协议 Mock
Dubbo Mock
流量录制
契约漂移检测
复杂多分支工作流
BPMN / 拖拽工作流
任意 JavaScript / Groovy / SpEL / Velocity
低代码表单引擎
生产真实流量代理
```

说明：原需求对 AI 的 MVP 归属存在冲突，一处将其列入 MVP，版本规划又将其放到 V1.2。本方案按“AI 属于 V1.2”执行。若产品确认 AI 必须进入首版，需要单独增加人力和里程碑，不能挤占运行链路的交付时间。

### 2.3 MVP Exit 与试点范围不同

为降低一次性交付风险，分为两层验收：

```text
Pilot Exit：
  老巡天 1 个接口
  新巡天 1 个接口
  OA / e签宝 / 共享平台各 1 个样板接口
  完成固定响应、发布、回滚、Flow、Callback 闭环

MVP Exit：
  OA / e签宝 / 共享平台各至少 3 个核心接口
  完成本文件全部 P0 验收项
```

Pilot 通过前，不批量接入 9 个业务接口。

### 2.4 原需求追踪

| 原需求域 | MVP 处理 | 说明 |
|---|---|---|
| SDK Starter、注解、REAL/MOCK/CANARY | 纳入 | Feign、RestTemplate 首发 |
| WebClient、Forest | 后置 | 不影响原需求中 Feign/RestTemplate 的 MVP 接入闭环 |
| Apollo/Nacos 动态刷新 | 纳入 | 使用不可变 RoutingSnapshot |
| Provider/API/Contract | 纳入 | 包含最小 OpenAPI 3.0/JSON Schema 文件导入和版本 Diff |
| 固定/模板响应、匹配、Scope、有效期 | 纳入 | 模板改为受限白名单 DSL |
| 延迟、超时、连接重置、限流、错误码 | 纳入 | 第三方限流通过 HTTP 429 + Retry-After 场景表达；平台限流单独处理 |
| 状态机与回调 | 纳入 | 包含查询次数、时间、请求字段、人工推进、重复、乱序、签名 |
| 调用记录和 TTL | 纳入 | 回放和报告导出后置 |
| AI Agent | 后置 V1.2 | 解决原需求 MVP 与版本规划之间的冲突 |
| SSO/RBAC、审批、审计、回滚 | 纳入 | 配置中心开关审计由配置中心记录与 SDK 生效事件共同闭环 |
| 99.9%、P95 < 50ms | 纳入验收 | 采用明确依赖条件和压测口径，不作为无条件承诺 |

MVP 不等同于完整需求终态。以上后置项必须登记到产品 Backlog，不能在验收时被误认为遗漏，也不能在开发过程中未经评估临时塞回 MVP。

---

## 3. 架构决策

### 3.1 总体架构

```text
┌────────────────────────────────────────────────────────────┐
│                    Mock Web Console                        │
│ Vue3 / TypeScript / Vite / Element Plus                   │
└──────────────────────────────┬─────────────────────────────┘
                               │ /api/admin/**
                               ▼
┌────────────────────────────────────────────────────────────┐
│                    Mock Control Plane                      │
│ Provider / API / Contract / Scenario / Approval / Release │
│ Flow Admin / Timer Worker / Callback Worker / Query / Audit│
└──────────────┬───────────────────────────┬─────────────────┘
               │                           │
               ▼                           ▼
          MySQL HA                     Redis HA
        业务事实来源              Active Release / Cache / RateLimit
               ▲                           ▲
               │                           │
┌──────────────┴───────────────────────────┴─────────────────┐
│                     Mock Runtime                           │
│ Auth / Route / Match / Flow / Template / Fault / Logging  │
│ Spring Boot 3 + WebFlux + Reactor Netty                   │
└──────────────────────────────▲─────────────────────────────┘
                               │ MOCK
                 ┌─────────────┴─────────────┐
                 │                           │
       JDK8 Legacy Starter          JDK17 Boot3 Starter
                 │                           │
                 └────── REAL / MOCK / CANARY ──────┐
                                                    │
                                 REAL ──────────────┴──> 真实第三方
```

### 3.2 ADR-001：Runtime 是唯一场景决策点

Runtime 对以下结果拥有唯一决定权：

- 使用哪个 Active Release；
- 命中哪个 Scenario Version；
- Flow 当前状态和本次状态迁移；
- 本次返回内容和故障行为；
- 是否创建 Callback Task；
- 是否返回 `MOCK_NO_MATCH`。

不允许另一个 HTTP Mock Engine 再次根据原始请求做业务场景匹配，否则会出现 Release、Scenario 和 Flow 三者不一致。

### 3.3 ADR-002：MVP 主链路不使用 MockServer

原方案将 MockServer 放在 Runtime 后方，但这会引入以下问题：

1. Runtime 在推进 Flow 前无法确定 MockServer 最终命中的 Scenario。
2. MockServer 模板默认拿不到 Runtime 内的 Flow State。
3. MockServer 对 Runtime 连接执行 Reset 时，业务 SDK 只会看到 Runtime 转换后的网关错误。
4. 增加一次网络跳转，不利于 P95。
5. 原生 Velocity/JavaScript 模板与“禁止任意脚本执行”的安全目标冲突。

因此 MVP 由 Runtime 实现严格受限的 HTTP Mock 能力：

```text
有限 Match Rule
有限变量模板
Status / Header / Body
非阻塞 Delay / Timeout
Netty Connection Reset
Flow / Callback
```

这不是建设通用 MockServer。范围以本文件定义的 DSL 为边界，不接受任意脚本、代理转发、录制、插件执行和自定义代码。

Runtime 内部保留清晰的响应执行边界；MVP 不为尚未使用的外部引擎提前建立独立 SPI 模块。后续如确有纯无状态协议场景，再提取 MockServer/WireMock Adapter。

### 3.4 ADR-003：MySQL 是可恢复业务状态的事实来源

MVP 流量来自测试环境，正确性和可恢复性优先于极限吞吐。

Active Release、Activation、Outbox、Flow Instance、Flow Event、Request Execution、Callback Task 使用 MySQL。Flow 请求在同一事务中完成：

```text
按 (app, mockRequestId) 锁定或创建 Request Execution
  -> 未过期且指纹相同的 COMPLETED 命中直接复用，不再锁 Flow
  -> 需要执行时 SELECT Flow Instance ... FOR UPDATE
  -> 更新 queryCount / state / version
  -> 写入 Flow Event
  -> 创建 Callback Task
  -> 保存响应或故障决策
  -> 提交
```

这样避免“状态已变化，但请求返回结果或回调任务没有持久化”的跨存储双写问题。

Redis 在 MVP 中只用于：

- Active Release 指针；
- Release Snapshot 缓存；
- 签名 Active Admission ACL 的运行时投影；
- Runtime 本地缓存失效通知；
- 限流计数；
- 无业务副作用请求的短期幂等缓存；
- Dashboard 短期指标聚合。

### 3.5 ADR-004：发布物不可变，回滚只切指针

- Draft 和 Scenario 当前编辑内容永不被 Runtime 读取。
- 发布生成不可变 Release Snapshot。
- MySQL `mock_active_release` 是 Active Release 的权威状态，按 `environment + app` 唯一。
- Redis Active Pointer 是权威状态的运行时投影，不是唯一事实。
- 回滚不会修改历史 Release，只创建新的 Activation Version，将权威引用切到历史 Release。
- Runtime 已加载的 Snapshot 按 ReleaseId 缓存，不原地更新。

MVP 明确采用“请求内一致、集群有界最终一致”，不宣称所有 Runtime 节点在同一纳秒切换：

- 每个请求在入口固定 `releaseId + activationVersion`，处理期间不变化；
- Runtime 请求入口读取本地原子 Pointer；后台每 1 秒比较 Redis Pointer，发现变化后先加载并验签，再替换本地 Pointer；
- Redis 投影成功后，正常节点最大陈旧窗口为 5 秒；
- Publish 在所有已注册健康 Runtime 节点上报新版本 READY 后完成；5 秒边界前保持 ACTIVATING，达到边界仍有 required Target 未 READY 时原子转为 PARTIAL、摘除未就绪节点并告警，阻断后续发布直到目标恢复、被审计豁免或回滚；
- 验收同时检查请求内无混版和集群收敛时间。

### 3.6 ADR-005：模板是白名单 DSL，不是脚本

用户只能使用本文件列出的变量和简单替换，不支持：

```text
if / else
循环
函数调用
类加载
网络访问
文件访问
反射
表达式求值
用户自定义代码
```

模板在发布阶段完成解析和编译，Runtime 只执行预编译 Token，不在请求路径解释任意表达式。

---

## 4. 端到端运行链路

### 4.1 REAL

```text
1. 业务进入带 @ThirdPartyMock 的方法。
2. SDK 读取当前不可变 RoutingSnapshot。
3. 计算结果为 REAL。
4. SDK 不改写 URL，不注入 Mock 内部 Header。
5. 原客户端按既有方式访问真实第三方。
```

### 4.2 MOCK

```text
1. 业务进入带 @ThirdPartyMock 的方法。
2. SDK 建立 MockContext：app/provider/api/env/tenant/testAccount/traceId。
3. HTTP Interceptor 将目标 Host 改写为 Mock Runtime，保留 method/path/query/body。
4. SDK 删除真实第三方凭证，附加应用身份和 Mock Context。
5. Runtime 验证应用身份，并以身份信息覆盖客户端自报 app/env。
6. Runtime 规范化并校验 Method、Path、Content-Type 与发布态 Contract。
7. Runtime 读取 environment + app 的 Active Release，并在请求入口固定 releaseId/activationVersion。
8. 对参与 Flow 的 API，先用发布态 Flow Mapping 提取业务键并查询既有 Flow。
9. 既有 Flow 存在时，改用其固定的 Release/Flow Definition Version，在该版本内匹配当前 API 的 Scenario。
10. 既有 Flow 不存在时，才在 Active Release 内按 scope/effectiveTime/matchRules 选择 Scenario，并原子创建 Flow。
11. 无匹配时返回 MOCK_NO_MATCH，不访问真实第三方。
12. Runtime 校验所有请求侧模板变量，确定候选响应或故障配置。
13. 有业务副作用时，在一个 MySQL 事务内写 Request Execution、推进 Flow、写 Event、创建 Callback Task，并保存最终响应/故障决策；任何渲染错误导致事务回滚。
14. 提交后执行已持久化的 status/delay/timeout/reset 决策；同一 MockRequestId 重试复用该决策，不重复推进状态。
15. Runtime 返回 MockRequestId/ScenarioId/ReleaseId，并记录脱敏日志和指标。
16. SDK 将 HTTP 结果交回 Feign/RestTemplate 原有解码链。
```

### 4.3 CANARY

CANARY 只支持确定性白名单，不在 MVP 实现百分比随机流量：

```text
命中 app + tenant + testAccount 或显式测试 Header 白名单 -> MOCK
未命中 -> REAL
```

约束：

- PROD 环境强制禁止 MOCK/CANARY。
- Request Override 默认关闭，仅测试环境、测试身份且应用显式开启时生效。
- 环境值以 SDK 可信配置和 Runtime 身份映射为准，不信任普通请求 Header。
- CANARY 结果必须写指标；配置中心变更必须能够关联操作人和变更单。

### 4.4 FALLBACK 策略

默认：

```text
FAST_FAIL
```

#### FAST_FAIL

Runtime 连接失败、超时或返回内部错误时，SDK 返回明确 Mock 异常，不访问真实系统。

#### FALLBACK_REAL

只有同时满足以下条件才允许：

1. 非 PROD 环境；
2. App + Provider + API 三级配置显式允许；
3. 失败属于明确的 DNS 解析失败、Connection Refused、Connect Timeout 或 TLS 握手前失败；
4. 请求体可安全重放，不是文件流或一次性流；
5. 真实地址属于 SDK 静态允许列表；
6. 写入 `mock_fallback_real_total` 指标和安全日志。

收到任何 Runtime HTTP 响应、完成 TLS 后的写入异常、读超时、响应中断或无法确定请求是否送达时，禁止自动回退真实，避免 Mock 已创建 Flow/Callback 后又调用真实第三方。无法可靠归类的异常一律按“可能已送达”处理。

`FALLBACK_REAL` 不是可直接修改的配置中心布尔值。Control 必须先发布已经审批的不可变 `SDK_FALLBACK_REAL` 安全策略版本，再由 `SDK_CONFIG_ENVELOPE` 引用；Apollo/Nacos 只保存签名 Config Activation Wrapper（内含完整签名 Envelope）。SDK 验证 Wrapper 与 Envelope 的 checksum、Control 签名、环境、App、Provider/API Scope 和有效期后，才能把结果装入 `RoutingSnapshot`。任一校验失败都回到 `FAST_FAIL` 并告警。

#### FALLBACK_RESPONSE

按 API 配置静态 HTTP 响应：

```yaml
fallback-response:
  status: 503
  content-type: application/json
  body: '{"code":"MOCK_RUNTIME_UNAVAILABLE","message":"mock runtime unavailable"}'
```

SDK 返回 HTTP 级响应，让 Feign/RestTemplate 的既有解码器处理，不在 SDK 中构造业务 Java 对象。

---

## 5. SDK 设计

### 5.1 Maven 模块

```text
mock-client-annotation
mock-client-core                 JDK8，无 Spring 依赖
mock-client-config-spi           JDK8
mock-client-apollo-adapter       JDK8
mock-client-nacos-adapter        按新巡天版本
mock-client-boot2-starter        JDK8 / javax
mock-client-boot3-starter        JDK17 / jakarta
```

父工程使用 Maven Toolchains 或独立流水线验证 JDK8/JDK17，不允许只在 JDK17 上编译后假定老巡天兼容。

### 5.2 注解

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ThirdPartyMock {
    String provider();
    String api();
}
```

约束：

- 一个注解方法只对应一次第三方 HTTP 调用。
- MVP 不支持在注解方法内并行发起多个第三方请求。
- MVP 不支持异步线程跨越注解作用域；WebClient/Reactor 适配后使用 Reactor Context 单独实现。
- 自调用无法经过 Spring AOP 时，应把注解放在实际代理接口或 Bean 边界。

### 5.3 拦截实现

#### Feign

- 启动时扫描 Feign Client 接口方法，建立 `configKey -> provider/api` 映射。
- 调用时由 Feign Capability/InvocationHandler 或已验证可用的代理扩展建立 MockContext。
- RequestInterceptor 只补充不涉及路由决策的 Trace/Mock Context。
- 使用 `Client` Decorator 包装既有 Feign Client，负责 REAL/MOCK 路由、创建脱敏的 Mock Request 副本、异常分类和 FALLBACK_RESPONSE。
- Decorator 保留不可变的 Original Request；MOCK 分支只修改副本，因此 FALLBACK_REAL 无需“恢复”已删除的真实凭证。
- FALLBACK_RESPONSE 在 `Client.execute` 层构造 Feign `Response`，继续进入既有 Decoder/ErrorDecoder。
- 一次逻辑调用只生成一个 MockRequestId，Feign Retryer 的所有 MOCK 尝试复用该 ID。
- 必须验证与既有 Retryer、ErrorDecoder、FallbackFactory、熔断组件的调用顺序；默认禁止这些组件把 Runtime 读超时自动转换为真实调用。

#### RestTemplate

- AOP Around 在进入注解方法时压入 MockContext，退出时 `finally` 清理。
- 使用 `ClientHttpRequestFactory` Decorator 或经 PoC 证明可双分支执行的 `ClientHttpRequestInterceptor`，负责路由、请求副本、异常分类和合成 `ClientHttpResponse`。
- Original Request 的 URI、Header、Body 保持不变；Mock Request 副本改写 URI并删除真实凭证。
- 只有允许的连接前异常才能使用 Original Request 执行 FALLBACK_REAL。
- 上下文使用栈而不是单值，避免嵌套调用覆盖。
- 线程池切换不自动传播；MVP 明确不支持异步 RestTemplate 调用。

Feign 和 RestTemplate 共用同一套 `RouteDecision`、`FailureClassification` 和策略测试，不分别解释安全规则。

### 5.4 URL 改写

SDK 从不可变 Original Request 创建 Mock Request 副本，只替换 scheme、host、port，并保留原始：

```text
HTTP Method
Path
Query String
Body
Content-Type
业务 Header 白名单
```

例如：

```text
真实地址：https://esign.example.com/v3/contracts/123?detail=true
Mock 地址：http://mock-runtime.test/v3/contracts/123?detail=true
```

Provider/API 不依赖 Path 反推，由可信 Mock Header 显式携带。

### 5.5 Header

SDK 发送：

```http
Authorization: MockApp <short-lived-token>
X-Mock-Provider: esign
X-Mock-Api: contract.query
X-Mock-Tenant: tenant-a
X-Mock-Test-Account: tester-01
X-Mock-Trace-Id: ...
X-Mock-Explicit-Scenario: ...   # 可选，受权限和范围限制
```

Runtime 根据认证主体得出：

```text
App
Environment
允许的 Provider/API
允许的 Tenant/TestAccount 范围
```

普通 Header 中的 `X-Mock-App`、`X-Mock-Environment` 不作为可信依据。

Runtime 正常 HTTP 响应附加：

```http
X-Mock-Request-Id: mr-xxx
X-Mock-Scenario-Id: sc-xxx
X-Mock-Release-Id: rel-xxx
X-Mock-Flow-State: PROCESSING   # 启用 Flow 时
```

SDK 不吞掉这些 Header，便于业务日志、测试脚本和问题排查关联。

### 5.6 凭证剥离

默认删除：

```text
Authorization
Proxy-Authorization
Cookie
Set-Cookie
X-Api-Key
X-App-Secret
第三方 Signature Header
SDK_HEADER_FILTER 策略声明的 Provider 额外敏感 Header
```

业务 Header 采用允许列表复制，并在复制前执行“平台默认 denylist ∪ 当前签名 `SDK_HEADER_FILTER` denylist”；deny 优先于 allow。删除只发生在 Mock Request 副本；Original Request 不修改且只可能进入 REAL 或符合严格条件的 FALLBACK_REAL。SDK 自己的 Mock 身份凭证只加入副本，在真实调用路径中不得出现。

Provider 首次接入或新增认证/签名 Header 时，必须先发布对应 `SDK_HEADER_FILTER` Version。Control 把 Header 规则与引用它的路由配置放入同一个签名策略信封；SDK 只有在二者完整、Scope 一致并验签通过时才原子替换 `RoutingSnapshot`，否则该 Provider 的 MOCK/CANARY 快速失败。这样不会出现“新路由已生效、敏感 Header 规则尚未生效”的中间状态。

### 5.7 动态配置

SDK 使用不可变快照：

```java
AtomicReference<RoutingSnapshot>
```

配置中心监听到完整且通过校验的新配置后一次替换引用；不逐字段修改共享对象。

所有会改变 REAL/MOCK/CANARY 的路由都必须生成不可变 `SDK_CONFIG_ENVELOPE`，不再允许把模式开关作为可独立修改的配置中心键。Envelope 以 `app + environment + configVersion` 唯一，包含完整路由规则、`SDK_HEADER_FILTER`/`FALLBACK_REAL` 策略版本及 Payload、有效期、canonical checksum、signature 和 signatureKeyId。启用 MOCK/CANARY 的 Provider/API 若缺少适用的 Header Filter，Envelope Validate 直接失败。

Control 通过 `ConfigPublisherAdapter` 把签名 `Config Activation Wrapper` 原子写入 Apollo/Nacos 单一配置项。Wrapper 是发布元数据，不改变已经审批的 Envelope，固定包含：

```text
activationId
app / environment
envelopeId / configVersion / envelopeChecksum
publishedAt
完整签名 Envelope
wrapperChecksum / wrapperSignature / wrapperSignatureKeyId
```

Config Publish Outbox 以预分配的持久化 ActivationId 为聚合键，并保存事务开始前一次生成、签名的 Wrapper 精确 canonical bytes。数据库事务若因并发版本检查失败，丢弃尚未持久化的 Wrapper；事务提交后 Adapter 只能读取并发布 Outbox 中的原始字节，不得重新生成 publishedAt、checksum 或 signature。动态 Target 集合不进入 Wrapper 或 Envelope checksum。SDK 必须先验证 Wrapper checksum、Control 签名、Scope 及其 Envelope 引用，再验证内层 Envelope 的 checksum、签名、有效期和策略引用；全部通过后才替换 `RoutingSnapshot`，不会分别监听路由和安全策略键。Provider Environment、App ACL、Callback Allowlist/Signature 等非 SDK 策略仍按各自发布通道消费。

配置切换审计采用两类证据：

1. Apollo/Nacos 保存“谁修改了什么配置”的源审计记录；
2. SDK 从已验签 Wrapper 取得 ActivationId，在新 RoutingSnapshot 整包实际生效或拒绝后，上报签名事件：activationId、app、environment、sdkInstanceId、envelopeId、oldConfigVersion、newConfigVersion、securityPolicyRefs、APPLIED/REJECTED、effectiveAt/error。

SDK 上报失败不阻塞业务调用，但必须本地重试有限次数并产生监控告警。Control 按 `activationId + sdkInstanceId` 幂等保存，并校验事件中的 App、Environment、EnvelopeId 与 Activation 一致；页面可关联配置中心变更单、各实例应用状态和实际生效的安全策略版本，不能用单个实例 ACK 宣称全部实例 EFFECTIVE。

SDK 观察到更高 ConfigVersion 但整包校验失败时，保留 REAL 路由；所有当前处于 MOCK/CANARY 的路由立即进入 `MOCK_CONFIG_INVALID` FAST_FAIL，不能继续用旧 Header/Fallback Policy 无限运行。成功应用有效 Envelope 后才解除。新实例未加载当前 ConfigVersion 不得通过 readiness。

优先级：

```text
受控 Request Override
  > API
  > Provider
  > App
  > Environment Default
```

示例：

```yaml
third-party:
  mock:
    environment: TEST
    app: pomp-power
    runtime-url: http://mock-runtime.test
    default-mode: REAL
    unavailable-policy: FAST_FAIL
    allow-request-override: false
    providers:
      esign:
        mode: MOCK
        unavailable-policy: FAST_FAIL
        apis:
          contract.query:
            mode: CANARY
            canary:
              tenants: [tenant-a]
              test-accounts: [tester-01]
```

### 5.8 SDK 指标

```text
third_party_mock_route_total{app,provider,api,mode}
third_party_mock_route_duration_ms{app,provider,api,mode}
third_party_mock_runtime_error_total{app,provider,api,error}
third_party_mock_fallback_real_total{app,provider,api,reason}
third_party_mock_config_version{app}
```

禁止在指标标签中放 BusinessNo、TraceId、账号等高基数字段。

### 5.9 SDK PoC 验证矩阵

在 M0 阶段锁定实际版本并完成以下验证：

| 维度 | 最低验证 |
|---|---|
| 老巡天 | 实际 JDK8、Spring、Feign、RestTemplate、Apollo 版本 |
| 新巡天 | JDK17、Boot3、Feign、RestTemplate、Nacos 版本 |
| 代理 | 注解可见性、自调用、嵌套调用、异常清理 |
| 客户端 | GET/POST、JSON、Form、空 Body、非 2xx |
| 动态配置 | REAL→MOCK→REAL 连续切换，无重启 |
| 安全 | 真实 Token/Cookie/Signature 未到 Runtime |
| 失败 | FAST_FAIL、连接前 FALLBACK_REAL、FALLBACK_RESPONSE |

文件上传、下载流、SSE 和 WebSocket 不属于 MVP。

---

## 6. Runtime 设计

### 6.1 技术栈

```text
JDK17
Spring Boot 3
Spring WebFlux
Reactor Netty
MyBatis / 公司统一数据层
MySQL
Redis
Micrometer / 公司监控
```

选择 WebFlux/Reactor Netty 的原因是 Runtime 属于 HTTP 网关型服务，需要非阻塞 Delay/Timeout，并需要在故障场景下控制客户端连接。

业务代码禁止无边界 `block()`；MySQL 调用在受控 JDBC Scheduler/线程池中执行，线程池大小和队列必须配置上限。

### 6.2 请求限制

默认限制：

```text
Request Header 总大小：32KB
Request Body：1MB
Response Body：1MB
模板 Token：500 个
Match Rule：每 Scenario 最多 20 条
Scenario：每 API 最多 100 个
Regex 长度：500 字符
Delay：最大 60 秒
Flow TTL：1 小时～7 天
```

超限在 Validate 或 Runtime 入站阶段明确拒绝。

### 6.3 认证

支持两种公司基础设施可落地的实现，项目启动时必须选定一种，不允许长期保持“App Identity / mTLS”模糊描述：

1. 首选：mTLS，证书 Subject/SAN 映射 App 和 Environment。
2. 备选：公司签发的短期 Service Token，Runtime 校验签名、audience、expiry、app、environment。

Runtime 永远不根据用户可自由填写的 Header 决定 App 或 Environment。

Tenant/TestAccount 既是隔离维度也是场景选择条件，必须满足以下信任模型：

- SDK 从巡天统一请求上下文取得 Tenant/TestAccount，并将完整 Mock Context 与 MockRequestId 一起签名；
- Runtime 校验签名后，仍根据签名的 Active Admission ACL 检查 App 被允许的 Provider/API/Tenant/TestAccount；
- Active Admission ACL 是独立于 Release 的实时准入策略。MySQL Security Policy Binding 中已提交的 `desiredPolicyVersionId + bindingVersion` 是权威发布意图，`effectivePolicyVersionId` 保存已完成投影的版本；Redis 只保存由 Admission Lease Projector 生成的签名短租约；
- MVP 固定每个 `environment + app` 只有一个 `APP_ACL` Aggregate/Binding。其不可变 Payload 一次包含该 App 的全部 Provider/API/Tenant/TestAccount 准入规则；增删任一 Provider 权限都会创建新的完整 Aggregate Version，不存在 Provider 级 Binding 覆盖同一 Redis Key；
- Runtime 每 5 秒轮询并原子替换本地不可变 Admission Snapshot，通知只用于加速。它只接受签名正确、Scope 匹配、`issuedAt <= now + 5秒`、`notAfter > now` 且 `(bindingVersion, issuedAt)` 不倒退的信封；重复读取同一个信封不得延长其本地有效期。Control/Runtime 节点必须使用公司时间同步，时钟偏差超过 5 秒时不签发/不接收租约并告警；
- 每个信封满足 `notAfter - issuedAt <= 60 秒`。Projector 只有重新读取并确认当前 MySQL desired Binding 后才能续签，默认每 20 秒续租；超过签名 `notAfter` 无法取得新租约时按 fail-closed 返回 `MOCK_ADMISSION_POLICY_STALE`。新策略中的 deny/撤销优先于 Release 内 Scenario Scope；
- Runtime 在 readiness/心跳中按 App 上报 `admissionBindingId + bindingVersion + policyVersionId`。新节点未加载所承载 App 的当前 Binding 不得 READY；策略投影后 60 秒仍未上报的旧节点必须进入 fail-closed 并从负载均衡摘除。Control 只有在该 Binding 的所有 READY 目标节点已上报或未上报节点已摘除时才展示 EFFECTIVE；
- Admission ACL 只决定“请求主体能否进入某 Provider/API/Tenant/TestAccount”，不改变 Scenario 内容。请求还必须满足入口固定 Release 中的 Scenario Scope，两者取交集；实时策略只能收紧或授予准入，不能修改已发布响应/Flow/Callback 语义；
- 无法取得可信业务上下文时，只能使用 App 预先绑定的默认测试 Tenant/Account，禁止调用方任意填写；
- 如果公司无法提供可信 Tenant/TestAccount 来源，必须把它们降级为非安全匹配标签，并在需求验收前由安全负责人书面确认，不能继续宣称完成租户安全隔离。

Admission 投影键：

```text
mock:active-admission:{environment}:{app}
  -> {bindingId, environment, app, policyVersionId, bindingVersion, payload, checksum, issuedAt, notAfter, signature, signatureKeyId}
```

Admission 发布先在一个 MySQL 事务中写入新的 `desiredPolicyVersionId`、递增 `bindingVersion`、状态 PUBLISHING、Audit 和 Outbox，旧 `effectivePolicyVersionId` 保持不变。Projector 只能投影这个已经提交的 desired Binding；Redis 写成功后再把同一 bindingVersion 的 `effectivePolicyVersionId` 更新为 desired、状态改为 BOUND。任一步崩溃都由 Reconciler 按 MySQL desired Binding 重放；Redis 丢失时也只依据已提交的 desired Binding 重建，不允许从旧缓存反向覆盖 MySQL。

Runtime 不直接拼装数据库字段，只加载完整验签成功的信封。`bindingVersion` 变大表示新的权威发布意图，即使策略版本号回滚也可识别；同版本续租只允许 `issuedAt` 前进。这样 Redis 写成功、数据库确认前崩溃不会形成“数据库无权威意图”的越权窗口，也不能用持续读取旧 Redis 内容无限续命。

### 6.4 Active Release 读取

键：

```text
mock:active-release:{environment}:{app}
  -> {releaseId, activationVersion, snapshotChecksum, signatureKeyId}
mock:release-snapshot:{releaseId}
  -> signed compiled snapshot envelope
```

读取顺序：

```text
Runtime 本地 Caffeine Cache
  -> Redis Active Pointer
  -> Redis Release Snapshot
  -> 校验 checksum 和 Control signature
  -> 放入本地不可变缓存
```

每个请求在入口读取本地 Pointer Version；后台每 1 秒轮询 Redis Pointer，Pub/Sub 仅用于加速。收到新版本后先加载、验签、预编译校验，再原子替换本地引用。正常健康节点必须在 5 秒内收敛，否则从负载均衡摘除并告警。

Redis 不是权威来源。Redis 丢失或冷启动无法读取缓存时，Runtime 可以从 MySQL `mock_active_release` 和 `mock_release` 恢复并回填缓存。MySQL 与 Redis 均不可用时，已加载节点可以在配置的 10 分钟保护窗口继续使用 Last Known Good Release；冷启动或超过保护窗口返回 `MOCK_RELEASE_UNAVAILABLE`。该 10 分钟窗口不适用于安全准入：Active Admission Snapshot 仍按 6.3 的 60 秒上限 fail-closed。

每个请求固定入口时读取到的 `releaseId + activationVersion`。处理期间即使本地 Active Pointer 更新，也继续使用已固定 Snapshot，避免同一请求混用两个版本。

### 6.5 场景选择

场景选择前必须使用 Release Snapshot 中的发布态 Contract 校验：

```text
HTTP Method 精确匹配
Path 按 RFC 3986 逐段规范化后匹配已编译 Path Template
Content-Type 忽略参数后匹配，例如 application/json;charset=UTF-8 -> application/json
请求 Body 类型和大小符合 Contract
```

Path 只做一次百分号解码，拒绝非法编码、`.`、`..`、反斜杠和超出模板的额外段。Path Variable 只绑定单个 Segment，不能跨 `/`。校验失败返回 `MOCK_CONTRACT_MISMATCH`，不继续匹配 Scenario。

过滤顺序：

```text
Release
  -> Provider + API
  -> Environment + App
  -> Tenant + TestAccount
  -> effectiveFrom/effectiveTo
  -> Explicit Scenario（如有且有权限）
  -> Match Rules
  -> Priority
```

规则：

- 一个 Scenario 内 Match Rules 为 AND。
- 多个 Scenario 之间为 OR。
- Priority 数值越大优先级越高。
- 同优先级同时命中时，按 `scenarioCode` 升序确定结果，同时记录冲突指标。
- 发布校验应阻断能够静态证明的同优先级冲突。
- Regex 使用 RE2/J 兼容语法，拒绝回溯型高风险表达式。
- JSONPath 只读，不允许脚本过滤器或执行扩展。

显式场景规则优先，但仍必须满足当前 Release、Provider/API 和 Scope，禁止通过 ScenarioId 越权访问其他 App/Tenant 的场景。

### 6.6 Match Rule 模型

```json
{
  "type": "HEADER | QUERY | JSON_PATH | REGEX | BUSINESS_NO | EXPLICIT_SCENARIO",
  "key": "字段名或 JSONPath",
  "operator": "EQ | NE | CONTAINS | PREFIX | REGEX | EXISTS",
  "value": "期望值",
  "caseSensitive": true
}
```

`BUSINESS_NO` 不同时承担匹配和提取职责。每个 API Contract 单独配置业务键提取器：

```json
{
  "source": "HEADER | QUERY | JSON_BODY",
  "path": "X-Business-No | orderNo | $.data.orderNo",
  "required": true,
  "normalize": "TRIM"
}
```

提取失败且 Scenario 启用 Flow 时返回 `MOCK_BUSINESS_KEY_MISSING`。

### 6.7 模板 DSL

允许变量：

```text
${request.body.$.path}
${request.header.Header-Name}
${request.query.param}
${flow.businessNo}
${flow.variable.name}
${state.current}
${traceId}
${mockRequestId}
${now.iso}
${now.epochMillis}
${uuid}
```

规则：

- 仅支持变量替换，不支持表达式、方法、条件和循环。
- 所有变量路径在发布时解析并编译。
- Header 名大小写不敏感，Query 参数区分大小写。
- 缺失变量默认使请求失败并返回 `MOCK_TEMPLATE_VARIABLE_MISSING`；场景可针对单个变量配置固定默认值。
- JSON 响应中的替换值必须执行 JSON 字符串转义。
- 渲染后再次校验 JSON 合法性和最大响应大小。
- `${uuid}` 每次请求生成一次，同一响应内多处引用值一致。
- `${now.*}` 每次请求取一个固定时刻，避免同一响应内时间漂移。

类型语义：

- 普通 `${...}` 只能出现在 JSON 字符串中，始终按字符串进行 JSON 转义；
- `${json:request.body.$.amount}` 可作为完整 JSON Value 插入 number、boolean、null、object 或 array；
- `json:` Token 必须独占一个 JSON Value 位置，不能与普通文本拼接；
- 发布编译时根据 Response Schema 校验 Token 期望类型，运行时类型不符则整个请求失败且事务不提交。

示例：

```json
{
  "code": "0",
  "contractId": "${flow.businessNo}",
  "status": "${state.current}",
  "requestId": "${mockRequestId}",
  "createdAt": "${now.iso}"
}
```

### 6.8 响应与故障模型

```json
{
  "httpStatus": 200,
  "headers": {
    "Content-Type": "application/json"
  },
  "bodyTemplate": "{...}",
  "delayMs": 0,
  "fault": {
    "type": "NONE | HTTP_ERROR | READ_TIMEOUT | CONNECTION_RESET",
    "durationMs": 30000,
    "sideEffectPolicy": "NO_APPLY | APPLY_BEFORE_FAULT"
  }
}
```

语义：

| 类型 | 行为 |
|---|---|
| NONE | 正常返回 status/header/body |
| HTTP_ERROR | 返回配置的 4xx/5xx 和 Body |
| Delay | 非阻塞等待后返回，不占用 Servlet 工作线程 |
| READ_TIMEOUT | 在 `durationMs` 内不发送响应字节，结束时关闭连接；最大 60 秒 |
| CONNECTION_RESET | Runtime 在接受请求后由 Netty Reset 当前 HTTP/1.1 连接，不转换成 5xx |

Connection Reset 必须通过 JDK8/JDK17 客户端集成测试证明客户端观察到连接异常，否则不得标记为已支持。

MVP Runtime 对 Mock 入口固定使用 HTTP/1.1，避免 HTTP/2 多路复用下关闭连接影响同连接的其他请求。故障场景必须显式选择副作用策略：

- `NO_APPLY`：不推进 Flow、不创建 Callback，只记录持久化的故障决策；
- `APPLY_BEFORE_FAULT`：在发送故障前提交 Flow/Event/Callback 和 Request Execution，用于模拟“第三方已受理但响应丢失”。

同一 MockRequestId 重试始终复用第一次的策略和结果，不再次产生副作用。

禁止用户配置：

```text
任意上游 URL
HTTP Forward
动态 Webhook
本地文件路径
任意响应脚本
Hop-by-hop Header
```

### 6.9 Runtime 错误码

```text
MOCK_AUTH_FAILED
MOCK_FORBIDDEN
MOCK_CONTEXT_INVALID
MOCK_CONTRACT_MISMATCH
MOCK_REQUEST_TOO_LARGE
MOCK_RATE_LIMITED
MOCK_RELEASE_NOT_FOUND
MOCK_RELEASE_UNAVAILABLE
MOCK_ADMISSION_POLICY_STALE
MOCK_NO_MATCH
MOCK_BUSINESS_KEY_MISSING
MOCK_FLOW_CONFLICT
MOCK_FLOW_OPERATION_BUSY
MOCK_TEMPLATE_VARIABLE_MISSING
MOCK_TEMPLATE_RENDER_FAILED
MOCK_IDEMPOTENCY_CONFLICT
MOCK_REQUEST_IN_PROGRESS
MOCK_INTERNAL_ERROR
```

`MOCK_ADMISSION_POLICY_STALE` 属于 `SECURITY_FAIL_CLOSED`：SDK 原样抛出明确 Mock 基础设施异常，禁止 FALLBACK_REAL，也不转换为可能掩盖撤权的业务兜底响应；同时记录 `mock_sdk_admission_stale_total`。

错误响应必须包含：

```json
{
  "code": "MOCK_NO_MATCH",
  "message": "no published scenario matched",
  "mockRequestId": "mr-xxx",
  "traceId": "trace-xxx"
}
```

不得包含堆栈、SQL、模板原文、密钥或内部地址。

### 6.10 限流

维度：

```text
Environment + App + Provider + API
```

MVP 使用 Redis 固定窗口或公司统一限流组件。平台自我保护的 429 与 Scenario 模拟的第三方 429 必须使用不同错误码和指标。

### 6.11 Runtime 幂等

SDK 为每次逻辑 Mock 请求生成 `X-Mock-Request-Id`，所有底层 HTTP 重试复用该 ID。Runtime 使用与真实路由、匹配相同的规范化逻辑计算 HMAC 请求指纹：

```text
认证主体 ID
+ environment + app + tenant + testAccount
+ provider + api
+ normalized method + path + sorted multi-value query
+ normalized Content-Type
+ explicitScenario
+ canonicalBusinessHeaders
+ bodyHash
```

`canonicalBusinessHeaders` 包含 SDK 允许转发的全部业务 Header，名称转小写、值保持顺序；排除 Mock Authorization、TraceId、MockRequestId 等不影响业务决策的传输字段。这样所有 Header Match、Header Business Key Extractor 和显式场景上下文都进入指纹。指纹只保存 HMAC 结果，不保存敏感 Header 原文。

对会推进 Flow、创建 Callback 或执行故障副作用的 SDK 请求，MySQL `mock_request_execution` 是幂等账本，并与状态变更处于同一事务。Timer/Manual 等内部事件不使用该表，按 7.6 的确定性 `internalExecutionId` 在 Flow Event 中防重。账本保存：

```text
app + mockRequestId
requestFingerprint
IN_TRANSACTION / COMPLETED
releaseId / activationVersion / scenarioVersionId
flowInstanceId / flowGeneration / transitionResult
response status / headers / encrypted body
fault type / duration / sideEffectPolicy
expireAt
```

处理规则：

1. 新事务先插入 `IN_TRANSACTION` 占位行，利用 `(app, mockRequestId)` 唯一键获得数据库唯一键锁；该状态不会在事务提交前对其他事务可见；
2. 插入冲突时 `SELECT ... FOR UPDATE` 锁定既有行。并发首事务提交后读取 `COMPLETED`；首事务回滚后由等待方成功插入并继续；
3. 既有行 `expire_at <= now` 时，在同一行锁事务内把 `execution_generation + 1`、新指纹/TTL 写入，清空全部旧 Release/Flow/Response/Fault 字段并重置为 `IN_TRANSACTION`，随后按新逻辑请求执行；不得等待 Cleanup 先删除；
4. Cleanup 删除过期 Execution 也必须先锁行并重新检查 expireAt。若业务请求先重置，Cleanup 看到新 TTL 后跳过；若 Cleanup 先删除，业务请求随后插入新行；
5. 未过期的已提交记录指纹相同：复用响应或故障决策；指纹不同：返回 `MOCK_IDEMPOTENCY_CONFLICT`；
6. 唯一键/行锁等待超过 2 秒，返回 `MOCK_REQUEST_IN_PROGRESS`，不另起状态推进；
7. 新请求在同一事务内写 Flow/Event/Callback、渲染结果并更新为 `COMPLETED`；模板渲染失败使占位行和全部副作用一起回滚；
8. 进程在提交后、回包前崩溃，客户端重试仍读取相同结果。

平台对有副作用请求从 `completed_at` 起提供 24 小时幂等保证，`expire_at = completed_at + 24h`。SDK 自动重试总窗口不得超过 5 分钟，并且永不复用已结束逻辑调用的 MockRequestId；超过 24 小时再次使用相同 ID 按新逻辑请求处理。若某接入方需要更长重试窗口，必须在 API 接入评审时提高 Execution TTL 并纳入容量测算。

纯无状态且没有故障副作用的响应可使用 Redis 短期幂等缓存；缓存丢失只可能重新生成 UUID/时间，不会改变 Flow 或创建 Callback。若产品要求所有无状态重试也字节级一致，则统一启用 MySQL 账本并重新评估容量。

---

## 7. Flow 设计

### 7.1 Flow Definition Version

Flow 是跨 API 的稳定业务流程，不隶属于某一个查询 API。`flowCode` 在 Provider 内唯一；Definition 每次修改生成不可变 Version。

MVP Flow Graph 必须无环；同一个 `transitionId` 在一个 generation 内最多执行一次。每个 Transition 必须声明整数 `priority`。需要循环状态的业务通过 Reset 开启新 generation，不在 MVP 引入循环工作流语义。

```json
{
  "flowCode": "esign.contract",
  "version": 3,
  "initialState": "PROCESSING",
  "ttlSeconds": 86400,
  "participantApis": [
    {
      "apiCode": "contract.create",
      "role": "CREATE",
      "createIfAbsent": true,
      "businessKeyExtractor": {
        "source": "JSON_BODY",
        "path": "$.contractNo",
        "required": true,
        "normalize": "TRIM"
      }
    },
    {
      "apiCode": "contract.query",
      "role": "QUERY",
      "createIfAbsent": false,
      "businessKeyExtractor": {
        "source": "QUERY",
        "path": "contractNo",
        "required": true,
        "normalize": "TRIM"
      }
    },
    {
      "apiCode": "contract.cancel",
      "role": "COMMAND",
      "createIfAbsent": false,
      "businessKeyExtractor": {
        "source": "JSON_BODY",
        "path": "$.contractNo",
        "required": true,
        "normalize": "TRIM"
      }
    }
  ],
  "variables": [],
  "transitions": []
}
```

角色语义：

- `CREATE`：可创建 Flow；重复创建由 MockRequestId 和 Flow 唯一键约束。
- `QUERY`：读取状态并可增加 QueryCount。
- `COMMAND`：按请求字段触发迁移，不增加 QueryCount。
- 一个 API 在同一个 Flow Definition 中只能有一个角色。

业务键提取器属于不可变 Flow/Contract Version 并进入 Release Snapshot。修改同一 `flowCode` 的规范化结果或键语义时，如果仍有未过期实例则发布被阻断；不兼容变更必须使用新 `flowCode`。

### 7.2 Flow 查找顺序

对参与 Flow 的 API，Runtime 按以下顺序执行：

```text
1. 从 Active Release 获取当前 API 的 Flow Mapping 和版本化 Business Key Extractor。
2. 提取并规范化 Business Key，计算 Flow Key。
3. 使用当前及保留 HMAC Key 查询 mock_flow_instance；若 status=ACTIVE 但 expireAt<=now，在行锁内先标记 EXPIRED 再分支。
4. ACTIVE 实例存在：
   4.1 读取实例固定的 releaseId / flowDefinitionVersionId；
   4.2 加载旧 Release Snapshot；
   4.3 校验当前 API 是该固定 Flow Version 的 Participant；
   4.4 在固定 Release 内匹配当前 API、同 flowCode 的 Scenario Version。
5. EXPIRED/DELETED 实例存在：只有当前 API 是 CREATE 且 createIfAbsent=true 时进入 7.8 的原子再激活协议；QUERY/COMMAND 按无活动 Flow 返回。
6. 实例不存在：
   6.1 在 Active Release 内匹配 Scenario；
   6.2 只有 createIfAbsent=true 的 Participant 可以创建 Flow；
   6.3 在唯一键约束下原子创建，冲突后重新读取并按 ACTIVE/EXPIRED/DELETED 分支处理。
```

因此发布新 Release 后，处理中流程不会先经过新 Scenario Match 再查旧 Flow。

既有 Flow 的后续请求仍校验 App/Tenant/TestAccount 与 Flow Key 一致，但不因旧 Scenario 的 `effectiveTo` 已过而中断；其生命周期由 Flow TTL 决定。新建 Flow 才按当前 Scenario 有效期判断。

为保证第 1 步始终可定位旧 Flow，只要仍存在未过期实例，发布校验就禁止从新 Release 移除其 `provider + api -> flowCode` Locator，也禁止改变会产生不同规范化 Business Key 的提取规则。不兼容变更必须使用新 API Code/Flow Code，并保留旧 Locator 至相关 Flow 全部过期。

同一兼容性检查同时适用于 Publish 和 Rollback。Control 根据未过期 Flow 引用的 Flow Definition Version 汇总所需 Participant Locator/Extractor；目标 Release 缺少任一项时阻断激活，并列出受影响 Flow 数量和缺失 Locator。需要回滚时应先生成“旧业务配置 + 必需兼容 Locator”的新修复 Release，或明确终止受影响 Flow，不能强制切换后让旧流程失联。

### 7.3 Flow Key

逻辑组成：

```text
environment
app
provider
flowCode
tenant
testAccount
businessNo
```

API 不进入 Flow Key，只作为事件来源。数据库保存规范化维度并建立唯一索引，同时生成：

```text
businessNoHmac = HMAC-SHA256(kv, normalizedBusinessNo)
flowKey = base64url(HMAC-SHA256(kv,
  canonical(environment, app, provider, flowCode, tenant, testAccount, businessNoHmac)))
```

`kv` 是受密钥服务管理并带版本号的 HMAC Key。BusinessNo 查询和 Flow 查找对当前及保留版本分别计算候选 HMAC，只做精确匹配；新建 Flow 只使用当前版本并记录 `hmac_key_version`。旧 Key 的保留时间必须长于最大 Flow TTL、Request Log 查询保留期和安全缓冲期，过期前不得销毁。

密钥轮换采用两阶段：第一阶段把新 Key 作为“可读但不写”版本发布到全部 Runtime；全部节点确认且超过单请求最大执行时间后，第二阶段才把它切为当前写版本。旧 Key 继续可读到所有旧 Flow 和日志过期，避免切换窗口创建两个逻辑相同的 Flow。任何节点缺少所需 Key 时不得创建或推进 Flow。不把明文业务单号拼到 Redis Key、指标标签或普通日志中。

### 7.4 版本固定

创建 Flow Instance 时固定：

```text
release_id
flow_definition_version_id
flow_definition_checksum
generation
```

每次 API 调用再从固定 Release 中选择该 API 对应的 Scenario Version，并写入 Request Execution/Flow Event。

新 Release 发布后：

- 已存在 Flow 继续按创建时 Release 和 Flow Version 运行；
- 新业务键使用新 Active Release；
- 回滚不隐式修改既有 Flow；
- Reset 默认递增 generation，并按当前 Active Release 重建；也可经显式选择保留原版本。

历史 Release 和 Flow Definition Version 的保留时间必须覆盖所有引用它们的未过期 Flow。

### 7.5 Flow Variables

变量必须在 Definition 中声明：

```json
{
  "name": "applicantName",
  "type": "STRING | NUMBER | BOOLEAN",
  "required": false,
  "maxLength": 200,
  "initialValue": {
    "source": "REQUEST_FIELD | LITERAL",
    "path": "$.applicant.name",
    "value": null
  }
}
```

Transition 只允许以下 Assignment：

```text
SET_LITERAL
SET_FROM_REQUEST_FIELD
INCREMENT_NUMBER
CLEAR
```

限制：

- 每个 Flow 最多 50 个变量；
- `variables_json` 序列化后最大 8KB；
- 类型在发布时和运行时校验；
- 缺失 required 初始变量时不创建 Flow；
- Assignment 与状态迁移处于同一事务；
- 不支持表达式、变量间计算、脚本或任意对象。

`${flow.variable.name}` 只能读取已声明变量。

### 7.6 状态推进语义

每个 Transition 必须有稳定 `transitionId`。

确定性选择规则：

1. 只评估 `from == currentState` 且与当前事件类型相符的 Transition；
2. 一个输入事件最多执行一个 Transition；
3. 多个条件同时满足时按 `priority` 降序、`transitionId` 升序选择；
4. 发布时阻断能够静态证明的同状态、同事件、同优先级冲突，并对无法证明互斥的规则给出警告；
5. Runtime 惰性 TIME 迁移是独立 Timer Event，先完成至多一次到期迁移，再以迁移后的状态处理当前 SDK Request Event；两者分别写唯一 Event。

#### QUERY_COUNT

- 只统计 Participant Role 为 `QUERY` 的调用。
- 本次请求先增加 queryCount，再判断迁移。
- 达到阈值的该次查询返回迁移后的状态。
- 同一 MockRequestId 重试通过 Request Execution 不重复计数。

#### TIME

- Flow Instance 保存 `next_transition_at` 和待执行 `transition_id`。
- Control 内的 Flow Timer Worker 用短事务 `SELECT ... FOR UPDATE SKIP LOCKED` 扫描到期实例。
- Worker 和 Runtime 惰性检查调用同一个 `FlowTransitionService`，都先锁定 Flow 行。
- `mock_flow_event` 对 `(flow_instance_id, generation, transition_id)` 建唯一约束，保证同一时间迁移只执行一次。
- Timer Worker 不创建 SDK `Request Execution`，使用确定性 `internalExecutionId = timer:{flowId}:{generation}:{transitionId}`；Runtime 惰性检查与 Worker 对同一到期迁移生成相同 ID。
- 事务只更新状态、变量、Event 和 Callback Task，不在锁内发送 HTTP。

#### REQUEST_FIELD

```json
{
  "transitionId": "approve",
  "priority": 100,
  "from": "PROCESSING",
  "to": "SUCCESS",
  "trigger": {
    "type": "REQUEST_FIELD",
    "source": "JSON_BODY",
    "path": "$.action",
    "operator": "EQ",
    "value": "approve"
  },
  "assignments": []
}
```

只使用与 Scenario Match 相同的安全操作符。

#### MANUAL

只能通过管理 API 操作，并要求 `mock:flow:transition` 权限、二次确认和同步审计。管理请求必须带唯一 `requestId`/`Idempotency-Key`，人工事件使用 `internalExecutionId = manual:{requestId}`；重试同一请求复用 Event 结果，不创建 SDK `Request Execution`。人工迁移复用相同事务服务和唯一 Event 规则。

### 7.7 并发与 SDK 请求事务

```text
BEGIN
锁定或创建 mock_request_execution
SELECT flow_instance ... FOR UPDATE
检查 status / generation / expire_at / fixed release
校验并执行 Transition 与 Variable Assignment
完成响应模板与 Callback URL/Header/Payload 的最终渲染、大小校验和故障决策
UPDATE flow_instance
INSERT flow_event（如有迁移）
INSERT callback_task（如有，唯一键防重）
UPDATE request_execution = COMPLETED，保存完整决策
COMMIT
```

这套 `mock_request_execution` 账本只服务 SDK 请求。Timer/Manual 事务从锁定 Flow 开始，以 `mock_flow_event.internal_execution_id` 防重；Event、Flow、Callback Task 和同步 Audit（Manual）仍在同一事务提交。

同一 Flow 的并发请求串行，不同 Flow 可并行。锁等待超出阈值返回 `MOCK_FLOW_CONFLICT`，不无限重试。任何模板、变量或回调任务生成错误均回滚整个事务。

### 7.8 Reset、Delete 与 Generation

- Flow 创建时 `generation=1`。
- Callback Task 固定 `flow_instance_id + generation`。
- Reset/Delete 先锁定 Flow 行，并检查当前 generation 的 Callback Task。
- 存在 RUNNING Task 时返回 `MOCK_FLOW_OPERATION_BUSY`，不声称能够撤回已经开始的外部发送。
- 没有 RUNNING Task 时，在同一事务中取消 NEW/RETRYING Task、写 Event，并递增 generation。
- Reset 重建初始状态、queryCount、variables 和版本引用；Delete 先标记 `DELETED`，不立即物理删除。
- Worker Claim 只锁 Task 并置为 RUNNING、递增 fencing token；提交后再无锁当前读 Flow。若 Flow 非 ACTIVE 或 generation 不同，Worker 在只锁 Task 的 fenced 事务中取消 Attempt/Task。Worker 已进入 RUNNING 后，Reset/Delete 必须返回 `MOCK_FLOW_OPERATION_BUSY`，因此 Worker 的后续事务无需且不得锁 Flow。
- 成功完成 Reset/Delete 后，旧 generation 未开始发送的 Callback 不会再执行；已经 SUCCESS 的历史发送不可撤销并保留审计。

同一 BusinessNo 在 TTL 到期或 Delete 后可以重新开始测试，但不插入第二条逻辑实例。CREATE 在 Request Execution 事务内锁定旧 Flow 行并执行：

```text
确认 status IN (EXPIRED, DELETED)
确认当前 generation 无 RUNNING Callback，否则返回 MOCK_FLOW_OPERATION_BUSY
取消 NEW/RETRYING Callback
generation = generation + 1
切换到当前 Active Release / Flow Definition / HMAC Key Version
重建 initialState / queryCount / variables / pendingTransition / expireAt
写 REACTIVATE Flow Event
status = ACTIVE
```

整个过程与本次 SDK Request Execution、响应和新 Callback Task 同事务提交。并发 CREATE 仍由 Flow 行锁串行，后到请求读取已再激活实例；旧 Event/Attempt 通过 generation 保留审计，不依赖物理删除释放唯一键。

### 7.9 TTL 和清理

- `expire_at` 创建时确定，人工推进不默认延长。
- 到期 Flow 不再作为 ACTIVE 实例参与 QUERY/COMMAND；CREATE 可按 7.8 原子再激活。
- Cleanup 复用 Delete 规则：取消未开始任务；存在 RUNNING Task 时跳过，下一批重试。
- 只有 Flow 已标记 DELETED、无 RUNNING Task 且达到保留期后才物理删除。
- Flow Event、Request Execution 和 Callback Attempt 按各自保留策略清理。
- 清理操作必须记录数量、耗时和失败指标。

---

## 8. Callback 设计

### 8.1 Callback Definition

```json
{
  "enabled": true,
  "triggerState": "SUCCESS",
  "urlSource": "FIXED | REQUEST_FIELD",
  "url": "https://test-app.example.com/callback/esign",
  "requestField": "$.callbackUrl",
  "method": "POST",
  "headers": {
    "Content-Type": "application/json"
  },
  "payloadTemplate": "{...}",
  "delayMs": 1000,
  "maxRetry": 3,
  "retryIntervalsMs": [1000, 5000, 30000],
  "totalDeliveryCount": 1,
  "deliveryOffsetsMs": [0],
  "signaturePolicyVersionId": "esign-sign-v3",
  "allowlistPolicyVersionId": "esign-callback-allowlist-v5"
}
```

限制：

```text
maxRetry <= 3
retryIntervalsMs.length == maxRetry
retryIntervalsMs 每项非负，累计自动重试计划 <= 24h
1 <= totalDeliveryCount <= 3
deliveryOffsetsMs.length == totalDeliveryCount
单次 Payload <= 1MB
总延迟 <= 24h
只允许 HTTP/HTTPS 中公司批准的协议，默认只允许 HTTPS
```

### 8.2 任务生成

`totalDeliveryCount` 表示场景主动要求的总投递数：1 表示正常投递一次，2 表示一次正常投递加一次主动重复。故障重投不计入该数。

状态迁移到 Callback Trigger State 时，根据 `totalDeliveryCount` 和 `deliveryOffsetsMs[deliveryIndex]` 生成多条 Delivery Task：

```text
deliveryId = stable UUID derived from flowEventId + callbackDefinitionId + deliveryIndex
unique(flow_event_id, flow_generation, callback_definition_id, delivery_index)
```

任务创建时同时固定 `releaseId + snapshotChecksum + callbackSignaturePolicyVersionId + callbackAllowlistPolicyVersionId`。后续重试只从该签名 Release Snapshot 读取精确版本，不按当前 Security Policy Binding 重新解析 PolicyId；策略更新不会让历史任务换算法、Secret Ref 或 Allowlist。

Callback 的 URL、业务 Header 和 Payload 在创建 Task 的 Flow 事务内完成最终渲染、大小校验，以及不含 I/O 的 URI/Scheme/Host/Port/Allowlist 检查，并立即加密保存最终值。`${now}`、`${uuid}`、Request 字段、Flow Variables 和 Event 状态都在此时求值；生成值进入 Task/Request Execution 决策。任何变量缺失、模板错误、URL 或 Payload 超限都会使 Flow/Event/Task/Execution 整体回滚；DNS/网段/IP Pinning 不进入该事务。

Worker 不再读取创建时 Request 或当前 Flow Variables，也不重新渲染 Payload。每个 Attempt 只生成签名协议规定的 timestamp/nonce，并使用 Task 中相同的最终 URL/Header/Payload；因此即使 Request Execution 已清理或 Flow 后续迁移，重试内容也不会漂移。

主动重复的每个 Delivery 有不同 `deliveryId`，但共享 `flowEventId`。同一个 Delivery 的故障重投保持相同 `deliveryId`，只增加 `attemptNo`。

简单乱序通过不同 Callback Definition 或 Delivery 的 `deliveryOffsetsMs` 控制计划执行时间，不实现任意依赖图。发布校验要求数组长度严格等于总投递数且所有 Offset 非负。

### 8.3 Worker 领取

```text
BEGIN
SELECT callback_task ... WHERE (
  (task.status IN (NEW, RETRYING) AND next_execute_at <= now)
  OR (task.status = RUNNING AND lease_until < now)
)  -- 只锁 Task，不 JOIN/锁 Flow
FOR UPDATE SKIP LOCKED LIMIT 50
若接管过期 RUNNING 且旧 Attempt=PREPARING：标记 ABANDONED_PREPARATION，不消耗发送预算，继续领取
若接管过期 RUNNING 且旧 Attempt=STARTED：标记 ABANDONED；如果 sendAttemptCount >= 1 + maxRetry + manualSendGrantCount，则 UPDATE task=FAILED_UNCONFIRMED，COMMIT 后 STOP，不得进入下列分支
只有未耗尽预算或旧 Attempt 未开始发送时：
UPDATE status=RUNNING, lease_owner=?, lease_until=?, fencing_token=fencing_token+1
INSERT callback_attempt(status=PREPARING, delivery_id, attempt_no, fencing_token)
COMMIT

事务外当前读 Flow status/generation；此时不持有 Task 锁
若 Flow 非 ACTIVE 或 generation 不同：在只锁 Task 的 fenced 事务中把 Attempt/Task=CANCELLED，清租约，COMMIT 后 STOP
执行前重新读取 Task cancel status 和 fencing_token
按 Task 固定的 Release/Snapshot/策略 Version 解密最终请求、读取 Secret、解析 DNS、校验 Allowlist、固定目标 IP
若准备失败：进入 fenced Preparation Finalize 事务，按下表更新 Attempt/Task、nextExecuteAt 并清租约，COMMIT 后 STOP

BEGIN
再次校验 Task.status=RUNNING、lease_owner + fencing_token；只访问/锁定 Task 与 Attempt，不查询或锁定 Flow
分配 sendAttemptNo，增加 sendAttemptCount，将 Attempt 从 PREPARING 改为 STARTED
COMMIT

事务外执行 HTTP Callback

BEGIN
校验 lease_owner + fencing_token
按 deliveryCertainty + HTTP 结果 + 剩余预算执行下表转换
更新 callback_attempt 结果并清租约
COMMIT
```

数据库锁顺序固定为：Runtime/Timer/Reset/Delete/Cleanup 事务需要两类行时一律 `Flow -> Task`；Callback Worker 的 Claim、Preparation Finalize、HTTP Finalize 事务只锁 Task，绝不在持有 Task 锁时等待 Flow。Worker 先置 RUNNING 时，Reset/Delete 随后看到 RUNNING 返回 BUSY；Reset/Delete 先取消 Task 时，Worker 的 Claim 条件不会再选中。Worker Claim 连接使用 READ COMMITTED；MVP 需 MySQL 8.x 的 `SKIP LOCKED`，若公司实例不支持则必须在 M0 改为经 PoC 的原子 Claim 方案。目标 MySQL 版本/隔离级别下必须用并发测试证明无 Flow↔Task 反向死锁。

所有 Finalize 都用 `WHERE task_id=? AND lease_owner=? AND fencing_token=? AND status='RUNNING'` 条件更新并要求 affectedRows=1；为 0 表示旧 Worker，丢弃结果且不得改 Attempt/Task。完整转换表：

| 条件 | Attempt 终态 | Task 终态 | 其他原子更新 |
|---|---|---|---|
| Flow 非 ACTIVE 或 generation 不同 | CANCELLED | CANCELLED | 清 lease，STOP |
| Preparation 失败且 `preparationRetryCount < maxPreparationRetry + manualPreparationGrantCount` | PREPARATION_FAILED | RETRYING | preparationRetryCount+1、nextAt=固定准备间隔、清 lease |
| Preparation 失败且预算耗尽 | PREPARATION_FAILED | FAILED_PREPARATION | preparationRetryCount+1、清 lease、告警 |
| HTTP 2xx | SUCCESS | SUCCESS | 保存 status/duration、清 lease |
| 明确 HTTP 4xx/不可重试 5xx | FAILED | FAILED | deliveryCertainty=CONFIRMED_RESPONSE、清 lease |
| 可重试结果且仍有发送预算 | FAILED | RETRYING | 保存 deliveryCertainty、nextAt=retryIntervalsMs[sendAttemptNo-1]、清 lease |
| 完整收到可重试 5xx 但发送预算耗尽 | FAILED | FAILED | deliveryCertainty=CONFIRMED_RESPONSE、清 lease |
| 明确未写出请求且发送预算耗尽 | FAILED | FAILED | deliveryCertainty=NEVER_SENT、清 lease |
| 已开始写出后 Read Timeout/Reset/响应中断且预算耗尽 | ABANDONED | FAILED_UNCONFIRMED | deliveryCertainty=UNKNOWN、清 lease、告警 |

HTTP Client 必须返回 `deliveryCertainty = NEVER_SENT / CONFIRMED_RESPONSE / UNKNOWN`。无法证明 NEVER_SENT 或收到完整响应时一律 UNKNOWN。Finalize 不得把 UNKNOWN 的最终失败记成普通 FAILED。

调用超时不得超过租约，接近租约边界时 Worker 只能用同一 fencing token 续租。旧 Worker 不能用过期 token 更新新 Attempt 的结果。

SQL 实现必须使用括号表达领取条件：

```sql
(
  status IN ('NEW', 'RETRYING') AND next_execute_at <= now()
)
OR (
  status = 'RUNNING' AND lease_until < now()
)
```

只有 Attempt 已进入 `STARTED` 才表示请求已交给 HTTP Client，之后结果可能未知并消耗发送预算。接管过期 `STARTED` 保持相同 DeliveryId；`maxRetry` 表示首次发送之外允许的自动额外发送次数，自动预算为 `1 + maxRetry`，再加经过审计的 `manualSendGrantCount`。预算耗尽分支只写 `ABANDONED + FAILED_UNCONFIRMED` 并结束本次领取，绝不创建新 Attempt。

KMS 解密、固定策略读取、DNS/SSRF/IP Pinning 等发送前失败记为 `PREPARATION_FAILED`，不消耗 `maxRetry`。它们使用独立的 `maxPreparationRetry=3 + manualPreparationGrantCount` 和固定间隔 `1s/5s/30s`；耗尽后任务进入 `FAILED_PREPARATION` 并告警，禁止无限重试。`STARTED` 的边界固定为“完整请求已准备好、即将交给 HTTP Client”；从该点起即使 Connection Refused 也保守计入发送预算。

HTTP 发送成功后、数据库确认前崩溃时仍可能再次发送，因此基础设施语义明确为至少一次，不承诺 exactly-once。每次请求携带：

```http
X-Mock-Callback-Delivery-Id: delivery-xxx
X-Mock-Callback-Attempt: 2   # sendAttemptNo，不是 preparation attemptNo
X-Mock-Flow-Generation: 3
```

接收方可按 DeliveryId 幂等。平台区分“场景主动重复”（不同 DeliveryId）与“故障重投”（相同 DeliveryId、不同 Attempt）。

### 8.4 重试

- 仅网络错误、连接超时、HTTP 5xx 默认重试。
- HTTP 4xx 默认失败，不重试；场景可显式配置允许重试的状态码。
- 使用定义中的固定重试间隔，不引入复杂退避策略。
- 人工 Retry 只接受终态 Task，要求 `mock:callback:retry`、二次确认、唯一 requestId 和同步审计。对 `FAILED/FAILED_UNCONFIRMED`，事务内把 `manual_send_grant_count + 1` 后转 RETRYING；对 `FAILED_PREPARATION`，把 `manual_preparation_grant_count + 1` 后转 RETRYING。存在未消费 Grant 或 Task 非终态时返回冲突，不能预先累积多个 Grant。每次管理请求只授权一次额外 Attempt，不重写历史结果，不把 maxRetry 改成无限；默认立即执行，管理请求可指定 `delayMs` 但只能在 0～300000ms。

### 8.5 签名

签名策略由管理员预先注册：

```text
policy_id
algorithm
secret_ref
timestamp_header
nonce_header
signature_header
canonicalization_rule
```

Scenario 只能引用已审批/发布的 `signaturePolicyVersionId` 和 `allowlistPolicyVersionId`，不能保存明文 Secret。Task 固定具体 Version，Worker 从固定 Release Snapshot 读取算法、`secret_ref` 和白名单，再从公司密钥服务读取 Secret。

### 8.6 SSRF 防护

Callback URL 必须满足：

1. Task 创建事务内只做纯计算：URI 语法、Scheme、禁止 user-info、规范化 Host/Port 和精确 Allowlist 匹配，不执行 DNS 或任何外部 I/O；
2. Worker 每个 PREPARING Attempt 都重新解析 DNS 并检查全部候选地址；
3. 禁止环回、链路本地、云元数据和未授权内网地址；
4. 校验后把本次连接绑定到选定 IP，同时保留原 Host Header 和 TLS SNI，禁止 HTTP Client 再次独立解析 Host；
5. 默认禁止重定向；若允许，每一跳重新执行解析、网段检查和 IP 绑定；
6. 连接使用独立 HTTP Client，不读取系统代理和用户提供代理；
7. DNS TTL 不跨 Attempt 复用，每次重试重新校验；
8. Callback 发送紧邻执行前再次校验 Task fencing/cancel 状态；
9. 出站网络策略只放行批准目标。

控制台可以在事务外提供 DNS 预检，但结果只用于提示，不作为发送授权；真正授权永远以每次 Worker PREPARING 的重新解析、网段检查和 IP Pinning 为准。这样 DNS 抖动不会占用 Flow/Request Execution 行锁。

### 8.7 Callback 数据保护和清理

- `callback_url`、`headers_json`、`payload` 使用公司 KMS 管理的信封加密列保存；表中记录 `encryption_key_id`。
- 管理查询默认只返回 Host、Path 摘要和脱敏 Payload；查看解密原文需要单独权限并记录审计。
- Task 增加 `expire_at`，默认保留 30 天；Attempt 不单独设置 TTL，随所属 Task 按外键/批处理级联删除。到期清理先删 Attempt 再删 Task，避免孤儿记录。
- Signature Secret 只保存 `secret_ref`，不进入 Task、Attempt、Request Log 或 Audit Before/After。
- KMS 不可用时不发送 Callback，按发送前准备失败进入 RETRYING/FAILED_PREPARATION 并告警，不消耗发送预算，不能降级为明文。

### 8.8 Callback 可观测性

```text
mock_callback_created_total{provider,api}
mock_callback_attempt_total{provider,api,result}
mock_callback_retry_total{provider,api}
mock_callback_duration_ms{provider,api}
mock_callback_queue_delay_ms{provider,api}
mock_callback_stuck_total
```

---

## 9. Contract 与 Scenario 管理

### 9.1 Provider

```text
Provider Code
Provider Name
Owner
Status
```

Provider 是跨环境逻辑实体。每个环境保存 Real Base URL、Auth Type 等元数据，并关联已发布的 Provider Environment、SDK Header Filter、Callback Allowlist/Signature Policy Version。真实 Base URL 只能由管理员维护，仅用于展示、SDK 配置核对和安全审计，Runtime 不提供真实代理功能；高风险规则不得作为可原地修改的 JSON 字段维护。

### 9.2 API

```text
Provider
API Code
API Name
HTTP Method
Path
Content-Type
Request Schema
Response Schema
Error Codes
Signature Rule Metadata
Owner
Status
```

Provider + API Code 唯一。

API 页面展示当前发布 Contract 的 Business Key Extractor，但 Runtime 不读取 API 表中的可变提取器。

### 9.3 Contract Version

生命周期：

```text
DRAFT -> VALIDATED -> PUBLISHED -> DEPRECATED
```

每次修改生成新版本，不原地修改已发布版本。Contract Version 包含：

```text
request_schema
response_schema
examples
error_codes
business_key_extractor
signature_metadata
checksum
source_type
source_file_hash
```

支持相邻版本字段级 Diff：新增、删除、类型变化、required 变化、枚举变化。

### 9.4 OpenAPI/JSON Schema 导入

MVP 仅支持上传本地文件，不支持服务端从任意 URL 下载。

```text
OpenAPI 3.0 JSON/YAML
JSON Schema Draft 7 或项目统一版本
文件最大 5MB
拒绝 ZIP/GZIP 等压缩包和上传 Content-Encoding
禁止外部 $ref
本地 $ref 最大深度 32、总解析次数 1000
YAML Alias 默认禁用（若解析库不能禁用则上限 10）
JSON/YAML 最大嵌套深度 64、总节点数 100000、最大 Code Points 5000000
单文件解析/校验超时 3 秒
```

导入使用开启安全限制的 Jackson/SnakeYAML 等解析器，放入独立受限线程池（MVP 默认 2 线程、队列 20），不在管理 API 工作线程内无限解析。节点、Alias、深度、引用、大小、队列或超时任一超限都返回明确 Validation Error，不生成半成品。具体依赖版本和约束 API 在 M0 用恶意样本实测后锁定。

导入只生成草稿，用户确认 Provider/API Code、负责人和业务键后才能 Validate。

### 9.5 Flow Definition 生命周期

```text
DRAFT -> VALIDATED -> APPROVED -> PUBLISHED -> DEPRECATED
```

Flow Definition Version 管理 Participant API、角色、版本化 Business Key Extractor、Variables、Transitions 和 TTL。Scenario Version 只能引用 `APPROVED` 或已 `PUBLISHED` 的 Flow Definition Version；Scenario 只保存引用，不嵌入或复制 Flow 定义。Release 只能包含 `APPROVED/PUBLISHED` 的 Flow Definition Version，首次随成功 Activation 被引用后标记为 `PUBLISHED`。涉及不兼容业务键变更时必须使用新 Flow Code。

### 9.6 Scenario 生命周期

```text
DRAFT
  -> VALIDATED
  -> PENDING_APPROVAL
  -> APPROVED
  -> PUBLISHED（由 Release 引用）
  -> DISABLED
```

更新已发布 Scenario 时生成新的 Scenario Version，旧 Release 继续引用旧版本。

### 9.7 Scenario Version

```json
{
  "scenarioCode": "sc-esign-processing",
  "provider": "esign",
  "apiCode": "contract.query",
  "contractVersion": 3,
  "flowDefinitionVersion": 3,
  "priority": 200,
  "effectiveFrom": "2026-08-01T00:00:00+08:00",
  "effectiveTo": "2026-12-31T23:59:59+08:00",
  "scope": {
    "environment": "TEST",
    "apps": ["pomp-power"],
    "tenants": ["tenant-a"],
    "testAccounts": ["tester-01"]
  },
  "matchRules": [],
  "response": {},
  "callbacks": []
}
```

### 9.8 Validate

发布前必须完成：

- Contract 存在且已发布；
- Method/Path 与 API 一致；
- Scope 非空且不包含 PROD；
- TestAccount/Tenant 未越过操作者权限；
- Match Rule 类型、长度和操作符合法；
- JSONPath/Regex 可编译；
- 模板变量合法且能用 Sample Context 渲染；
- Response 符合 Contract Response Schema；
- Flow 状态和 Transition 合法；
- Flow Graph 无环，TransitionId 唯一；
- Transition Priority 存在且同事件选择结果确定；
- Participant API Role、Business Key Extractor 和 Flow Code 兼容性合法；
- Variable 声明、初始提取和 Assignment 类型合法；
- 不兼容业务键变更不存在未过期旧 Flow，或已改用新 Flow Code；
- 新 Release 未删除任何仍被未过期 Flow 使用的 API -> Flow Locator；
- Flow TTL 在限制内；
- Callback URL、签名策略、次数和延迟合法；
- Callback/Provider Environment 等 Release 类安全策略 Version 已 PUBLISHED 且 Binding=BOUND；
- 同 API 场景冲突检测；
- 响应、Header、Payload 不含已知密钥。

Validation 结果必须保存，审批和发布引用同一个 checksum；审批后内容变化会使审批失效。

---

## 10. 发布、审批与回滚

### 10.1 发布粒度

MVP 固定为：

```text
Environment + App
```

一个 Release Snapshot 可以包含多个 Provider/API 的 Scenario Version，但只服务一个 App 和一个非生产 Environment。

### 10.2 不可变 Snapshot

Snapshot 包含：

```text
releaseId
environment
app
contractVersions
compiledContracts（Method/Path/Content-Type、Request/Response Schema Validator、Business Key Extractor 引用）
scenarioVersions
compiledMatchers
compiledTemplates
flowDefinitions
callbackDefinitions
releaseSecurityPolicyVersions（不含独立实时生效的 Active Admission ACL）
createdAt
checksum
schemaVersion
signature
signatureKeyId
signatureAlgorithm
```

Snapshot 生成后不可修改。Control 对 canonical snapshot bytes 计算 checksum，再使用公司密钥服务中的发布私钥签名；Runtime 按 `signatureKeyId` 获取受控公钥验签。密钥轮换时，新旧公钥至少并存到所有引用旧 Key 的 Release 超出保留期。

Runtime 的请求路径不得读取 `mock_api`、`mock_contract_version`、`mock_scenario_version` 或安全策略等可变管理表。请求入口分别固定“当前签名 Admission Snapshot”和“当前 Release Snapshot”：前者只做实时身份准入，后者负责 Method/Path/Content-Type、Schema、Business Key、Scenario Scope、匹配、模板、Flow 和 Callback 定义。除 Admission ACL 外的 Runtime 策略都使用同一 Release Snapshot；缺少所需编译 Contract 或策略时整次 Snapshot 加载失败，不做降级拼装。

### 10.3 审批

策略：

- 默认 TEST 环境支持单人审批；高风险 Provider 可配置双人复核。
- 开启双人复核时，最后修改人不能审批自己的版本。
- 审批针对 checksum，不针对可变对象 ID。
- App ACL、Provider Environment、SDK Header Filter、Callback Allowlist、Callback Signature、FALLBACK_REAL 等高风险配置必须以不可变安全策略版本提交审批，禁止直接修改活动投影或配置中心原始值。
- 每次提交生成 `ApprovalRequest(requiredCount, objectChecksum, policyCode)`，每位评审人的决定单独保存。
- 同一评审人不能重复计数；任一有效拒绝终止当前请求；内容 checksum 变化后原审批全部失效。

### 10.4 发布过程

```text
1. 用户选择 APPROVED Scenario Version，并校验其 Contract Version 为 PUBLISHED、Flow Definition Version 为 APPROVED/PUBLISHED；所有 Release 类安全策略 Version 必须已经 PUBLISHED 且 Binding=BOUND，APPROVED 但未发布/绑定的策略不得进入 Snapshot。
2. Control 生成 canonical Snapshot、checksum 和 signature。
3. 写 MySQL Release 和 Release Item，状态 PREPARING。
4. 将签名 Snapshot 预写入 Redis immutable snapshot key，读回并验签。
5. 更新 Release 为 READY。
6. 从服务发现获取当前 REGISTERED + READY 的 Runtime Node 快照。
7. 开启 MySQL 事务并锁定 mock_active_release(environment,app)。
8. 校验 expectedActivationVersion；不一致则回滚，拒绝覆盖并发发布。
9. 更新权威 Active Release：releaseId=new、activationVersion=old+1、state=ACTIVATING。
10. 同一事务写 release_activation、activation_target_node、不可变 Audit 和 release_outbox。
11. 提交事务。到此发布意图和目标 Node 集合具备唯一、可恢复的权威事实。
12. Outbox Projector 幂等写 Redis Active Pointer，并发送失效通知。
13. Runtime 发现新 activationVersion，加载并验签 Snapshot，上报 runtime_activation_ack，同时更新对应 Target 状态。
14. 所有原始 Target 都已明确收敛到 READY/LEFT/WAIVED（等价于所有 `required=true` Target 均为 READY，所有 `required=false` Target 均为 LEFT/WAIVED）后，将 ACTIVATING/PARTIAL Activation 标记 APPLIED、Release 标记 PUBLISHED，把首次被引用的 APPROVED Scenario/Flow Definition Version 标记为 PUBLISHED；WAITING/FAILED 不计完成。安全策略 Version 不在此改变生命周期，只更新 Binding 的 currentEffectiveReleaseId/effectiveActivationVersion/effectiveAt。之后新加入的节点在接流量前加载当前权威版本。
```

发布 API 返回 `ACTIVATING` 或 `PUBLISHED`，不把 MySQL 已提交但尚未投影完成的状态报告为失败。控制台展示各 Runtime 节点的生效版本。

Target Node 规则：

- Activation 事务提交后目标集合不可静默增删；
- Target 在 Ack 前被服务发现确认已注销且持续 10 秒，在同一事务中写 `status=LEFT、required=false` 和审计，并重新聚合 Activation；
- 仍注册但加载失败或不健康的 Target 保持 required，5 秒后 Activation 为 PARTIAL，该节点从负载均衡摘除；
- 扩容的新节点不属于历史 Target，但必须加载当前权威版本并验签成功后才能上报 READY、接入流量；
- 人工豁免 Target 仅在服务治理已经证明该节点从业务流量摘除后允许，需要 `mock:release:override` 权限、原因和二次确认；同一事务写 `status=WAIVED、required=false、waivedBy/waiveReason`、Audit 并重新聚合 Activation。若全部原始 Target 已为 READY/LEFT/WAIVED，则 PARTIAL 转 APPLIED；未摘流节点不得豁免，也不得通过忽略 FAILED/WAITING Target 完成发布。

本方案的发布语义是：

```text
MySQL 权威状态单行串行化
Redis 投影幂等可重建
单个请求内强一致
健康 Runtime 集群 5 秒内有界最终一致
```

因此本文不再使用“集群全局原子切换”表述。

### 10.5 故障恢复

- PREPARING 超时且未产生 Activation：标记 FAILED，删除未引用的 Snapshot 缓存。
- MySQL Activation 已提交但 Outbox 未投影：Projector 或 Reconciler 重放同一 activationVersion。
- Redis 投影成功但确认前崩溃：Reconciler 根据 MySQL Active Release 重写相同 Pointer，操作幂等。
- Runtime 加载新 Snapshot 校验失败：继续使用 Last Known Good，并上报高优告警；不静默加载部分内容。
- 健康 Runtime 超过 5 秒未加载权威版本：摘除该节点，Activation 保持 PARTIAL 并阻断下一次发布；节点恢复并 READY、已摘流后按审计流程 WAIVED，或人工回滚后才能解除阻断。
- Redis 数据丢失：Control 从 MySQL `mock_active_release`、Release Snapshot 和未完成 Outbox 唯一重建缓存和 Active Pointer。

### 10.6 回滚

```text
选择历史 READY/PUBLISHED Release
  -> 权限和审批检查
  -> 校验 Snapshot checksum/signature
  -> 校验所有未过期 Flow 所需 API -> Flow Locator/Extractor 兼容性
  -> 使用与发布相同的 MySQL Active Release 事务创建新 activationVersion
  -> Outbox 投影 Redis Pointer
  -> Runtime 上报生效
```

回滚不会删除新 Release，不会修改已有 Flow Instance。

回滚兼容性校验失败时返回受影响的 FlowCode、Participant API、Flow 数量和最早过期时间。禁止提供绕过校验的 Force Rollback。

---

## 11. 数据模型

以下为逻辑字段，实际 DDL 按公司数据库规范补充字符集、主键生成、审计字段和分库约束。

### 11.1 `mock_provider`

```text
id
provider_code              UNIQUE
provider_name
owner
status
created_by / created_at
updated_by / updated_at
```

Provider 是跨环境逻辑实体，环境地址和白名单不放在本表。

### 11.2 `mock_provider_environment`

```text
id
provider_id
environment
real_base_url              仅元数据
auth_type
provider_environment_policy_version_id
sdk_header_filter_policy_version_id
callback_allowlist_policy_version_id
callback_signature_policy_version_id
status
created_at / updated_at
UNIQUE(provider_id, environment)
```

本表是管理查询投影；高风险字段的权威内容在不可变 `mock_security_policy_version` 中，Runtime 不直接读取本表。

### 11.3 `mock_app_acl`

```text
id
app_code
environment
provider_id
api_scope_json
tenant_scope_json
test_account_scope_json
app_acl_policy_version_id
version
status
created_by / created_at
updated_by / updated_at
UNIQUE(app_code, environment, provider_id)
INDEX(app_code, environment, status)
```

Scope 字段是当前 PUBLISHED `APP_ACL` Version 的只读查询投影，唯一权威是 Security Policy Version/Binding；不得通过本表 CRUD 绕过审批。

`mock_app_acl` 可以按 Provider 展开多行便于页面查询，但这些行必须由同一个 `scope_key=environment+app` 的完整 APP_ACL Aggregate Version 原子重建。`UNIQUE(app_code,environment,provider_id)` 只约束派生投影，不代表存在多个可独立发布的 Binding。

### 11.4 `mock_api`

```text
id
provider_id
api_code
api_name
http_method
path
content_type
owner
status
created_at / updated_at
UNIQUE(provider_id, api_code)
INDEX(provider_id, status)
```

API 只保存稳定身份和当前展示信息；业务键提取器不作为 Runtime 的可变来源。

### 11.5 `mock_contract_version`

```text
id
api_id
version_no
status
request_schema_json
response_schema_json
examples_json
error_codes_json
business_key_extractor_json
signature_metadata_json
source_type
source_file_hash
checksum
created_by / created_at
published_by / published_at
UNIQUE(api_id, version_no)
UNIQUE(api_id, checksum)
```

### 11.6 `mock_flow_definition`

```text
id
provider_id
flow_code
flow_name
current_draft_version
status                     ENABLED / DISABLED（逻辑 Flow Definition 整体状态）
created_by / created_at
updated_by / updated_at
UNIQUE(provider_id, flow_code)
```

### 11.7 `mock_flow_definition_version`

```text
id
flow_definition_id
version_no
status                     DRAFT / VALIDATED / APPROVED / PUBLISHED / DEPRECATED
initial_state
ttl_seconds
participant_apis_json
variables_json
transitions_json
compiled_json
checksum
validation_status
created_by / created_at
UNIQUE(flow_definition_id, version_no)
UNIQUE(flow_definition_id, checksum)
```

Participant 中引用固定 Contract Version 和版本化 Business Key Extractor。Runtime 只读取 Release Snapshot 中的该版本。

### 11.8 `mock_scenario`

```text
id
scenario_code              UNIQUE
scenario_name
provider_id
api_id
current_draft_version
status                     ENABLED / DISABLED（逻辑 Scenario 整体状态）
created_by / created_at
updated_by / updated_at
```

### 11.9 `mock_scenario_version`

```text
id
scenario_id
version_no
status                     DRAFT / VALIDATED / PENDING_APPROVAL / APPROVED / PUBLISHED / DISABLED
contract_version_id
flow_definition_version_id  nullable
priority
effective_from / effective_to
scope_json
match_rule_json
response_json
callback_json
compiled_json
checksum
validation_status
validation_result_json
approval_request_id        nullable
approved_at                nullable
published_at               nullable
disabled_at                nullable
created_by / created_at
UNIQUE(scenario_id, version_no)
UNIQUE(scenario_id, checksum)
```

Scenario Version 只用 `flow_definition_version_id` 引用完整 Flow Definition；不保存任何内嵌 Flow 定义副本，避免同一状态机出现两个权威来源。Scenario 自身只定义该 API 的匹配、响应和 Callback Binding。审批、Release 选取、已发布不可修改等规则都检查 Version `status`；根表 `mock_scenario.status` 只控制该逻辑 Scenario 是否整体启用，不能替代 Version 生命周期。

### 11.10 `mock_approval_request`

```text
id
object_type
object_id
object_checksum
policy_code
required_count
status
requested_by / requested_at
completed_at
INDEX(object_type, object_id, status)
UNIQUE(object_type, object_id, object_checksum, policy_code)
```

### 11.11 `mock_approval_decision`

```text
id
approval_request_id
reviewer
decision                   APPROVE / REJECT
comment
decided_at
UNIQUE(approval_request_id, reviewer)
INDEX(approval_request_id, decision)
```

服务层强制最后修改人不得审批自己的对象；APPROVE 人数达到 `required_count` 才完成审批，任一有效 REJECT 使请求失败。

### 11.12 `mock_release`

```text
id
release_code               UNIQUE
environment
app_code
status
snapshot_json              或受控对象存储引用
checksum
schema_version
signature
signature_key_id
signature_algorithm
release_note
created_by / created_at
published_by / published_at
INDEX(environment, app_code, created_at)
```

### 11.13 `mock_release_item`

```text
id
release_id
item_type                  CONTRACT / FLOW_DEFINITION / SCENARIO / SECURITY_POLICY
object_id
object_version_id
UNIQUE(release_id, item_type, object_version_id)
INDEX(release_id)
```

### 11.14 `mock_active_release`

```text
environment
app_code
release_id
activation_version
state                      ACTIVATING / APPLIED / PARTIAL
updated_at
PRIMARY KEY(environment, app_code)
```

这是 Active Release 的 MySQL 权威状态。

### 11.15 `mock_release_activation`

```text
id
environment
app_code
from_release_id
to_release_id
from_activation_version
to_activation_version
action                     PUBLISH / ROLLBACK / RECOVER
status                     PENDING / PROJECTED / APPLIED / PARTIAL
request_id
operator
created_at / completed_at
INDEX(environment, app_code, created_at)
UNIQUE(request_id)
```

### 11.16 `mock_release_outbox`

```text
id
activation_id
aggregate_key              environment + app
activation_version
payload_json
status                     NEW / PROJECTED / FAILED
attempt_count
next_attempt_at
created_at / projected_at
UNIQUE(activation_id)
INDEX(status, next_attempt_at)
```

### 11.17 `mock_activation_target_node`

```text
id
activation_id
runtime_node_id
required
status                     WAITING / READY / FAILED / LEFT / WAIVED
captured_at
updated_at
waived_by / waive_reason
UNIQUE(activation_id, runtime_node_id)
INDEX(activation_id, required, status)
```

Target 集合与 Activation、Outbox 同事务保存，Control 重启后仍能判断完成条件。LEFT/WAIVED 转换必须同时把 `required=false` 并写 Audit；完成聚合要求所有 `required=true` 行均 READY，且所有 `required=false` 行只能为 LEFT/WAIVED，WAITING/FAILED 永不计完成。

### 11.18 `mock_runtime_activation_ack`

```text
id
environment
app_code
runtime_node_id
release_id
activation_version
status                     READY / FAILED
error_masked
reported_at
UNIQUE(environment, app_code, runtime_node_id, activation_version)
INDEX(environment, app_code, activation_version, status)
```

### 11.19 `mock_flow_instance`

```text
id
flow_key                   UNIQUE
environment
app_code
provider_code
flow_code
tenant_code
test_account
business_no_hmac
hmac_key_version
business_no_masked
release_id
flow_definition_version_id
flow_definition_checksum
generation
status                     ACTIVE / EXPIRED / DELETED
current_state
query_count
variables_json
version
pending_transition_id
next_transition_at
expire_at
created_at / updated_at
UNIQUE(environment, app_code, provider_code, flow_code, tenant_code, test_account, hmac_key_version, business_no_hmac)
INDEX(environment, app_code, provider_code, flow_code, current_state)
INDEX(hmac_key_version, business_no_hmac)
INDEX(next_transition_at, current_state)
INDEX(expire_at)
```

业务单号是否允许明文存储由数据分级决定；默认保存 HMAC 和脱敏值，不保存普通 SHA 或明文。查询按 `hmac_key_version` 使用当前及保留 Key 计算候选值。

### 11.20 `mock_request_execution`

```text
id
app_code
mock_request_id
execution_generation        default 1，过期原子复用时递增
request_fingerprint
status                     IN_TRANSACTION / COMPLETED
release_id
activation_version
scenario_version_id
flow_instance_id           nullable
flow_generation            nullable
transition_result_json
response_status
response_headers_encrypted
response_body_encrypted
fault_type
fault_duration_ms
side_effect_policy
encryption_key_id
expire_at
created_at / completed_at
UNIQUE(app_code, mock_request_id)
INDEX(status, created_at)
INDEX(expire_at)
```

`IN_TRANSACTION` 仅是首事务内部占位值，不作为跨事务可见状态；正常提交记录必须是 COMPLETED。并发同 ID 请求通过唯一键锁等待协议协调。

MVP 中 `mock_request_execution` 保持非分区 InnoDB 表，才能由 `UNIQUE(app_code, mock_request_id)` 提供跨日期全局幂等。使用 `expire_at` 索引按主键小批量清理（默认每批 1000 行）；不把日期加入唯一键，也不按日分区。若实测容量超出单表边界，只允许评审后按 `(app_code, mock_request_id)` 做 HASH 分区，并先在目标 MySQL 版本验证唯一键规则。

### 11.21 `mock_flow_event`

```text
id
event_id                   UNIQUE
flow_instance_id
flow_generation
source_type                SDK / TIMER / MANUAL
mock_request_id            nullable，仅 SDK
request_execution_generation nullable，仅 SDK
internal_execution_id      nullable，仅 TIMER / MANUAL
source_api_code            nullable，TIMER/MANUAL 可为空
transition_id
from_state
to_state
event_type
query_count
operator
event_at
INDEX(flow_instance_id, event_at)
UNIQUE(flow_instance_id, flow_generation, transition_id)
UNIQUE(flow_instance_id, mock_request_id, request_execution_generation, event_type)
UNIQUE(internal_execution_id)
```

非迁移查询 Event 的 `transition_id` 为 NULL；状态迁移必须有稳定 ID。数据库唯一索引允许多个 NULL；服务层按 `source_type` 强制 SDK 填 `mock_request_id + request_execution_generation`，Timer/Manual 只填 `internal_execution_id`。因此 24 小时后同一 MockRequestId 开启新 Execution Generation 不会与历史 Event 唯一键冲突。

### 11.22 `mock_callback_task`

```text
id
task_id                    UNIQUE
delivery_id                UNIQUE
flow_event_id
flow_instance_id
flow_generation
release_id
snapshot_checksum
callback_definition_id
delivery_index
provider_code
api_code
callback_url_encrypted
http_method
headers_json_encrypted
payload_encrypted
rendered_payload_hash
encryption_key_id
callback_signature_policy_version_id
callback_allowlist_policy_version_id
status                     NEW / RETRYING / RUNNING / SUCCESS / FAILED / FAILED_PREPARATION / FAILED_UNCONFIRMED / CANCELLED
next_execute_at
send_attempt_count / max_retry
preparation_retry_count / max_preparation_retry
manual_send_grant_count      default 0
manual_preparation_grant_count default 0
lease_owner / lease_until
fencing_token
last_http_status
last_error_masked
expire_at
created_at / updated_at
UNIQUE(flow_event_id, flow_generation, callback_definition_id, delivery_index)
INDEX(status, next_execute_at)
INDEX(lease_until)
INDEX(expire_at)
```

### 11.23 `mock_callback_attempt`

```text
id
task_id
delivery_id
attempt_no
send_attempt_no            nullable，只有 STARTED 及其终态存在
fencing_token
status                     PREPARING / PREPARATION_FAILED / ABANDONED_PREPARATION / STARTED / SUCCESS / FAILED / ABANDONED / CANCELLED
started_at / completed_at
http_status
result
delivery_certainty         NEVER_SENT / CONFIRMED_RESPONSE / UNKNOWN，nullable
error_masked
duration_ms
UNIQUE(task_id, attempt_no)
UNIQUE(task_id, send_attempt_no)
```

### 11.24 `mock_request_log`

```text
id
mock_request_id
trace_id
environment
app_code
tenant_code
test_account_masked
provider_code
api_code
scenario_id
scenario_version_id
release_id
flow_key
business_no_hmac
hmac_key_version
http_method
path
request_summary
response_summary
http_status
match_result
duration_ms
error_code
expire_at
created_at
PRIMARY KEY(created_at, id)
INDEX(id)
INDEX(app_code, mock_request_id, created_at)
INDEX(trace_id)
INDEX(hmac_key_version, business_no_hmac)
INDEX(provider_code, api_code, created_at)
INDEX(scenario_id, created_at)
INDEX(expire_at)
```

Request Log 记录每次到达 Runtime 的传输请求，同一 MockRequestId 的网络重试允许有多条记录；它不是幂等权威，去重由 `mock_request_execution` 负责。因此本表不设跨分区业务唯一键，按 `created_at` 做日分区，并把分区列纳入 `PRIMARY KEY(created_at,id)` 以满足 MySQL 约束；`id` 由应用生成 UUID，但数据库不把它声明为跨分区唯一。Request/Response Summary 分别限制为 4KB；到期按分区删除，不执行大范围逐行 DELETE。BusinessNo 查询使用当前及保留的 HMAC Key 分别计算候选值，并连同 `hmac_key_version` 精确匹配。

### 11.25 `mock_audit_log`

```text
id
request_id
operator
action
object_type
object_id
object_checksum
before_json_masked
after_json_masked
result
reason
created_at
INDEX(operator, created_at)
INDEX(object_type, object_id, created_at)
UNIQUE(request_id, action, object_id)
```

审批、发布、回滚、权限相关审计必须与管理事务同步写入，不能通过易丢失的内存异步队列记录。

### 11.26 `mock_sdk_config_event`

```text
id
app_code
environment
sdk_instance_id
sdk_config_envelope_id
sdk_config_activation_id
old_config_version
new_config_version
security_policy_refs_json   nullable，记录本次原子快照使用的全部 policyId/version
status                      APPLIED / REJECTED
effective_at
error_masked
source_audit_ref
received_at
UNIQUE(sdk_config_activation_id, sdk_instance_id)
INDEX(app_code, environment, effective_at)
```

该表记录 SDK 实际生效结果，配置修改人和原始内容仍以 Apollo/Nacos 审计为准。

### 11.27 `mock_security_policy_version`

```text
id
policy_id
policy_type                APP_ACL / PROVIDER_ENVIRONMENT / SDK_HEADER_FILTER / CALLBACK_ALLOWLIST / CALLBACK_SIGNATURE / SDK_FALLBACK_REAL
scope_key                  规范化 Scope；APP_ACL 固定为 environment + app，其他类型按 app/provider/api 需要定义
version_no
config_json_encrypted
checksum
status                     DRAFT / VALIDATED / APPROVED / PUBLISHED / DEPRECATED
signature
signature_key_id
source_audit_ref
approval_request_id
created_by / created_at
published_by / published_at
UNIQUE(policy_id, version_no)
UNIQUE(policy_type, scope_key, checksum)
INDEX(policy_type, scope_key, status)
```

高风险策略内容不可原地更新；修改必须创建新 Version、Validate、按 checksum 审批并发布。`APPROVED` 之后内容变化会生成新 checksum 并使原审批失效。

### 11.28 `mock_security_policy_binding`

```text
id
policy_type
scope_key
desired_policy_version_id
effective_policy_version_id nullable
effect_mode                LIVE_ADMISSION / RELEASE / SDK_CONFIG
status                     PUBLISHING / BOUND / INACTIVE
binding_version
desired_at
bound_at
first_effective_release_id nullable
current_effective_release_id nullable
effective_activation_version nullable
sdk_effective_config_version nullable
effective_at               nullable
updated_by / updated_at
UNIQUE(policy_type, scope_key)
INDEX(desired_policy_version_id)
INDEX(effective_policy_version_id)
```

Binding 中 `desired_policy_version_id` 是已提交的发布意图，`effective_policy_version_id` 是外部投影或本地绑定完成的版本；二者不同即 PUBLISHING。Binding 不等于所有消费者已生效，只能由安全策略发布流程更新，普通 Provider/App ACL 编辑 API 无权直接修改。控制台分开展示 Version `PUBLISHED`、Binding `BOUND` 和消费者 `EFFECTIVE`：Release 类策略记录首次/当前引用它的 ReleaseId 与 ActivationVersion；SDK 类策略按 `sdk-config-event` 记录 ConfigVersion；Admission ACL 只有全部 READY Runtime 已上报该 Binding Version，或未上报节点在 60 秒后 fail-closed/摘除，才记录 effectiveAt。Runtime 的 Scenario/Callback 使用 Release Snapshot 固定策略，SDK 使用签名 Config Envelope，Runtime 准入使用独立 Active Admission Snapshot。

对 `APP_ACL`，`UNIQUE(policy_type,scope_key)` 因此等价于每个 Environment+App 一个 Aggregate Binding。APP_ACL 管理 API 必须基于 expectedBindingVersion 对完整规则集合做乐观并发更新，禁止对 Provider 子行做独立 Publish。

### 11.29 `mock_config_publish_outbox`

```text
id
aggregate_type             ADMISSION_BINDING / SDK_CONFIG_ACTIVATION
aggregate_id               bindingId:bindingVersion 或 activationId
target_type                APOLLO / NACOS / REDIS_RUNTIME_ADMISSION
target_namespace
payload_encrypted
checksum
status                     NEW / PUBLISHED / FAILED
attempt_count
next_attempt_at
last_error_masked
created_at / published_at
UNIQUE(aggregate_type, aggregate_id, target_type, target_namespace)
INDEX(status, next_attempt_at)
```

`APP_ACL` Binding 通过 `REDIS_RUNTIME_ADMISSION` 触发首次短租约；`SDK_CONFIG_ACTIVATION` 通过 Apollo/Nacos 发布包含 ActivationId 和完整 SDK Envelope 的签名 Config Activation Wrapper。SDK Config Publish 在事务前预分配 ActivationId、生成并签名 Wrapper；事务内把 Wrapper 的精确 canonical bytes/checksum 写入 `payload_encrypted/checksum`。事务提交后 Adapter 只读并发布这些字节，使重试可幂等重放，禁止保存草稿或改写 publishedAt/checksum/signature。Admission 首次成功后，Lease Projector 独立每 20 秒重新读取当前 MySQL desired Binding 并续签，不靠重复消费旧 Outbox 续命。Projector 失败时旧 Admission 只活到签名 notAfter；旧 SDK Envelope 继续生效，Activation 保持 PENDING/PARTIAL 并告警。

### 11.30 `mock_runtime_policy_ack`

```text
id
runtime_node_id
binding_id
environment
app_code
policy_type                APP_ACL
policy_version_id
binding_version
status                     READY / FAILED / STALE
error_masked
reported_at
UNIQUE(runtime_node_id, binding_id, binding_version)
INDEX(binding_id, binding_version, status)
```

Runtime 每次 Admission Snapshot 切换后按稳定 `bindingId + bindingVersion` 幂等上报。Environment/App 是查询冗余，EFFECTIVE 聚合严格按 Binding 计算，不能把同一节点为另一个 App、相同数字版本的 ACK 计入。Control 结合服务发现中的 READY Node 计算 EFFECTIVE；超过 60 秒仍未确认的 Node 必须 fail-closed/摘除，不能通过忽略节点制造“已生效”。

### 11.31 `mock_sdk_config_envelope`

```text
id
app_code
environment
config_version
routing_json
security_policy_refs_json
security_policy_payloads_encrypted
effective_at
expire_at                 nullable
checksum
signature
signature_key_id
validation_status
status                    DRAFT / VALIDATED / PENDING_APPROVAL / APPROVED / PUBLISHING / PUBLISHED / DEPRECATED
approval_request_id
source_audit_ref
created_by / created_at
published_by / published_at
UNIQUE(app_code, environment, config_version)
UNIQUE(app_code, environment, checksum)
```

Envelope 是 SDK 可应用配置的最小不可变发布物。任何模式、比例、Provider/API Scope、Header Filter 或 Fallback 引用变化都创建新 configVersion；启用 MOCK/CANARY 前必须包含适用的 PUBLISHED+BOUND `SDK_HEADER_FILTER`，启用 FALLBACK_REAL 还必须包含 PUBLISHED+BOUND fallback Policy。Activation Outbox 始终发布包含 ActivationId 和该完整 Envelope 的签名 Config Activation Wrapper，禁止按字段覆盖；Wrapper 的签名不改写 Envelope checksum 或审批结果。

### 11.32 `mock_sdk_config_activation`

```text
id
app_code
environment
sdk_config_envelope_id
from_config_version
to_config_version
status                    PENDING / PROJECTED / APPLIED / PARTIAL
request_id
operator
created_at / completed_at
UNIQUE(request_id)
UNIQUE(app_code, environment, to_config_version)
INDEX(app_code, environment, created_at)
```

### 11.33 `mock_sdk_config_target_instance`

```text
id
activation_id
sdk_instance_id
required
status                    WAITING / APPLIED / REJECTED / LEFT / WAIVED
captured_at
updated_at
waived_by / waive_reason
UNIQUE(activation_id, sdk_instance_id)
INDEX(activation_id, required, status)
```

动态 Target 集合不属于 Envelope checksum。Publish 前从服务发现捕获目标，随后在同一个 MySQL 事务中写 Activation、Target Instance、Audit 和 Config Publish Outbox；因此审批后的不可变 Envelope 不被改写，Control 重启后仍有唯一完成条件。LEFT/WAIVED 转换必须同时把 `required=false` 并写 Audit；完成聚合要求所有 `required=true` 行均 APPLIED，且所有 `required=false` 行只能为 LEFT/WAIVED，WAITING/REJECTED 永不计完成。

### 11.34 `mock_active_sdk_config`

```text
app_code
environment
desired_envelope_id
desired_config_version
last_applied_envelope_id    nullable
last_applied_config_version nullable
activation_id
state                       ACTIVATING / APPLIED / PARTIAL
updated_at
PRIMARY KEY(app_code, environment)
```

这是 SDK Config 的 MySQL 权威意图。Publish 事务锁定并更新 desired Config，再写 Activation/Targets/Outbox；ConfigPublisherAdapter 把它投影到 Apollo/Nacos。配置中心 Key 丢失时，恢复链路固定为 `mock_active_sdk_config.activation_id -> mock_config_publish_outbox(aggregate_type=SDK_CONFIG_ACTIVATION).payload_encrypted`，只重放原始 Config Activation Wrapper canonical bytes，不得从 Envelope 重新生成、重新签名，也不能从任一 SDK 实例反向覆盖权威值。MVP 不清理已经 PUBLISHED 的 SDK_CONFIG_ACTIVATION Outbox，确保当前配置和历史回滚依据始终保留精确发布字节。

---

## 12. 管理 API

统一前缀：

```text
/api/admin/v1
```

### Provider / API

```text
GET    /providers
POST   /providers
PUT    /providers/{id}
GET    /providers/{id}/environments
GET    /providers/{id}/apis
POST   /apis
PUT    /apis/{id}
GET    /app-acls
```

Provider/API 的普通元数据可以由上述接口更新；Provider Environment 和 App ACL 内容属于高风险策略，只能走下述版本化安全策略接口。`GET /app-acls` 返回当前活动 Binding 的只读投影。

### Contract

```text
GET    /apis/{apiId}/contracts
POST   /apis/{apiId}/contracts
POST   /contracts/import
POST   /contracts/{id}/validate
POST   /contracts/{id}/publish
GET    /contracts/{id}/diff?compareTo={versionId}
```

### Flow Definition

```text
GET    /flow-definitions
GET    /flow-definitions/{id}
POST   /flow-definitions
POST   /flow-definitions/{id}/versions
POST   /flow-definition-versions/{id}/validate
POST   /flow-definition-versions/{id}/submit-approval
```

### Scenario

```text
GET    /scenarios
GET    /scenarios/{id}
POST   /scenarios
PUT    /scenarios/{id}
POST   /scenarios/{id}/versions
POST   /scenario-versions/{id}/validate
POST   /scenario-versions/{id}/submit-approval
POST   /scenario-versions/{id}/copy
POST   /scenarios/{id}/disable
```

已发布 Version 不提供 PUT/DELETE。

### Approval

```text
GET    /approvals
POST   /approvals/{id}/approve
POST   /approvals/{id}/reject
```

### Security Policy

```text
GET    /security-policies
GET    /security-policies/{policyId}/versions
POST   /security-policies
POST   /security-policies/{policyId}/versions
POST   /security-policy-versions/{id}/validate
POST   /security-policy-versions/{id}/submit-approval
POST   /security-policy-versions/{id}/publish
GET    /security-policy-versions/{id}/diff?compareTo={versionId}
```

策略类型覆盖 App ACL、Provider Environment、SDK Header Filter、Callback Allowlist、Callback Signature 和 SDK FALLBACK_REAL。Publish 必须验证同 checksum 的审批结果，并按消费方式处理：

- `APP_ACL`：一个 MySQL 事务写 Audit、PUBLISHED Version、Binding 的新 desiredPolicyVersion/bindingVersion 和 Admission Outbox；Projector 只投影已提交 desired Binding，成功后更新 effectivePolicyVersion/BOUND，Runtime 在签名 60 秒租约内应用；
- `SDK_HEADER_FILTER`、`SDK_FALLBACK_REAL`：同事务写 Audit、PUBLISHED Version 和 BOUND Binding，但不单独写配置中心；只有被完整 `SDK_CONFIG_ENVELOPE` 引用并由 ConfigPublisherAdapter 发布后才显示 EFFECTIVE；
- `PROVIDER_ENVIRONMENT`、`CALLBACK_ALLOWLIST`、`CALLBACK_SIGNATURE`：同事务写 Audit、PUBLISHED Version 和 BOUND Binding，但只有被 Release 引用并成功 Activation 后才显示 EFFECTIVE。

Outbox 失败时必须告警且不允许把 PUBLISHING 展示为 EFFECTIVE。SDK/Release 消费者继续使用旧已生效版本；Admission 只能使用旧信封剩余的签名租期，`notAfter` 后必须 fail-closed，不能为旧授权续租。上述接口不提供对 PUBLISHED Version 的 PUT/DELETE。

### SDK Config Envelope

```text
GET    /sdk-config-envelopes?app={app}&environment={env}
GET    /sdk-config-envelopes/{id}
POST   /sdk-config-envelopes
POST   /sdk-config-envelopes/{id}/validate
POST   /sdk-config-envelopes/{id}/submit-approval
POST   /sdk-config-envelopes/{id}/publish
POST   /sdk-config-envelopes/{id}/rollback
GET    /sdk-config-envelopes/{id}/diff?compareTo={envelopeId}
```

Publish 流程：

```text
1. 只接受 checksum 未变化的 APPROVED Envelope，Target 不属于 checksum。
2. 预分配 ActivationId，从服务发现捕获当前 REGISTERED + READY 的 App Instance；用该 ActivationId、固定 publishedAt 和完整 Envelope 生成并签名 Config Activation Wrapper，得到不可变 canonical bytes/checksum。此时不发布外部配置。
3. MySQL 事务内锁定 mock_active_sdk_config、校验 expectedConfigVersion，使用预分配 ID 写 sdk_config_activation，更新 desired Envelope/ConfigVersion，并写全部 target_instance、Audit，以及保存 Wrapper 精确 canonical bytes/checksum 的 Config Publish Outbox，Envelope=PUBLISHING。若版本校验失败则事务回滚并丢弃该 Wrapper。
4. 事务提交后，ConfigPublisherAdapter 只读取 Outbox 已持久化的 Wrapper 字节并幂等写入 Apollo/Nacos，禁止重新生成或重新签名；成功后 Activation=PROJECTED、Envelope=PUBLISHED。
5. SDK APPLIED/REJECTED 事件按 activationId + sdkInstanceId 更新持久化 Target。
6. 全部原始 Target 都已收敛到 APPLIED/LEFT/WAIVED（等价于所有 `required=true` Target 均 APPLIED、所有 `required=false` Target 均 LEFT/WAIVED）后 Activation/Active SDK Config=APPLIED，更新 lastApplied 和相关 SDK Policy Binding 的 sdkEffectiveConfigVersion/effectiveAt；WAITING/REJECTED 不计完成，否则超时后为 PARTIAL。
```

Target 规则：实例从服务发现持续注销 10 秒后，才可在同一事务标 `LEFT、required=false` 并写 Audit；REJECTED 实例立即对现有 MOCK/CANARY 路由 FAST_FAIL；60 秒仍 WAITING/REJECTED 的实例必须由服务治理从业务流量摘除。`mock:sdk-config:override` 只能在实例已被证明摘除后，在同一事务写 `WAIVED、required=false、waivedBy/waiveReason`、Audit 并重新聚合 Activation。无法接入实例发现/摘流能力时，不得承诺安全策略 60 秒生效，M0 必须阻断该 App 的安全敏感 Envelope 发布。新实例加载当前 Envelope 前不得 READY，不能用一个实例 ACK 代表全量生效。

PARTIAL Activation 阻断下一次 SDK Config Publish，直到目标恢复、被证明摘流后 WAIVE，或回滚。Rollback 不重新使用较小 ConfigVersion，而是把历史 Envelope 内容复制成新的 DRAFT、分配更大的 ConfigVersion，重新 Validate/审批/Activation，保证 SDK 的单调版本检查成立。

### Release

```text
GET    /releases
GET    /releases/{id}
GET    /active-releases?environment={env}&app={app}
GET    /release-activations/{id}
POST   /releases/validate
POST   /releases
POST   /releases/{id}/publish
POST   /releases/{id}/rollback
GET    /releases/{id}/diff?compareTo={releaseId}
```

所有写 API 要求 `Idempotency-Key` 或 `requestId`。

### Flow

```text
GET    /flow-instances
GET    /flow-instances/{flowKey}
GET    /flow-instances/{flowKey}/events
POST   /flow-instances/{flowKey}/transition
POST   /flow-instances/{flowKey}/reset
DELETE /flow-instances/{flowKey}
```

### Callback

```text
GET    /callback-tasks
GET    /callback-tasks/{taskId}
GET    /callback-tasks/{taskId}/attempts
POST   /callback-tasks/{taskId}/retry
POST   /callback-tasks/{taskId}/cancel
```

### Request / Audit / Dashboard

```text
GET /requests
GET /requests/{requestLogId}?createdDate={yyyy-MM-dd}
GET /requests?appCode={app}&mockRequestId={mockRequestId}
GET /audit-logs
GET /dashboard/summary
GET /dashboard/trend
GET /dashboard/exceptions
```

### SDK Internal

```text
POST /api/internal/v1/sdk-config-events
POST /api/internal/v1/runtime-activation-acks
POST /api/internal/v1/runtime-policy-acks
```

该接口只接受服务身份认证，不使用浏览器 SSO；请求必须带时间戳、Nonce 和签名，并按配置版本幂等。

### 统一响应

```json
{
  "code": "0",
  "message": "success",
  "data": {},
  "requestId": "req-xxx"
}
```

分页：

```json
{
  "records": [],
  "total": 0,
  "page": 1,
  "size": 20
}
```

---

## 13. Web Console

### 13.1 技术栈

```text
Vue 3
TypeScript
Vite
Element Plus
Vue Router
Pinia
Axios
```

不使用完整若依，不建设独立 RBAC、动态菜单平台、低代码引擎和通用 CRUD 框架。

### 13.2 页面

```text
/mock/dashboard
/mock/providers
/mock/apis
/mock/contracts
/mock/flow-definitions
/mock/scenarios
/mock/approvals
/mock/security-policies
/mock/sdk-configs
/mock/releases
/mock/flows
/mock/callbacks
/mock/requests
/mock/audit
```

### 13.3 场景编辑器

使用 760～860px Drawer，分区：

```text
基础信息
Scope
Match Rules
Response
Flow
Callback
Validate Result
```

不提供代码编辑器。JSON Schema、Example 和 Body Template 使用普通文本区域加格式化、校验和只读预览。

### 13.4 权限码

```text
mock:view
mock:provider:view / edit
mock:api:view / edit
mock:app-acl:view
mock:security-policy:view / edit / publish
mock:sdk-config:view / edit / publish / override
mock:contract:view / edit / publish
mock:flow-definition:view / edit
mock:scenario:view / edit / disable
mock:approval:view / approve
mock:release:view / publish / rollback
mock:flow:view / transition / reset / delete
mock:callback:view / retry / cancel
mock:request:view
mock:audit:view
```

前端权限只控制展示，后端每个 API 必须重新鉴权。

### 13.5 状态管理

Pinia 仅保存：

```text
当前用户
权限列表
当前 Environment
Runtime 健康状态
侧边栏状态
```

分页、筛选、Drawer 和表单保留在页面组件内。

### 13.6 前端目录

```text
mock-platform-web/
├─ src/
│  ├─ api/
│  ├─ components/
│  ├─ layout/
│  ├─ router/
│  ├─ stores/
│  ├─ styles/
│  ├─ types/
│  ├─ utils/
│  └─ views/mock/
│     ├─ dashboard/
│     ├─ providers/
│     ├─ contracts/
│     ├─ flow-definitions/
│     ├─ scenarios/
│     ├─ approvals/
│     ├─ security-policies/
│     ├─ sdk-configs/
│     ├─ releases/
│     ├─ flows/
│     ├─ callbacks/
│     ├─ requests/
│     └─ audit/
└─ package.json
```

### 13.7 页面布局

```text
Sidebar：200～220px
Header：56～60px
页面 Padding：20～24px
内容 width:100%; max-width:none
Table 100% 宽
筛选栏最多两行
```

### 13.8 前端验证

```bash
npm ci
npm run type-check
npm run lint
npm run build
```

---

## 14. 安全设计

### 14.1 环境保护

以下三层均禁止生产 Mock：

1. SDK：生产 Profile 不接受 MOCK/CANARY 配置。
2. Runtime：认证主体映射为 PROD 时拒绝请求。
3. 网络：生产业务网段不能访问 Mock Runtime。

不能依赖单个 `X-Mock-Environment` Header。

### 14.2 管理面

- 复用巡天 SSO/RBAC。
- Provider/API/Contract/Scenario/Approval/Release/Flow/Callback 写操作全部审计。
- 发布、回滚、Flow 人工推进、Callback Retry 必须二次确认。
- App ACL、Provider Environment、SDK Header Filter、Callback Allowlist、Callback Signature 和 FALLBACK_REAL 必须“新建不可变 Version -> Validate -> checksum 审批 -> Publish”，后端拒绝绕过 Approval 直接修改活动 Binding。
- Config Publisher 使用服务身份写 Apollo/Nacos；人工账号只能发布已审批的策略版本，不能直接写 SDK 高风险配置值。SDK/Runtime 必须验签，未知 Key、Scope 不符、过期或 checksum 不符一律拒绝应用。
- 审计 Before/After 数据先脱敏再落库。

### 14.3 模板安全

- 不加载 JavaScript、Groovy、SpEL、Velocity。
- 不反射访问 Java 对象。
- 不允许文件、网络或环境变量读取。
- Compile 阶段建立 Token 白名单。
- Runtime 只消费 Control 签名并校验 checksum 的 Snapshot。

### 14.4 数据脱敏

脱敏规则来源：

```text
平台默认敏感字段字典
已发布的 SDK_HEADER_FILTER / Provider 敏感字段策略
Contract 字段标记
公司统一脱敏组件
```

默认敏感字段包括：

```text
authorization
cookie
token
secret
password
mobile
idCard
bankCard
signature
```

请求和响应先脱敏后进入日志队列/数据库，禁止“先落原文再查询时脱敏”。

### 14.5 数据保留

建议默认：

```text
Request Log：7 天
Request Execution：24 小时；仍被故障重试窗口引用时顺延到窗口结束
Flow Instance：场景 TTL，最长 7 天
Callback Task / Payload / Attempt：30 天
Audit Log：180 天或公司审计要求
Release / Scenario Version：长期保留
```

具体时间由数据合规确认。

---

## 15. 可观测性

### 15.1 Runtime 指标

```text
mock_runtime_requests_total{app,provider,api,result}
mock_runtime_duration_ms{app,provider,api}
mock_runtime_no_match_total{app,provider,api}
mock_runtime_scenario_conflict_total{app,provider,api}
mock_runtime_flow_transition_total{provider,api,from,to}
mock_runtime_flow_conflict_total{provider,api}
mock_runtime_active_release{environment,app,release}
mock_runtime_snapshot_load_failure_total{environment,app}
mock_runtime_admission_stale_total{environment,app}
mock_runtime_rate_limited_total{app,provider,api}
```

ReleaseId 不适合作为长期高基数指标标签时，改为日志字段和 Dashboard 当前值。

### 15.2 日志

每次请求至少关联：

```text
TraceId
MockRequestId
App
Environment
Provider
API
ReleaseId
ScenarioId
FlowKey
Result
Duration
```

日志正文不包含未脱敏 Body。

### 15.3 告警

```text
Runtime 5xx / internal error rate
NO_MATCH 突增
P95 超标
Active Release 加载失败
Callback 失败率和积压
Flow Worker 延迟
Request Log 写入失败
Redis / MySQL 不可用
```

---

## 16. 性能与可用性

### 16.1 P95 口径

正式验收使用固定脚本 `perf/runtime-mvp`，版本、配置和原始结果随验收报告保存。主压测口径：

```text
同机房调用
HTTP/1.1 Keep-Alive
100 个并发连接
预热 5 分钟
稳态 200 RPS 持续 10 分钟
随后 500 RPS 峰值持续 2 分钟
请求和响应：70% 为 1KB，30% 为 20KB
无故意 Delay/Timeout/Reset
从 SDK Feign Client/RestTemplate 执行入口到完整读取响应最后一个字节
```

场景流量比例固定为：

```text
50% 固定响应、无 Flow
20% 模板响应、无 Flow
15% QUERY_COUNT Flow
5% Flow Transition + Callback Task 创建
10% MOCK_NO_MATCH
```

MVP Exit 目标：

```text
稳态 200 RPS：整体 P95 < 50ms，P99 < 100ms
峰值 500 RPS：整体 P95 < 100ms，错误率 < 0.1%
平台非预期错误率：< 0.1%
```

错误率分母为压测发出的全部请求；`MOCK_NO_MATCH` 在脚本预期时视为正确业务结果。故意 HTTP Error/Timeout/Reset 在单独故障脚本中验证，不混入性能错误率。连接失败、`MOCK_INTERNAL_ERROR`、非预期超时和响应内容不符均计为平台错误。

1MB 报文不属于 `<50ms` 承诺，单独以 20 RPS、20 并发连接持续 5 分钟测试，目标 P95 `<200ms`、无内存溢出和 Event Loop 阻塞。实际业务量只能在 M0 容量评审后上调，不能在验收时为了通过而下调。

### 16.2 可用性边界

Runtime Availability SLI 定义为：

```text
成功执行的合格 Mock 请求数 / 合格 Mock 请求总数
```

合格请求是认证、Contract 和请求大小均合法的请求。按场景正确返回 HTTP Error、Delay、Timeout、Reset 计为成功；`MOCK_INTERNAL_ERROR`、`MOCK_RELEASE_UNAVAILABLE`、`MOCK_ADMISSION_POLICY_STALE`、非预期连接失败或超出场景约定时间计为不可用。NO_MATCH 属于配置质量指标，不计入 Availability，但单独告警。安全准入陈旧时优先 fail-closed，即使因此消耗 Availability Budget 也不延长 60 秒窗口。

目标为滚动 30 天 `>=99.9%`，对应不可用预算不超过 43.2 分钟。计划维护也计入预算；MySQL、Redis、网络和容器平台造成的 Runtime 失败均计入平台 SLI。

实现条件：

- Runtime 至少 2 实例，跨故障域部署；
- Control 管理面单独统计可用性，不混入 Runtime SLI；
- MySQL、Redis 使用公司高可用实例；
- Runtime 使用 Last Known Good Release；
- 发布失败不影响现有 Active Release；
- Callback Worker 使用数据库租约和 fencing token，可在节点故障后恢复；
- 部署支持滚动更新和连接优雅退出。

Runtime 冷启动且 Redis/MySQL 都不可用时不承诺继续提供 Mock；应快速失败并告警。

验收至少包含：单 Runtime 节点退出、Redis 主从切换、MySQL 短暂故障、发布期间节点加载失败、Callback Worker 崩溃恢复。每个演练记录用户可见错误数、恢复时间和是否消耗 Availability Budget。

### 16.3 容量模型与存储门槛

M0 在真实流量未知前使用以下规划假设，不把 200 RPS 峰值误当成持续平均值：

```text
平均请求率：<= 10 RPS
稳态压测：200 RPS
短时峰值：500 RPS
Request Log：约 86.4 万行/天，7 天约 605 万行
Request/Response Summary：各最大 4KB，规划平均总计 2KB/行
```

按 605 万行、平均 2KB 加索引和空间放大估算，Request Log 需预留约 20～30GB。MySQL 采用按日分区，在线保留 7 天，通过 Drop Partition 清理。

容量门槛：

- 若 M0 实测平均请求率超过 10 RPS、日增超过 100 万行，或平均单行超过 2KB，M1 前必须评审是否改用公司日志检索/ClickHouse；
- `mock_request_execution` 仅对 Flow/Callback/副作用故障的 SDK 请求强制持久化，默认保留 24 小时，MVP 非分区并按 `expire_at` 小批量删除；Timer/Manual 只写 Flow Event；
- Request Log 日分区不设置跨分区业务唯一键；M0 必须在目标 MySQL 版本实际执行建表和 Drop Partition 脚本；
- Callback Payload 最大 1MB 只是协议上限，容量规划必须使用实际样本的 P50/P95；
- Dashboard 聚合不得扫描 Request Log 明细，使用预聚合指标；
- 所有容量结论记录平均/峰值、行大小、索引大小、日增量和清理耗时。

---

## 17. 测试策略

### 17.1 单元测试

必须覆盖：

- 模式优先级和 CANARY 判断；
- URL 改写和敏感 Header 删除；
- 平台默认 denylist 与签名 SDK_HEADER_FILTER 的合并、路由/过滤策略原子切换；
- SDK_CONFIG_ENVELOPE checksum、完整引用校验和 configVersion 并发保护；
- Config Activation Wrapper checksum/签名、ActivationId 与 Envelope 引用一致性；
- Match Rule 每种操作符；
- 场景优先级和冲突；
- Template Token 解析、缺失变量、字符串/类型化 JSON 插入；
- Flow Participant、变量和四类 Transition；
- QueryCount 边界；
- Callback Retry 判定和重复任务生成；
- Release checksum 和不可变性；
- Contract 编译物完整性和 Runtime 禁止读取可变管理表；
- Snapshot signature、key rotation 和篡改阻断；
- Flow BusinessNo HMAC 当前/保留 Key 查询和两阶段轮换；
- Transition Priority 冲突检测和确定性选择；
- SDK 请求指纹的 Tenant/TestAccount/Content-Type/显式 Scenario/业务 Header 差异；
- Timer/Manual `internalExecutionId` 防重；
- EXPIRED/DELETED Flow 原子再激活与并发 CREATE；
- Callback 固定签名/Allowlist Version、准备失败与发送预算分离；
- Callback 预算耗尽停止领取、人工单次 Grant、创建时最终渲染与重试内容稳定；
- Callback Finalize 的 CANCELLED/PREPARATION/CONFIRMED/UNKNOWN 转换和 affectedRows fencing；
- retryIntervalsMs 长度/非负/累计时长与人工 delay 限制；
- OpenAPI/JSON/YAML Alias、深度、节点、引用和超时限制；
- 权限和审批状态机；
- 高风险安全策略 Version、checksum 审批、签名发布和绕过阻断。

### 17.2 集成测试

```text
JDK8 Feign -> Runtime
JDK8 RestTemplate -> Runtime
JDK17 Feign -> Runtime
JDK17 RestTemplate -> Runtime
自定义 Provider 敏感 Header 在 JDK8/JDK17 SDK 均不会进入 Runtime
SDK 从签名 Config Activation Wrapper 取得 ActivationId，APPLIED/REJECTED 事件按 activationId+sdkInstanceId 幂等且无法串到其他 Envelope
Runtime -> MySQL/Redis
Control Publish -> Runtime Active Release
MySQL Active Release -> Outbox -> Redis Projection -> Runtime Ack
Redis 丢失后从 MySQL 唯一恢复 Active Release
Redis 丢失后从 MySQL Policy Version/Binding 重建签名 Active Admission ACL
同一 Runtime 对两个 App 的 bindingVersion=1 分别 ACK，不发生唯一键冲突或串 Binding
发布期间请求固定 Release、健康节点 5 秒内收敛
Flow 并发请求
CREATE/QUERY/COMMAND 跨 API 共用 Flow
Delete/TTL 后同一 BusinessNo CREATE 原子再激活，旧 Generation 审计仍可查
旧 Flow 在新 Release 删除旧 Scenario 后继续使用固定版本
回滚目标缺少未过期 Flow Locator/Extractor 时被阻断，补兼容 Release 后成功
相同 MockRequestId 在提交后断链重试，不重复推进
相同 MockRequestId 但 Tenant、业务 Header 或显式 Scenario 不同时报指纹冲突
Request Execution 过期但 Cleanup 尚未删除时，同一 ID 在行锁内原子开启新 generation
相同 MockRequestId 与相同 Flow 的并发请求统一按 RequestExecution->Flow->Callback Task 锁序执行，无反向死锁
模板运行错误使 Flow/Event/Callback/Execution 全部回滚
Runtime 惰性 TIME 与 Timer Worker 并发只产生一个 Event
Timer/Manual 重试复用 internalExecutionId 且不写 SDK Request Execution
Callback Worker 多实例领取
Reset/Delete 与 Worker Claim 在目标 MySQL 隔离级别下按 Flow->Task/Task-only 锁序无反向死锁
Worker 崩溃后租约恢复
RUNNING 租约过期被接管，旧 Attempt 标记 ABANDONED，预算耗尽转 FAILED_UNCONFIRMED
KMS/DNS 等 PREPARATION_FAILED 不消耗发送预算且准备重试有上限
发送预算耗尽的过期 STARTED 只转 FAILED_UNCONFIRMED，不创建 PREPARING Attempt；人工 Retry 只增加一次 Grant
Callback Task 重试始终使用创建时固定的 Snapshot/签名/Allowlist Version
Flow 后续迁移或 Request Execution 清理后，Callback 重试仍使用创建时已加密的相同 URL/Header/Payload
Preparation 各失败分支清 lease 并进入 RETRYING/FAILED_PREPARATION；UNKNOWN 最终失败进入 FAILED_UNCONFIRMED
Flow 无效的已领取 Task fenced CANCELLED 后不再扫描
Callback 创建事务不执行 DNS；Worker 每个 Attempt 重新解析并 Pin IP
Callback 发送成功但确认前崩溃，重复使用相同 DeliveryId
Reset/Delete 与 NEW/RETRYING/RUNNING Callback 竞争
Connection Reset 客户端观测
SSRF 白名单、重定向、DNS Rebinding 和 IP Pinning
Callback 密文存储、解密权限和到期清理
OpenAPI/YAML Alias Bomb、深层嵌套、超节点/$ref/超时样本均被受限线程池拒绝
```

### 17.3 端到端样板

每类第三方至少包含：

```text
成功
失败或驳回
处理中到成功
超时
回调成功
回调失败后重试
```

同一业务键执行两次完整测试，结果应可重复；清理后能够从初始状态重新执行。

样板至少包含一个跨接口链路：CREATE 创建 Flow、QUERY 推进状态、COMMAND 改变状态、Callback 返回结果。

### 17.4 发布测试

```text
Draft 修改不影响运行
并发发布只有一个 Activation Version 成为权威
Outbox 投影失败时 MySQL 权威状态不丢失并可恢复
SDK Wrapper 在事务提交前失败时不外发；提交后任意重试均发布相同 canonical bytes/checksum/signature
Apollo/Nacos 配置 Key 被删除后通过 Active Config activationId 定位 Outbox 并重放完全相同的 Wrapper 字节
回滚恢复旧响应
Runtime 节点逐个刷新，每个请求无混版且 5 秒内收敛
超过收敛窗口的节点被摘除，Activation 标记 PARTIAL
Redis 清空后可恢复 Active Release
Snapshot 被篡改或使用未知 Key 时 Runtime 拒绝加载
Activation Target Node 集合在 Control 重启后仍保持原完成条件
Release PARTIAL 只有在失败节点恢复 READY、已摘流后同事务 WAIVE，或回滚时才能解除；WAIVE 后完成聚合可收敛
Snapshot 缺少 Contract 编译物或安全策略时 Runtime 整体拒绝加载
未审批或签名无效的高风险策略无法发布到 Runtime/SDK
App ACL 撤销在 60 秒内生效，Admission Snapshot 超过 60 秒无法刷新时 fail-closed
Admission Projector 崩溃、旧 Redis 信封重复读取、Binding 回滚场景均受 bindingVersion/issuedAt/notAfter 约束
Runtime Policy Ack 缺失节点在 60 秒后被摘除，不能被忽略后宣称 EFFECTIVE
Security Policy 的 PUBLISHED/BOUND/EFFECTIVE 状态不混淆
Release 只接受 PUBLISHED+BOUND 安全策略，Activation 不再次修改其 PUBLISHED 状态
SDK 路由与 Header/Fallback Policy 通过同一 Config Envelope 发布，不存在分键中间态
SDK Config Activation/Target 与 Outbox 同事务，Control 重启后 required 集合不丢失
SDK Target LEFT/WAIVED/REJECTED/超时摘流和新实例 readiness 规则可执行
目标 MySQL 版本实际建表成功：Execution 全局唯一不分区、Request Log 日分区无业务唯一键
```

---

## 18. 部署

```text
mock-platform-web
  -> 公司静态资源平台 / Nginx

mock-platform-control
  -> JDK17，2 实例

mock-platform-runtime
  -> JDK17，2 实例，独立域名和网络策略

MySQL HA
Redis HA
```

网络：

```text
Browser -> Control Admin API
Business SDK -> Runtime
Control -> Redis / MySQL / 密钥服务
Control ConfigPublisherAdapter -> Apollo / Nacos
Runtime -> Redis / MySQL
Callback Worker -> Callback Allowlist Target
```

Runtime 无真实第三方出站权限。Callback Worker 使用单独出口策略，不能复用 Runtime 的任意网络能力。

---

## 19. 工程结构

```text
third-party-mock-platform/
├─ pom.xml
├─ mock-client-annotation
├─ mock-client-core
├─ mock-client-config-spi
├─ mock-client-apollo-adapter
├─ mock-client-nacos-adapter
├─ mock-client-boot2-starter
├─ mock-client-boot3-starter
├─ mock-platform-common
├─ mock-platform-control
├─ mock-platform-runtime
├─ mock-platform-web
├─ deploy
└─ docs
```

不要提前拆分独立 Callback Service、Release Service 或 Flow Service。Control 内按模块组织；只有出现独立伸缩或故障隔离需求时再拆服务。

---

## 20. 里程碑与工作量

### M0：技术决策和 PoC（4～6 人周）

```text
锁定 JDK8/JDK17 客户端版本矩阵
实现最小 SDK URL 改写
验证 Apollo/Nacos 动态切换
Runtime 固定响应
验证 Token/Cookie/Signature 剥离
验证 Connection Reset
验证 Feign Client/RestTemplate Decorator 的 FAST_FAIL/FALLBACK_REAL/FALLBACK_RESPONSE
验证 Runtime 已接收后断链绝不回真实，Feign 重试保持同一 MockRequestId
确认 Tenant/TestAccount 可信来源和 App ACL
完成 200 RPS 基线压测和容量测算
```

退出条件：SDK 路由方式和 Runtime 网络模型验证通过。

### M1：最短运行链路（4～6 人周）

```text
Provider / API / Contract 最小管理
固定/模板响应
Scenario Match
Request Log
REAL/MOCK/CANARY
一个老巡天和一个新巡天接口
```

### M2：发布闭环（6～9 人周）

```text
Scenario Version
Validate
Approval
Release Snapshot
Publish / Rollback
Audit
Runtime Cache Refresh
MySQL Active Release / Outbox / Runtime Ack
Snapshot Signature / Runtime Verify
Contract 编译物完整快照
安全策略 Version / Approval / Binding
ConfigPublisherAdapter / SDK 策略验签
SDK Config Envelope / ConfigVersion / Activation Target / 原子发布
```

### M3：Flow + Callback（7～10 人周）

```text
Flow Instance / Event
Flow Definition / Participant API / Variables / Generation
Request Execution 幂等账本
QUERY_COUNT / TIME / REQUEST_FIELD / MANUAL
TTL / Reset / Cleanup
Callback Task / Attempt
Retry / Duplicate / Simple Out-of-order / Signature
```

### M4：三类第三方样板（4～6 人周）

```text
OA 1 个接口
e签宝 1 个接口
共享平台 1 个接口
沉淀 OA 通过/驳回、e签宝成功/失败、共享平台处理中/成功的场景种子模板
真实业务联调和修正
```

### M5：MVP Exit（5～7 人周）

```text
扩展到每类 3 个核心接口
安全检查
性能压测
故障演练
操作文档
验收 Demo
```

角色工作量粗估：

| 角色 | 人周 | 主要工作 |
|---|---:|---|
| 后端 A | 9～12 | JDK8/JDK17 SDK、Runtime、故障行为、性能 |
| 后端 B | 11～16 | Control、Release、安全策略、Flow、Callback、数据库 |
| 前端 | 4～6 | Console、权限、Scenario/Release/Flow 页面 |
| 测试/性能 | 4～6 | 兼容矩阵、端到端、并发、故障和压测 |
| 安全/运维/业务联调 | 2～4 | 身份、SSRF、网络、部署、9 个接口确认 |
| 合计 | 30～44 | 已包含约 20% 的集成与整改缓冲 |

关键路径为 `M0 -> M1 -> M2 -> M3 -> M4 -> M5`，前端页面可与 M1～M3 并行。具备 2 名后端、1 名前端、0.5～1 名测试以及安全/运维按需支持时，日历工期约 10～14 周；外部接口文档、账号、网络或统一认证等待超过 5 个工作日会直接影响季度目标。

单人利用正常业务需求间隙无法承诺完整 MVP。若只有 1 名兼职开发，应把季度目标改为 Pilot Exit，并后置双人审批、OpenAPI 导入、TIME Worker、主动乱序和 9 接口扩展。

---

## 21. MVP 验收标准

### 21.1 SDK

1. 老巡天 JDK8 至少一个真实服务接入。
2. 新巡天 JDK17 至少一个真实服务接入。
3. 业务代码不存在 `if mock` 分支。
4. REAL/MOCK/CANARY 通过不可变 SDK Config Envelope 动态切换无需重启；路由和 Header/Fallback Policy 整包原子应用，Activation/Target 状态可恢复，单实例 ACK 不会冒充全量 EFFECTIVE。
5. Mock 请求中不存在真实 Token、Cookie、AppSecret、Signature；Provider 自定义敏感 Header 通过签名 SDK_HEADER_FILTER 与路由原子生效后同样不会进入 Runtime。
6. FAST_FAIL、受限 FALLBACK_REAL、FALLBACK_RESPONSE 行为与配置一致。
7. PROD 环境无法开启 MOCK/CANARY。

### 21.2 Scenario 与 Release

8. 固定响应和受限模板响应可用。
9. Header/Query/JSONPath/Regex/BusinessNo 匹配可用。
10. App/Tenant/TestAccount/有效期隔离无串场景；独立 Active Admission ACL 撤销在 60 秒内生效，策略陈旧超过 60 秒时 Runtime fail-closed；ACK 以 BindingId 隔离，多 App 同版本号不串状态。
11. 同请求多场景匹配结果确定且可解释。
12. Draft 修改不影响运行流量；Scenario Version 的 DRAFT/VALIDATED/PENDING_APPROVAL/APPROVED/PUBLISHED/DISABLED 状态可持久化且写 API 强制执行。
13. Release 不可变，MySQL Active Release 可在 Redis 丢失后唯一重建。
14. 发布并发保护、持久化 Target Node、Outbox 投影、Runtime Ack 和回滚按 activationVersion 工作；目标 Release 缺少未过期 Flow 所需 Locator/Extractor 时回滚被阻断。
15. 每个请求固定一个 Release；健康 Runtime 集群在 5 秒内收敛，超时节点被摘除。
16. Release Snapshot 包含固定 Contract 编译物和 Release 类安全策略版本，准入使用独立签名 Admission Snapshot；checksum、签名和密钥轮换验证通过，篡改或缺少必需内容时无法加载，Runtime 请求路径不读取可变管理表。
17. Runtime 加载失败继续使用 Last Known Good，不加载部分 Snapshot。
18. MOCK_NO_MATCH 不访问真实系统。

### 21.3 Flow 与 Callback

19. Flow 按 Environment/App/Provider/FlowCode/Tenant/TestAccount/BusinessNo 唯一，可跨 CREATE/QUERY/COMMAND API 使用。
20. QUERY_COUNT、TIME、REQUEST_FIELD、MANUAL 推进可用。
21. 并发查询、Runtime 惰性推进和 Timer Worker 不会重复迁移或丢失状态；Timer/Manual 使用确定性 internalExecutionId 且不写 SDK Request Execution。
22. 已有 Flow 固定旧 Release 和 Flow Definition Version，新发布移除或修改 Scenario 后仍能继续。
23. 相同 MockRequestId 在 24 小时内重试复用已持久化结果或故障，不重复推进 Flow/创建 Callback；Environment/App/Tenant/TestAccount、Content-Type、显式 Scenario 或业务 Header 不同均形成不同指纹并被拒绝复用；过期但尚未清理的 Execution 可在行锁内原子重置为新 generation。
24. Flow 可查询、推进、重置、软删除、清理和 TTL 过期；Delete/过期后同一 BusinessNo 可原子再激活为新 Generation，不受历史行唯一键阻塞。
25. Reset/Delete 成功后旧 Generation 的未发送 Callback 全部取消；存在 RUNNING Task 时操作明确失败为 BUSY。
26. Callback 支持延迟、重试、主动重复、简单乱序和签名。
27. 无故障时不产生配置外重复；故障时满足至少一次，额外重投使用相同 DeliveryId 且 Attempt 可识别、可审计；Claim 不与 Flow 形成反向锁序，所有 Finalize 受 fencing 保护并区分 CANCELLED/PREPARATION/CONFIRMED/UNKNOWN；预算耗尽不再创建 Attempt，人工 Retry 只授权一次；历史任务始终使用创建时固定的 Snapshot/策略 Version 和最终渲染内容，DNS 只在 Worker PREPARING 执行。
28. SDK 请求的 Flow 状态迁移、Variable、Event、Callback Task 和 Request Execution 在同一事务中提交或回滚；Timer/Manual 的 Flow、Event、Callback Task（及 Manual Audit）在不创建 Request Execution 的事务中提交或回滚。

### 21.4 管理、安全与观测

29. SSO/RBAC 后端鉴权有效，App ACL 和 Tenant/TestAccount 信任模型通过安全验证；App ACL、Provider Environment、SDK Header Filter、Callback Allowlist/Signature 和 FALLBACK_REAL 只能通过不可变版本、checksum 审批及签名发布生效。
30. 单人/双人审批人数、禁止自审和审批 checksum 规则可执行、可审计。
31. Contract/Scenario/Security Policy/Approval/Publish/Rollback/Flow/Callback 写操作可审计。
32. Apollo/Nacos 变更记录与 SDK 实际生效事件可关联查询；安全策略的 PUBLISHED、BOUND、EFFECTIVE 可区分并关联 ConfigVersion 或 Release Activation。
33. 敏感字段在落库前完成脱敏；Callback URL/Header/Payload 和幂等响应加密存储并按期清理。
34. Callback DNS 重绑定、重定向和未授权地址绕过测试全部失败。
35. Request Log 可按 TraceId、BusinessNo、Provider/API、Scenario 查询；BusinessNo/FlowKey 使用带版本 HMAC，当前及保留 Key 轮换期间均可定位旧数据且不产生重复 Flow。
36. Dashboard 可看到请求量、命中率、NO_MATCH、P95、Callback 成功率和重试数。

### 21.5 性能与工程质量

37. 固定压测脚本和流量模型下满足稳态/峰值/1MB 三档指标。
38. Runtime 30 天 Availability SLI 定义可采集，故障演练结果符合 99.9% 预算。
39. Runtime 2 实例滚动发布不中断已有流量。
40. Web `type-check / lint / build` 通过。
41. 后端目标模块测试、构建和安全扫描通过；恶意 OpenAPI/JSON/YAML 限制样本通过；目标 MySQL 版本的 Execution 非分区唯一键、Request Log 日分区 DDL 和清理脚本实测通过。
42. OA、e签宝、共享平台各至少 3 个核心接口完成验收。

---

## 22. 开工前必须确认的外部依赖

以下信息不能靠技术方案假设，M0 开始前由项目负责人确认：

| 项目 | 必须确认内容 |
|---|---|
| 老巡天版本 | JDK、Spring、Boot、Feign、RestTemplate、Apollo 精确版本 |
| 新巡天版本 | JDK、Boot、Spring Cloud、Feign、Nacos 精确版本 |
| 统一认证 | 管理面 SSO 接口、运行面服务身份方案 |
| RBAC | 权限申请和后端鉴权接入方式 |
| 数据库 | MySQL 版本、HA、容量和 JSON 字段规范 |
| Redis | 版本、HA、Lua/ACL/PubSub 可用性 |
| 密钥 | Release/安全策略签名 Key、Flow/日志 HMAC Key、Callback Secret 的托管、轮换和读取方式 |
| 配置中心写入 | ConfigPublisherAdapter 的 Apollo/Nacos 服务账号、审批链路、命名空间和审计引用方式 |
| 实例治理 | SDK 所在 App 的服务发现、实例 ID、readiness 和超时摘流接口；缺失时不能做安全敏感 Config Activation |
| 网络 | Runtime 入站、Callback 出站、生产隔离策略 |
| 监控 | 指标、日志、Trace 接入规范 |
| 时间同步 | Control/Runtime/SDK 节点的公司 NTP/时钟偏差告警能力（Admission 租约最大允许 5 秒偏差） |
| 数据合规 | Body、业务单号、账号的脱敏和保留周期 |
| 首批接口 | 9 个接口清单、负责人、样例报文、回调规则 |

任一关键依赖未确认，不阻塞前端骨架，但不得据此宣称对应后端能力已完成。

---

## 23. 实施原则

1. 先 M0 PoC，再批量开发页面和接口。
2. Runtime 只有一个场景决策点。
3. MySQL 保存需要恢复的业务事实，Redis 不承载唯一事实。
4. 发布物不可变，运行时不读 Draft。
5. 模板严格白名单，不开放任意代码。
6. 默认不回退真实，生产三层禁止 Mock。
7. 先满足 3 个样板接口，再扩展到 9 个接口。
8. 每个里程碑必须有可运行 Demo、自动化验证和实际 Diff Review。
9. 不在 MVP 建设通用平台能力。
10. 未经产品确认，不把后续版本能力偷偷加入 MVP。

---

## 24. 后续版本

### MVP+

```text
Forest
WebClient
Request Replay
测试报告导出
OpenAPI 导入增强
更多故障类型
```

### V1.2

```text
AI Contract Draft
AI Scenario Draft
AI Diagnose
自然语言测试流程草稿
```

AI 只生成 Draft 或建议，发布必须人工审批；Agent 只允许调用白名单工具，不访问任意网络、不执行任意代码。

### V2

```text
MQ / 文件协议
流量录制
覆盖率分析
Contract Drift
复杂回放
独立 Callback Worker（确有伸缩需要时）
外部 Mock Engine Adapter
```

---

## 25. 最终判断

按本修订版实施，项目属于“范围可控、技术可行、需要真实 PoC 降低兼容风险”的 MVP。

不建议直接从 Web 页面开始堆功能。正确顺序是：

```text
SDK + Runtime 技术验证
  -> 单接口固定响应
  -> Scenario/Release 闭环
  -> Flow/Callback 事务闭环
  -> 三类第三方样板
  -> 扩展和验收
```

只有 M0 的 SDK 拦截、动态切换、凭证剥离、连接故障和性能基线得到实际证据后，季度交付承诺才成立。
