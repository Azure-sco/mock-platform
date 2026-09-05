# Scripts

- `verify.ps1`：执行 Maven 全量 `clean verify`、Web 的 `npm ci/type-check/lint/build`。
- `verify.ps1 -WithDocker`：在上述检查后，使用根目录 `.env` 启动并显示 Docker Compose 状态。
- `perf/runtime-mvp.ps1`：使用 JDK 17 执行固定 MVP Runtime 流量模型并输出 JSON 结果；默认是正式 5 分钟预热、200/500 RPS 和 1 MB 三档，`-Quick` 仅用于脚本冒烟。

脚本不会创建 `.env` 或 `.mvn/toolchains.xml`，请先按根 README 配置。
