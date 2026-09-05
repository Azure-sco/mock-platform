# MVP 运维、性能与故障演练

本文定义可重复的 MVP Exit 运行口径。任何演练都必须记录环境、Release、activationVersion、配置、开始/结束时间、请求总数、用户可见错误数、恢复时间和原始日志位置。

## 1. 性能门禁

### 1.1 前置条件

- 验收环境已通过 Control 发布 OA/e签宝/共享平台九接口完整目录。
- Runtime 至少 2 实例；每个实例加载同一 Release/activationVersion 并 ACK READY。
- MySQL 8、Redis 7、Callback 接收端和 Control 健康。
- Callback Worker 在“创建 Task”性能阶段关闭，避免接收端能力混入 Runtime 指标；Callback 发送性能另测。
- 客户端到 Runtime 同机房，HTTP/1.1 Keep-Alive。
- 监控采集 CPU、Heap、GC、线程、连接池、JDBC Scheduler、Event Loop pending task、MySQL/Redis 延迟。

### 1.2 正式命令

在仓库根目录执行：

```powershell
.\scripts\perf\runtime-mvp.ps1 `
  -BaseUrl http://runtime-vip:19091 `
  -Token '<验收 MockApp Token>'
```

脚本固定执行：

```text
100 个并发连接
预热 5 分钟 @ 200 RPS
稳态 10 分钟 @ 200 RPS
峰值 2 分钟 @ 500 RPS
1 MB 请求 5 分钟 @ 20 RPS / 20 连接
```

普通流量模型：50% 固定响应、20% 模板响应、15% QUERY_COUNT Flow、5% Flow Transition+Callback Task、10% 预期 `MOCK_NO_MATCH`。Flow 在每个计时阶段前通过真实 Runtime/MySQL 预置，预置流量不进入 percentile。

结果保存在 `build/perf-results/runtime-mvp-*.json`。`-Quick` 只检查脚本、身份和目录是否可用；不得作为正式验收结果。

### 1.3 通过标准

| 阶段 | 标准 |
|---|---|
| 稳态 200 RPS | 整体 P95 <50 ms，P99 <100 ms |
| 峰值 500 RPS | 整体 P95 <100 ms，平台非预期错误率 <0.1% |
| 1 MB 请求 | P95 <200 ms，无 OOM、无 Event Loop 阻塞 |

预期 `MOCK_NO_MATCH` 是正确业务结果，不计平台错误。连接失败、`MOCK_INTERNAL_ERROR`、非预期超时、正文或状态不符均计错误。当前 1 MB 档验证大请求；若业务要求 1 MB 响应，必须增加对应已审批 Scenario 后单独复测，不能用小响应结果代替。

## 2. 场景故障门禁

在支持创建 Netty selector 的主机执行真实 TCP 集成测试：

```powershell
.\mvnw.cmd -t .mvn\toolchains.xml `
  -pl mock-platform-runtime -am `
  '-Dtest=ScenarioFaultHttpIntegrationTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmock.integration.netty-fault=true' `
  test
```

要求客户端观察到真实连接异常/超时，而不是平台包装的 HTTP 500。另需逐项验证：

- `HTTP_ERROR + NO_APPLY`：返回配置状态码，Flow/Event/Callback 不变。
- `READ_TIMEOUT + APPLY_BEFORE_FAULT`：客户端超时，但相同 MockRequestId 重试复用已提交结果，不二次迁移。
- `CONNECTION_RESET + NO_APPLY`：连接断开且不访问真实第三方。
- 普通 `delayMs`：Reactor 非阻塞延迟，无 Event Loop sleep。

若 Codex Windows 宿主报告 `failed to open a new selector`，先使用下述 fallback 参数重跑；若仍失败，再记录为环境阻断并在普通 PowerShell、WSL/Linux CI 或部署环境重跑。不得将 skipped 测试计为通过。

当前 Codex Windows 宿主可在 Maven 命令增加 `-Djdk.net.unixdomain.tmpdir=Z:\codex-selector-fallback`，让 selector 唤醒使用 TCP fallback。该参数不是应用部署参数；普通 CI/部署环境应先不设置，并直接验证平台 socket 能力。

## 3. Release 与缓存演练

### 3.1 Redis Active Pointer 丢失

1. 记录当前 Release/activationVersion 和 Redis Pointer。
2. 只删除该验收 Scope 的 `mock:active-release:<ENV>:<APP>`，禁止清空整个 Redis。
3. 触发 Runtime Refresh。
4. 确认 Runtime 从 MySQL 恢复同一 Release 和真实 activationVersion，先写 Snapshot 再写 Pointer。
5. 验证请求持续使用 Last Known Good，无部分 Snapshot。

自动化依据：`ReleaseRefreshCoordinatorTest`、`RedisReleaseProjectionAdapterTest`、`LocalActiveReleaseRegistryTest`。

### 3.2 Snapshot 篡改/未知密钥

1. 在隔离环境构造 checksum、signature、keyId 或 canonical bytes 不匹配的候选 Snapshot。
2. 触发加载。
3. 要求 FAILED ACK，Active Release 不切换，旧 LKG 继续服务。

自动化依据：`RuntimeSnapshotEnvelopeVerifierTest` 和 `ReleaseSnapshotCompilerTest`。

### 3.3 发布期间节点失败

