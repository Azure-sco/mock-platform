# Phase 0 + M0 Checklist

状态日期：2026-08-20。`PASS` 表示已有实现与自动化证据；`BLOCKED` 表示当前机器缺少外部运行条件，而非伪造通过。

## Phase 0

| 项目 | 状态 | 证据 |
|---|---|---|
| Maven Reactor / Wrapper | PASS | 根 POM、Wrapper、双 JDK toolchains |
| Java 8/17 真实编译隔离 | PASS | compiler/Surefire/Failsafe toolchain 日志 |
| Boot 2/3 依赖隔离 | PASS | 独立 BOM 和模块边界 |
| SDK 不依赖平台后端 | PASS | Reactor 依赖图 |
| Control/Runtime/Samples 启动 | PASS | Spring 集成与 E2E 随机端口启动 |
| Control/Runtime Health | PASS | API/WebTestClient 与启动探针 |
| MySQL/Redis/Flyway/MyBatis/事务 | BLOCKED | Testcontainers 门禁存在；当前执行机无 Docker |
| Docker Compose | BLOCKED | Compose 文件存在；当前执行机未安装 Docker |
| Web type-check/lint/build | PASS | npm 脚本 |
| README 从零启动说明 | PASS | 根 README |
| 无真实 Secret/空业务 CRUD/TODO | PASS | 配置与源码扫描；只保留 local/test fixture |

## M0

| 能力 | 状态 |
|---|---|
| `MockContext` 栈、嵌套、异常清理 | DONE |
| 不可变 `RoutingSnapshot` 与原子整包更新 | DONE |
| REAL/MOCK/CANARY 路由 | DONE |
| Boot 2/3 Feign Client decorator | DONE |
| Boot 2/3 RestTemplate RequestFactory decorator | DONE |
| 原请求不变；Mock 请求 URL/body/header 复制 | DONE |
| 凭证剥离及 Runtime 实收验证 | DONE |
| 四个 OA/CPS_EQB 固定响应 | DONE |
| Request ID 生成、Retry 复用、跨调用区分 | DONE |
| `FAST_FAIL/FALLBACK_RESPONSE/FALLBACK_REAL` | DONE |
| 非生产且连接前白名单回退；其余拒绝 | DONE |
| 四客户端实际 Runtime Connection Reset | DONE |
| Fake Real 调用次数证明无静默回退 | DONE |
| Apollo/Nacos 正式接入 | BLOCKED（外部公司能力未提供） |

M1+ 能力没有被提前实现。
