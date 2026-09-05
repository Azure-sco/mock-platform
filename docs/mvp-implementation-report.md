# Mock Platform MVP 实施报告

## 1. 完成结论

本轮已完成 M1、M2、M3、M4 的平台实现与自动化验证，并完成 M5 的 Web Console、安全受限契约导入、真实基础设施门禁、Request Log 日分区与分钟预聚合、性能压测工具、运维与故障演练文档。

本报告按“可用 MVP 优先、不继续追求完美实现”的范围冻结。未验证的 V8 Dashboard 趋势/异常扩展及 Flow 后台物理清理扩展已撤销；数据库预发布迁移已收敛为 V1～V3。此后不把 Remaining Issues 当作本轮继续开发项。

当前代码已经形成可构建、可测试、可启动、可由 SDK 接入的 MVP 主链路，但尚不能把“全部 MVP Exit 已完成”作为正式结论。仓库内自动化门禁已经通过；剩余工作主要是目标环境与业务验收证据：正式压测、30 天 SLI、双实例滚动与基础设施故障演练，以及业务方九个真实接口联调和公司权限/密钥/配置中心适配确认。

里程碑状态：

| 里程碑 | 状态 | 说明 |
| --- | --- | --- |
| M1 数据与基础 Mock | 完成 | Provider、API、契约、场景、模板、请求日志及 SDK 路由已实现 |
| M2 审批、发布与安全配置 | 完成 | 审批、发布、回滚、Outbox、Runtime ACK、签名校验及 SDK 配置激活已实现 |
| M3 Flow、幂等与回调 | 完成 | Flow 状态、请求执行记录、故障语义、Callback Worker 租约与 fencing 已实现 |
| M4 九接口示范与跨接口 Flow | 完成 | 九接口目录、故障场景、OA 与共享 TIME Flow 自动化语义验证已实现 |
| M5 Exit 工具与控制台 | 部分完成 | 功能与工具已实现；正式性能、真实业务联调和运维演练证据待目标环境执行 |

## 2. 主要实现能力

### 2.1 SDK 与路由

- JDK 8 / JDK 17 客户端按照 `providerId + apiId + mockAppId` 解析路由。
- 本地、Apollo、Nacos 配置适配器统一输出激活元数据。
- SDK 配置采用 canonical JSON 与签名验证；版本、过期时间、策略引用或签名异常时 fail-closed。
- SDK 激活结果可 ACK 回传，并使用有界重试；旧激活版本不能覆盖新版本。
- 示例工程保留真实调用与 Mock 路由的切换能力。

### 2.2 Control 管理面

- 提供 Provider、API、契约版本、场景、审批、发布、回滚、安全策略、SDK 配置、Flow、Callback、请求日志、审计和 Dashboard 管理 API。
- 支持本地 OpenAPI 3.0 JSON/YAML 和 JSON Schema 文件导入为 Contract Draft；限制文件大小、UTF-8、节点/深度/引用、线程池队列和解析超时，拒绝外部/递归 `$ref`、YAML Alias/Anchor、压缩包和超限输入，失败不生成半成品。
- 契约和发布对象采用版本化模型；发布只激活不可变版本，回滚恢复历史激活版本。
- 管理写操作执行操作人身份校验，并记录审计事件。
- Runtime 在同一数据库事务中写 Request Log 与分钟级计数/延迟直方图；Dashboard 只读取预聚合指标，不扫描 Request Log 明细。

### 2.3 发布与一致性

- MySQL 保存权威发布状态、激活状态、目标状态、ACK 和 Outbox。
- 发布事务只写数据库，不在事务中访问 Redis 或 Runtime。
- Outbox Worker 将可重建投影发布到 Redis，并使用签名激活消息通知 Runtime。
- Runtime 校验激活签名和版本后切换不可变快照并 ACK；旧版本激活被拒绝。
- Redis 丢失时可由 MySQL 权威状态重新投影，不允许 Redis 反向覆盖数据库。

### 2.4 Runtime 匹配与执行

- 按 Provider、API、MockApp、HTTP 方法、路径、查询、Header 和 Body 规则匹配场景。
- 支持固定响应和模板响应。
- 支持 HTTP 错误、读超时、连接重置以及故障前应用/不应用业务副作用。
- 请求执行记录持久化最终响应与故障结果；相同幂等键重放相同结果，避免重复 Flow 变更和重复 Callback。
- 过期 Request Execution 由有界批处理 Worker 清理；删除前重新锁行并核对 generation/expireAt，不能误删已经复用的新 generation。
- Runtime 不直接访问真实第三方，出站只由受控 Callback Worker 执行。

### 2.5 Flow、并发与 Callback

- Flow Definition 与版本不可变；Flow Instance 保存业务状态、当前步骤、版本号和过期时间。
- 状态迁移在数据库事务中完成，使用稳定幂等键和行级并发控制保护业务不变量。
- Callback Task 和 Attempt 可审计；Worker 使用租约、fencing token、有界重试与退避，旧 Worker 无法提交结果。
- Callback 目标执行白名单、协议、DNS/IP、超时和响应大小校验；策略缺失或目标不可确认时拒绝出站。
- Callback 保留期清理只处理已过期终态 Task，在事务内锁定 Task、重新核对状态/expireAt，并先删 Attempt 后删 Task；运行中或被人工重试的任务不会被误删，删除量/失败数/耗时提供指标。

### 2.6 Web Console

已实现 Dashboard 以及 Provider、API、契约、场景、审批、发布、请求、Flow Definition、Flow Instance、Callback、安全策略、SDK 配置和审计页面，共 14 个管理视图。页面调用 Control 管理 API，并显示真实聚合指标和审计信息。

