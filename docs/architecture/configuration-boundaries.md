# 配置边界

## 配置层次

- `application.yml`：非敏感默认值、端口、功能边界；数据库连接使用无默认值的环境变量。
- `application-local.yml`：只允许本机可识别的 fixture（例如 `local-token-8/17`）和本地诊断显示。
- `application-test.yml`：测试隔离项；测试所需 token 由测试显式注入。
- 生产环境：DB password、token、证书和公司能力凭证必须来自环境变量、配置中心或 Secret 系统。

## 核心环境变量

| 变量 | 消费者 | 含义 |
|---|---|---|
| `MOCK_MYSQL_URL/USERNAME/PASSWORD` | Control、Runtime | MySQL 连接 |
| `MOCK_REDIS_HOST/PORT` | Control、Runtime | Redis 连接 |
| `MOCK_CONTROL_PORT` | Control | 默认 `19090` |
| `MOCK_RUNTIME_PORT` | Runtime | 默认 `19091` |
| `MOCK_RUNTIME_BASE_URI` | SDK Sample | Runtime 地址 |
| `MOCK_MODE` | SDK Sample | REAL/MOCK/CANARY 启动初值 |
| `MOCK_APP_TOKEN` | SDK Sample | Runtime MockApp token；生产必须外部注入 |

## 动态配置

M0 使用 `LocalConfigProvider` 验证无重启 Snapshot 切换。Apollo/Nacos 模块只暴露“收到并验证完整 Snapshot 后整体替换”的适配边界，没有引入公司客户端、Namespace、鉴权或发布流程，因此不得描述为正式接入。

## 安全约束

- Local Operator/MockApp 验证器仅在 `local`/`test` Profile 注册。
- 非 local/test 使用 fail-closed 实现；配置了看似有效的本地 Header/token 也不会放行。
- `.env`、`.mvn/toolchains.xml`、构建产物和 IDE 文件均在 `.gitignore` 中。