1. 两个 Runtime 节点均为 READY。
2. 发布新 Release，在一个节点加载前停止该节点。
3. 确认 Activation 为 PARTIAL，而不是 APPLIED；健康节点不回退旧 Release。
4. 节点恢复并 ACK 后确认收敛，或经审批 Waive 后确认审计记录。
5. 验证并发发布仅一个 expectedActivationVersion 成功。

## 4. 数据库与节点演练

### 4.1 单 Runtime 节点退出

- 压测保持 200 RPS，停止一个实例。
- 负载均衡在健康检查窗口内摘除节点。
- 记录错误数和恢复时间；现有 Flow 在另一节点继续使用固定 Release/Definition。

### 4.2 MySQL 短暂故障

- 只在隔离验收环境暂停 MySQL 连接 10～30 秒。
- 无 Flow 的已加载 Snapshot 响应可继续；需要 RequestExecution/Flow 的请求应快速返回稳定平台错误，不允许内存推进。
- 恢复后相同 MockRequestId 不重复产生副作用。

### 4.3 Redis 切换

- 模拟 Redis 主从切换或短暂不可用。
- 已加载 Release 使用 LKG；恢复后从 MySQL 权威状态重建投影。
- 安全 Admission 超过允许陈旧窗口必须 fail-closed，不能为了可用性延长租约。

## 5. Callback Worker 演练

### 5.1 Worker Claim 后崩溃

1. Task 被 Claim 后、HTTP 前终止 Worker。
2. Lease 到期后由另一 Worker 获取新 fencing token。
3. 旧 Worker 即使恢复也不能 Finalize。

### 5.2 HTTP 已发送、Finalize 前崩溃

1. 接收端确认收到 DeliveryId 后终止 Worker 数据库连接。
2. Attempt 保留 STARTED/UNKNOWN 可恢复语义。
3. 重投使用同一 DeliveryId，接收端按 DeliveryId 幂等。
4. 预算耗尽进入 `FAILED_UNCONFIRMED`，不能标记为 NEVER_SENT。

### 5.3 Preparation 与发送失败

- 非 Allowlist URL、DNS 私网/回环绕过、Redirect、缺少签名 Secret 必须在 Preparation 失败。
- HTTP 500 是 `CONFIRMED_RESPONSE`，按固定间隔消耗 Send Retry。
- DNS/连接中断是 `UNKNOWN`；Preparation Retry 不消耗 Send Retry。

自动化依据：`SecureCallbackDispatcherTest`、`CallbackWorkerTransactionsTest`。

## 6. Availability SLI

Runtime SLI：

```text
成功执行的合格 Mock 请求数 / 合格 Mock 请求总数
```

认证、Contract 和大小合法才属于合格请求。按场景正确产生 HTTP Error/Delay/Timeout/Reset 计成功；`MOCK_INTERNAL_ERROR`、`MOCK_RELEASE_UNAVAILABLE`、`MOCK_ADMISSION_POLICY_STALE`、非预期连接失败计不可用。NO_MATCH 单独作为配置质量指标，不进入 SLI。

目标滚动 30 天 >=99.9%，不可用预算 <=43.2 分钟。计划维护、MySQL、Redis、网络和容器平台造成的 Runtime 失败均消耗预算。仓库内只定义了采集语义；没有真实连续 30 天数据时，不得声称该项通过。

Callback 敏感内容的到期清理由 `mock.callback.retention-cleanup.*` 控制，默认每分钟最多处理 100 个已过期终态 Task。每个 Task 在短事务内重新锁定并核对状态，先删 Attempt 再删 Task；RUNNING、NEW、RETRYING 和状态已变化的任务不删除。监控 `mock.callback.retention.deleted`、`mock.callback.retention.failures` 和 `mock.callback.retention.duration`。

## 7. Request Log 分区与 Dashboard 聚合运维

- Flyway V3 包含 `mock_request_log` 的 UTC 日粒度分区和 `mock_request_metric_minute`；Runtime 在同一事务写明细和分钟聚合。
- Control 默认每 6 小时获取 MySQL named lock 后维护一次：保留未来 31 天分区、删除 7 天前的日分区，并删除同期限以前的分钟聚合桶。
- 配置项为 `mock.request-log.partition.enabled/future-days/retention-days/initial-delay-ms/fixed-delay-ms`。生产修改保留期前必须完成容量与合规评审。
- 维护失败时 `p_future` 保证写入不中断，但必须告警；不要手工删除 `p_future`。数据库账号需要 `ALTER` 和指标表 `DELETE` 权限。
- 验证查询：

```sql
SELECT partition_name, partition_description
FROM information_schema.partitions
WHERE table_schema = DATABASE() AND table_name = 'mock_request_log'
ORDER BY partition_ordinal_position;

SELECT MIN(bucket_start), MAX(bucket_start), SUM(request_count)
FROM mock_request_metric_minute;
```

Dashboard 请求量、命中率、NO_MATCH 和 P95 必须来自 `mock_request_metric_minute`，禁止临时改回扫描明细表。

## 8. 演练记录模板

```text
演练名称：
环境/拓扑：
Git revision：
Release / activationVersion：
开始/结束时间：
请求总数：
平台非预期错误数：
用户可见错误数：
恢复时间：
是否消耗 Availability Budget：
日志/指标/压测结果路径：
结论：通过 / 不通过 / 环境阻断
遗留问题与负责人：
```
