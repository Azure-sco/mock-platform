# ADR-0004：本地发布链路复用正式快照协议

日期：2026-09-29；状态：采用。

## 问题

`local` 的 Runtime 读取 Fixture，Control 的发布投影保存在内存，不能用 Web 发布改变请求响应。独立进程没有共享可信签名密钥；Runtime 的 JDBC ACK 已落库，但 Control 未消费它来收敛 Activation。

## 决策

- 保持原 `local/test` 行为。显式同时启用 `local,local-published` 时，Control 使用 Redis 发布投影，Runtime 使用现有签名快照验证、通知/轮询、缓存、MySQL 恢复和版本切换逻辑。没有有效 Release 时拒绝请求，不回退 Fixture。
- 本地发布仅绑定 `127.0.0.1`，仅支持 `TEST`，保留现有本地身份、角色与 MockApp Token 校验。它不是生产身份或签名 Admission 策略的替代品，生产 fail-closed 适配器不变。
- 本地 RSA 私钥保存在 Git 忽略目录中，并限制 Windows 文件 ACL；Control 读取持久私钥和公钥，Runtime 启动时显式信任同一公钥。缺失或不匹配的密钥阻止启动，不自动降级或重建旧密钥。
- Runtime 为明确配置的应用和节点写入短期 Redis 心跳；发布必须捕获存在心跳的目标。心跳不代表版本加载成功，只有相同环境、应用、Release、activationVersion、节点的真实 ACK 才能确认 READY。过期心跳不作为已摘流证据。
- Control 定时读取已持久化、匹配目标的 ACK，通过现有事务服务推进状态和审计。继续遵守 MySQL 权威状态及 Outbox，不让 Runtime 修改 Control 的 Activation 状态。
- 此模式禁用旧的测试签名 HTTP ACK 验证器，发布确认仅走 Runtime JDBC ACK 链路。
- 此本地模式支持无状态 Scenario（固定响应、模板和已有匹配规则）。有 Flow 实例或包含 Flow/Callback 的 Release 拒绝激活；不冒充已接通完整生产 Flow、KMS、安全策略与摘流适配。真实流量摘除能力缺失时保持 fail-closed，不生成虚假成功。
- 开发 Web 可切换管理员与审批人，仍由服务端阻止最后修改人自审。生产构建不显示该选择器。

## 验证与回滚

真实 MySQL/Redis 测试验证 ACK 身份/版本匹配和收敛；签名测试验证跨进程重启、篡改和密钥缺失；Profile 测试证明无 Fixture 回退。端到端脚本使用管理 HTTP API 创建四接口目录、契约、场景并审批发布，验证固定响应 V1/V2 和回滚，期间 Runtime PID 不变。

回退到 Fixture 需显式以 `local` 重启；不能将其视为发布回滚。业务配置回滚通过 Release API 创建新的 activationVersion，保留审计和历史版本。
