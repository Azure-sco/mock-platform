# Mock Platform 当前实现能力与实现说明

状态日期：2026-08-31  
适用版本：当前工作区 M0～M5 实现

## 1. 结论

当前代码已经从 M0 技术 PoC 扩展为具备管理面、发布闭环、数据面规则决策、Flow、Callback 和 Web Console 的 MVP 实现。M1～M4 的核心代码与自动化验证已落地；M5 的九接口目录、控制台、性能脚本和操作文档已落地，但正式性能结果、30 天可用性证据、双实例滚动发布、安全扫描和公司基础设施适配仍需在目标环境完成，因此不能把当前工作区表述为“已通过全部生产 MVP Exit”。

| 阶段 | 当前状态 | 已有证据 |
|---|---|---|
| M1 最短运行链路 | 完成 | Provider/API/Contract、Scenario、模板、Request Log、REAL/MOCK/CANARY 与双 JDK 测试 |
| M2 发布闭环 | 完成 | 审批、不可变 Release、Outbox、Redis 投影、Runtime Ack/恢复、签名校验、Security Policy、SDK Config 测试 |
| M3 Flow + Callback | 完成核心实现 | Flow/Execution/Callback 数据模型、事务、锁、租约、fencing、恢复和并发测试 |
| M4 三类样板 | 完成自动化目录与流程验证 | OA/e签宝/共享平台各 3 个接口，OA 跨 API Flow、共享平台 TIME Flow |
| M5 MVP Exit | 部分完成 | Console、Dashboard、审计、固定压测脚本和文档完成；目标环境门禁尚未全部执行 |

## 2. 模块能力

### 2.1 SDK Routing

- `@ThirdPartyMock` 在 Boot 2/JDK 8 和 Boot 3/JDK 17 两条技术线上创建调用上下文。
- `RouteResolver` 基于不可变 `RoutingSnapshot` 执行 REAL、MOCK、CANARY；Provider+API 配置优先于 Provider 和默认配置。
- Mock 请求复制 Path、Query、Body，只允许白名单业务 Header；Token、Cookie、AppSecret、Signature 等真实凭证不会进入 Runtime。
- `MOCK_NO_MATCH`、Read Timeout、Connection Reset 等不确定副作用故障不会静默访问真实系统。
- SDK Config 使用签名 Wrapper/Envelope、checksum、scope、版本和安全策略引用进行整包校验，通过 CAS 原子替换；目标实例 ACK 可追踪。
- Apollo/Nacos 模块实现发布 Adapter 和 ACK 边界，真实公司客户端仍属于外部适配。

### 2.2 Control 管理面

Control 提供以下持久化管理能力和管理 API：

- Provider、API、Contract Version；Contract 校验、发布和 Diff。
- OpenAPI 3.0 JSON/YAML、JSON Schema 本地文件导入 Contract Draft；解析在 2 线程/20 队列的受限执行器中进行，并限制 5 MB 文件、UTF-8、64 层深度、10 万节点、1000 个本地引用和 3 秒超时。外部/递归 `$ref`、YAML Anchor/Alias、压缩文件和超限样本 fail-closed，失败不落草稿。
- Scenario、Scenario Version、复制、校验、送审、审批、禁用。
- Release 校验、创建、发布、回滚、Activation/Target 查询与 Waive。
- Security Policy Version、审批、发布和 Binding。
- SDK Config Envelope、审批、发布、回滚、Target/ACK 和 Diff。
- Flow Definition/Version、校验和送审。
- Flow Instance 查询、事件、人工迁移、Reset 和软删除。
- Callback Task/Attempt 查询、人工重试和取消。
- Request Log、Audit Log 查询。
- Dashboard 汇总最近 24 小时请求量、命中率、NO_MATCH、P95、Callback 成功率和重试数。

所有管理写操作经服务端角色检查、Request ID 和审计边界执行。`local/test` Profile 使用可测试身份；其他 Profile 缺少公司 SSO/RBAC 时 fail-closed。

### 2.3 Scenario Runtime

Runtime 是唯一数据面决策点，Control 不参与单请求匹配。请求处理顺序为：

```text
MockApp 身份 -> Admission -> 固定 Release -> Contract -> Scenario Match
-> Flow/RequestExecution（如有）-> 响应或故障 -> Request Log
```

已实现：

