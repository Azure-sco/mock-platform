# Scripts

- `verify.ps1`：执行 Maven 全量 `clean verify`、Web 的 `npm ci/type-check/lint/build`。
- `verify.ps1 -WithDocker`：在上述检查后，使用根目录 `.env` 启动并显示 Docker Compose 状态。

脚本不会创建 `.env` 或 `.mvn/toolchains.xml`，请先按根 README 配置。
