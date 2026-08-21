# ADR-0003：双 JDK SDK 线物理隔离

状态：Accepted

## 决策

Java 8 core/SPI 是最低公共契约；Boot 2 starter 保持 Java 8，Boot 3 starter 和平台保持 Java 17。根 POM 不继承 Spring Boot parent，各线独立导入 BOM，并使用 Maven Toolchains 真实编译和测试。

## 影响

存在少量 Boot 2/3 传输装饰代码重复，但不会通过兼容分支把 Jakarta/Boot 3 污染到 Legacy 应用。共享行为由 core 测试与两套端到端 Harness 约束。