- Header、Query、JSONPath、Regex、BusinessNo 条件；优先级和稳定 ID 保证确定选择。
- Environment/App/Tenant/TestAccount/有效期 Scope。
- 固定 JSON 与受限变量模板；没有脚本、SpEL、Groovy、Velocity。
- Draft 永不进入 Runtime；Runtime 只读取编译后的不可变 Snapshot。
- 普通 Delay 使用 Reactor 非阻塞计时器。
- `HTTP_ERROR`、`READ_TIMEOUT`、`CONNECTION_RESET` 与 `NO_APPLY`/`APPLY_BEFORE_FAULT`。
- Request Log 异步落 MySQL；明文业务号不落库，使用 HMAC 版本和掩码字段。
- Request Log 和分钟级 Dashboard 聚合在同一数据库事务写入；聚合表保存请求量、命中/NO_MATCH 和固定延迟直方图，Dashboard 不扫描明细表。

### 2.4 Release

- MySQL 的 `mock_active_release` 是权威状态；Redis 只保存可恢复的 Active Pointer 和 Snapshot 投影。
- Release 创建时固定 canonical bytes、checksum、RSA signature、keyId 和编译产物，发布后不可原地修改。
- 发布事务创建 Activation、持久化目标节点、审计和 Outbox；Projector 幂等投影 Redis。
- Runtime 校验 Envelope canonical bytes、scope、checksum、RSA-2048 签名和编译版本，失败保持 Last Known Good。
- Runtime ACK 按 Environment/App/Node/activationVersion 幂等；PARTIAL/APPLIED 可区分。
- Rollback 创建新的 activationVersion，不修改历史 Release；仍被活跃 Flow 固定引用的 Locator/Extractor 会参与兼容检查。
- Redis 丢失时可从 MySQL 精确恢复当前或历史固定 Release，保留真实 activationVersion。

### 2.5 Security Policy 与 SDK Config

- APP ACL、SDK Header Filter、Callback Allowlist/Signature 等高风险配置使用不可变 Version、checksum、审批和发布。
- Config Publish Outbox 保存事务前生成的精确签名字节，重放不重新生成时间/checksum/signature。
- SDK Config Activation 固定目标实例；PENDING/PARTIAL/EFFECTIVE 分离，单实例 ACK 不会冒充全量生效。
- 敏感 Envelope、Callback URL/Header/Payload、幂等响应使用 `ProtectedPayloadCodec`/`RuntimeCryptography` 边界保护；生产 Profile 未配置 KMS 时 fail-closed。

### 2.6 Flow 与 RequestExecution

Flow 由 Environment/App/Provider/FlowCode/Tenant/TestAccount/BusinessNo HMAC 唯一定位，并固定：

```text
releaseId + activationVersion + flowDefinitionVersionId
+ flowDefinitionChecksum + generation
```

已实现：

- CREATE、QUERY、COMMAND Participant 和 Header/Query/JSON Body Business Key Extractor。
- QUERY_COUNT、TIME、REQUEST_FIELD、MANUAL Transition。
- 类型化 Variables、Event、TTL、Reset、Delete、Cleanup、过期后原子 Reactivate 和 generation 递增。
- 相同 Flow 的事务使用数据库行锁串行；不同 Flow 可并行。
- SDK 有副作用请求首先锁定/创建 `mock_request_execution`，再按 `Execution -> Flow -> Callback Task` 顺序处理。
- 相同 MockRequestId+指纹返回已持久化响应、Delay 和 Fault；指纹不同返回幂等冲突。
- Flow 状态、Variable、Event、Callback Task、Response/Fault Decision 和 COMPLETED Execution 同事务提交。
- Timer/Manual 使用确定性 internalExecutionId，不伪造 SDK RequestExecution。
- 过期 RequestExecution 使用主键小批量清理；每行删除前重新加锁并核对 generation/expireAt，避免与过期后重用同一 MockRequestId 竞争时误删新执行。

### 2.7 Callback

