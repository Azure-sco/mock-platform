# 数据库约定

## 当前基线

M0 只有 `mock_platform_bootstrap` 与 `mock_audit_log` 两张基础设施表，用于证明 Flyway 和数据库链路；没有提前创建技术方案中的业务表。DDL 使用 MySQL 8、InnoDB、`utf8mb4/utf8mb4_0900_ai_ci`。

## 后续迁移规则

- 表名使用小写 snake_case，平台业务表统一 `mock_` 前缀。
- 主键使用显式 `BIGINT` 或经 ADR 确认的 UUID 方案；不得依赖业务字段作为可变主键。
- 时间字段命名为 `created_at`、`updated_at`、`completed_at` 等，使用 `TIMESTAMP(6)`，应用边界明确时区。
- 状态字段使用稳定、受代码约束的字符串枚举；禁止依赖数据库隐式转换。
- JSON 只存边界清晰、无需关系约束的结构；权威状态、幂等键、关联和审批结果必须是可索引列。
- 索引名表达用途；唯一约束必须直接承载已确认的业务不变量。多列索引按真实查询前缀设计。
- 可修改业务对象补齐 `created_by/created_at/updated_by/updated_at`；发布、审批、安全和人工变更另写不可抵赖审计。
- Flyway migration 一经发布不得原地修改；新变化增加新版本，并验证升级与滚动发布。
- 大表分区、在线迁移和清理策略只有在对应里程碑有真实消费者与容量数据时引入。

## 事实来源

MySQL 保存所有需要恢复的权威业务状态。Redis key 必须以 `third-party-mock:` 开头，必须有明确 TTL/重建语义，且不得反向覆盖 MySQL。
