# M0 外部依赖状态

| 能力 | 当前状态 | M0 实现 | 正式接入仍需 |
|---|---|---|---|
| Apollo | Adapter boundary | Java 8 Snapshot 回调适配器 | 公司 Apollo 客户端、Namespace、鉴权、发布/回滚 |
| Nacos | Adapter boundary | Java 17 Snapshot 回调适配器 | 公司 Nacos 客户端、DataId/Group、鉴权、发布/回滚 |
| SSO | Local Adapter | `local/test` Header 身份验证 | 正式协议、会话/token 校验、用户映射 |
| RBAC | Local Adapter | `MOCK_VIEWER/MOCK_ADMIN` 服务端角色门禁 | 公司权限码、资源归属与租户授权 |
| Service Identity | Local Adapter | `local/test` MockApp token 映射 | 正式服务身份签发、轮换与校验 |
| KMS/Secret | Not integrated | 无；生产敏感配置无代码默认值 | 公司 KMS/Secret Provider、轮换、审计 |
| mTLS | Not integrated | 无 | 证书签发、信任链、轮换、吊销 |
| Service Discovery | Not integrated | Runtime 使用显式 base URI | 公司注册发现与网络策略 |
| Instance Drain | Not integrated | 无 | Runtime 节点登记、摘流、激活 ACK |
| Company Monitoring | Not integrated | 仅 Spring Actuator/Micrometer 基础 | 指标出口、日志规范、告警路由 |

除 `local`/`test` 外，Operator 与 MockApp 身份均 fail-closed。表中的 Adapter boundary 不是“已接入公司基础设施”。

Docker、MySQL、Redis 和 Maven/npm 仓库是本地/CI 工具依赖，不属于公司能力；Docker 不可用时基础设施门禁会明确显示 skipped/BLOCKED。
