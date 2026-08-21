# SDK 兼容性矩阵

| Sample | 实际测试 JVM | Spring | 客户端 | REAL | 实际 Runtime MOCK | Reset 后不回退 |
|---|---:|---|---|---|---|---|
| `mock-sample-jdk8` | JDK 8 | Boot 2.7.15 / Cloud 2021.0.5 | OpenFeign | 覆盖 | Failsafe 跨进程覆盖 | Feign 覆盖 |
| `mock-sample-jdk8` | JDK 8 | Boot 2.7.15 | RestTemplate | 覆盖 | Failsafe 跨进程覆盖，含 multipart | RestTemplate 覆盖 |
| `mock-sample-jdk17` | JDK 17 | Boot 3.1.6 / Cloud 2022.0.4 | OpenFeign | 覆盖 | 进程内真实 Reactor Netty Runtime | Feign 覆盖 |
| `mock-sample-jdk17` | JDK 17 | Boot 3.1.6 | RestTemplate | 覆盖 | 进程内真实 Reactor Netty Runtime | RestTemplate 覆盖 |

额外兼容性事实：

- Boot 2/3 各自实现 Feign `Capability`，可装饰 OpenFeign child context 中的底层 Client。
- Feign Retry 在同一 `MockContext` 中复用 Request ID；新的业务方法调用生成新 ID。
- OA multipart/form-data 的 method/path/body/content type 与业务 Header 可复制到 Runtime；真实 Authorization/Cookie/Signature 不会出现在 Mock 请求。
- CPS 使用 Provider `CPS_EQB`，保留 `channel=EQB` query 和普通业务 Header `domain`。
- Java 8 core/SPI 不依赖 Boot、Jakarta、Control 或 Runtime。