### 2.7 九接口示范目录

示范数据覆盖：

- OA：3 个接口；包含跨接口业务 Flow。
- CPS/EQB：3 个接口；包含固定响应、模板响应及故障场景。
- 共享平台：3 个接口；包含 TIME 跨接口 Flow。

自动化测试验证了目录完整性、匹配语义、错误场景以及 OA / 共享平台跨接口状态推进。

## 3. 关键架构决策

```text
业务应用 -> Mock SDK -> Runtime 不可变快照 -> 匹配/模板/Flow/故障
                                  |
                                  +-> 请求执行与请求日志 -> MySQL
                                  +-> Callback Task -> 受控 Callback Worker

Web Console -> Control -> MySQL 权威状态 -> Outbox -> Redis 投影 -> Runtime 激活/ACK
```

- Control 与 Runtime 分离：管理写路径不进入请求热路径。
- MySQL 为权威存储，Redis 仅为可恢复投影。
- 发布采用事务 Outbox，避免 MySQL/Redis 不可恢复双写。
- Runtime 只消费已签名、已发布的不可变快照。
- 幂等结果、Flow 状态和 Callback 任务都持久化，不能只依赖进程内存。
- Callback 是唯一受控出站边界，Runtime 本身不访问真实第三方。

相关决策记录位于 `docs/adr/`。

## 4. 数据库变更

按当前预发布 MVP 的精简要求，Flyway 迁移收敛为 3 个版本：

- `V1__platform_bootstrap.sql`：基础结构与审计。
- `V2__m1_control_contract_and_request_log.sql`：Provider、API、契约版本、请求日志。
- `V3__mvp_complete_schema.sql`：场景、审批、发布、激活、目标、ACK、Outbox、安全策略、SDK 配置、Flow、Callback、Request Execution、Request Log 日分区及分钟预聚合。

本次把尚未发布的 V4～V7 合并到 V3，因此已有本地开发数据库必须清库重建，不能沿用旧 `flyway_schema_history`。正式发布后不得再改写 V1～V3；后续数据库调整需先由产品负责人明确批准版本策略。

## 5. 验证结果

### 5.1 已通过

- 全量 Maven `verify`：14 个 Reactor 模块全部成功；自动化报告 198 个测试，0 失败、0 错误、0 跳过。
- 迁移收敛后最小回归：Control/Runtime 相关 Java 测试 12/12、Web TypeScript 类型检查通过。
- 真实 Docker 基础设施门禁：MySQL 8.0.36、Redis 7.2、Flyway V1-V3、约束/事务、日分区维护、Dashboard 预聚合 SQL、发布 Outbox、Redis 投影和 Runtime ACK，4/4 通过。
- Runtime 真实 MySQL：迁移收敛后 3/3 通过；Request Log 与分钟聚合原子写入、直方图累加以及 Request Execution 清理 generation fencing 通过。
- OpenAPI/JSON Schema 导入：10 个解析与恶意样本测试，以及 API 上传到 Contract Draft 的集成测试通过。
- M4 目录与跨接口 Flow：3/3 通过。
- 真实 Netty 故障测试：TCP Connection Reset / Read Timeout 链路通过；JDK 8 与 JDK 17 实际 Runtime 端到端调用通过。
- Web：TypeScript 类型检查、ESLint、Vite 生产构建通过。
- Java：Control、Runtime、JDK 8 / JDK 17 示例模块均完成编译和打包；JDK 17 示例 JAR 已生成。
- 性能工具源码使用 JDK 17 编译通过。

### 5.2 尚未形成的目标环境证据

Codex Windows 宿主的 Unix-domain selector 唤醒路径异常已通过 JVM 参数 `-Djdk.net.unixdomain.tmpdir=Z:\codex-selector-fallback` 强制 TCP fallback 绕开；带真实 socket、Docker 和双 JDK 的全量验证已经完成。该参数只用于此 Codex Windows 执行环境，普通终端/CI 无需设置。

固定性能脚本尚未执行正式 17 分钟流量阶段和 5 分钟 1 MB 请求阶段，因为正式压测要求双 Runtime、完整正式发布目录和资源监控。脚本和输出格式已经就绪。

## 6. 与目标方案的偏差

- M5 的性能阈值、容量结论、业务方九接口联调、权限矩阵和故障演练仍缺正式签字/记录，不能用单元测试替代。
- 示例 fixture 用于自动化语义验证；正式发布目录必须通过 Control 的审批、发布、Outbox 和 Runtime ACK 链路落地。
- Web 生产构建存在单个 JavaScript chunk 超过 500 KB 的 Vite 告警，不影响当前功能验收，但应在后续前端性能优化中处理。
- 按用户要求，本轮不执行独立代码审查；未创建分支、提交、推送或 PR。

## 7. Remaining Issues

1. 按 `scripts/perf/runtime-mvp.ps1` 在双 Runtime 验收环境执行正式固定压测，记录 p50/p95/p99、错误率、吞吐和资源使用。
2. 通过 Control 正式发布九接口目录，验证 Runtime ACK 后由三个业务域完成真实 SDK 联调。
3. 执行发布/回滚、Redis 丢失重建、数据库/节点故障、Callback 重试与 fencing 演练并归档证据。
4. 连续采集 30 天 Runtime Availability SLI。
5. 完成公司 SSO/RBAC、Service Identity、KMS、Apollo/Nacos、发现/摘流、告警和运维责任人确认，并执行目标环境依赖安全扫描。

具体自行启动步骤见 `docs/development/manual-acceptance-guide.md`，性能和故障演练步骤见 `docs/development/mvp-operations-and-fault-drills.md`，能力细节见 `docs/current-implementation-capabilities.md`。