- Task 创建时固定 Release/Snapshot、最终 URL/Header/Payload、DeliveryId、签名和 Allowlist Policy Version。
- Worker Claim 使用 MySQL 8 `SKIP LOCKED`、Lease 和递增 fencing token。
- HTTP 发送在事务外；Finalize 仅在 fence 仍有效时生效。
- 状态区分 preparation failure、confirmed HTTP response 和 transport unknown；已开始但结果未知不会伪装成“从未发送”。
- 支持 Delay、配置化 Retry、相同 DeliveryId 的主动重复、简单乱序和 HMAC 签名。
- Reset/Delete 先锁 Flow，再处理未发送 Task；Worker 只锁 Task，不形成 `Task -> Flow` 反向锁。
- URL 只允许 HTTPS 或显式本地 loopback；准备阶段做 allowlist、DNS/IP 校验、固定 IP、禁止 Redirect 和响应超时。
- 过期终态 Task 由有界保留期 Worker 物理清理：事务内重新锁 Task 并核对状态/expireAt，先删除 Attempt 再删除 Task；RUNNING/NEW/RETRYING 或人工重试后状态变化的任务保留。

## 3. 数据库变更

Flyway 在预发布 MVP 阶段收敛为 3 个版本：

- V1：bootstrap、audit。
- V2：provider、api、contract_version、request_log 明细表。
- V3：scenario/version、approval/release/outbox、安全策略、SDK Config、Flow/Callback/Request Execution，以及 Request Log 日分区和分钟预聚合。

所有需要恢复的业务状态在 MySQL；Redis 数据可从 MySQL 重建。V1～V3 使用全新 MySQL 8.0.36 容器验证；合并前运行过 V4～V7 的本地开发库需要清库重建。Control 定时维护未来 31 天分区、默认 7 天在线保留，并同步删除过期分钟聚合桶。

## 4. Web Console

Vue 3 + TypeScript Console 已包含 14 个页面：Dashboard、Provider、API、Contract、Flow Definition、Scenario、Approval、Security Policy、SDK Config、Release、Flow、Callback、Request、Audit。写操作均携带操作人、角色和唯一 Request ID；页面不实现自研 RBAC 或通用 CRUD Framework。

## 5. 九接口验收目录

`mock-platform-runtime/src/main/resources/mvp-demo-fixture.json` 固定了以下目录并由编译测试校验：

| Provider | 三个核心接口 |
|---|---|
| OA | 创建审批、查询 OA 单号、审批/驳回命令 |
| CPS_EQB | 机构认证检查、创建并发起签署、查询合同文件 |
| SHARED_PLATFORM | 获取 Token、供应商映射查询、结算单推送 |

覆盖成功、业务失败/驳回、处理中、HTTP Error、Read Timeout、Connection Reset、Callback 和重试配置。OA 提供 `CREATE -> QUERY -> COMMAND -> CALLBACK` Flow；共享平台提供 `PROCESSING -> TIME -> SUCCESS -> CALLBACK` Flow。Runtime 中不存在 Provider 业务特判。

该 JSON 是编译目录和自动化样例，不绕过数据库外键。手工联调带 Flow 的场景时，必须通过 Control 创建并发布真实 Release/Flow/Scenario/Security Policy；不能仅切换 Fixture 文件就声称发布链路已验收。

## 6. 验证与剩余边界

已实现固定性能入口：`scripts/perf/runtime-mvp.ps1`。正式默认口径为 5 分钟预热、200 RPS 10 分钟、500 RPS 2 分钟、100 连接，并提供 1 MB 请求档。脚本在计时前准备真实 Flow，保存 JSON 原始摘要；`-Quick` 只用于检查脚本，不是 Exit 证据。

仍需外部环境完成：

- 正式性能三档结果与 Event Loop/内存监控证据；
- 两个 Runtime 实例滚动发布、Redis/MySQL 切换和节点退出演练；
- 30 天 Availability SLI；
- 公司 SSO/RBAC、Service Identity、KMS、Apollo/Nacos、服务发现和监控告警 Adapter；
- 目标环境依赖安全扫描、真实 9 接口负责人/报文确认；

仓库自动化已完成 198 个测试、0 失败、0 错误、0 跳过的全量 Maven 验证，包含真实 MySQL/Redis、Netty Reset/Timeout 和双 JDK 调用链。Codex Windows 环境需要 `-Djdk.net.unixdomain.tmpdir=Z:\codex-selector-fallback` 避开宿主 Unix-domain selector 唤醒异常；普通终端和 CI 无需该参数。

精确启动和人工验收步骤见 [本地启动与人工验收指南](development/manual-acceptance-guide.md)，故障与性能口径见 [MVP 运维、性能与故障演练](development/mvp-operations-and-fault-drills.md)。
