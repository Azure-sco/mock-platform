# Mock Platform 本地启动与人工验收指南

适用环境：Windows PowerShell，仓库 `D:\workspace\java\mock-platform`。每个代码块都注明执行目录；没有特别说明时均在仓库根目录执行。

## 第 1 步：检查工具

打开 PowerShell：

```powershell
Set-Location D:\workspace\java\mock-platform
docker version
docker compose version
node -v
npm -v
& 'C:\Program Files\Java\jdk1.8.0_491\bin\java.exe' -version
& 'C:\Program Files\Java\jdk-17.0.20\bin\java.exe' -version
.\mvnw.cmd -t .mvn\toolchains.xml -v
```

要求：Docker Client/Server 可用，Node >=20.19，JDK 8 和 JDK 17 均存在，Maven 主进程使用 JDK 17。若本机 JDK 路径不同，先编辑 `.mvn\toolchains.xml`，不要把该本机文件提交 Git。

## 第 2 步：准备环境变量

首次运行：

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }
notepad .env
```

至少替换 `MYSQL_PASSWORD` 和 `MYSQL_ROOT_PASSWORD`。随后将 `.env` 导入当前 PowerShell：

```powershell
Get-Content .env | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith('#')) {
        $name, $value = $line -split '=', 2
        Set-Item -Path "Env:$name" -Value $value
    }
}
$env:MOCK_JDK8_HOME = 'C:\Program Files\Java\jdk1.8.0_491'
$env:MOCK_JDK17_HOME = 'C:\Program Files\Java\jdk-17.0.20'
$env:JAVA_HOME = $env:MOCK_JDK17_HOME
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

确认 `.env` 中 `MOCK_MYSQL_PASSWORD` 与 `MYSQL_PASSWORD` 相同，且 URL 指向 `localhost:3306/mock_platform`。

## 第 3 步：启动 MySQL 8 和 Redis 7

```powershell
docker compose --env-file .env -f deploy\docker-compose.yml up -d
docker compose --env-file .env -f deploy\docker-compose.yml ps
docker exec mock-platform-redis redis-cli ping
```

要求：两个容器均为 `healthy`，Redis 返回 `PONG`。

## 第 4 步：运行真实基础设施门禁

```powershell
.\mvnw.cmd -t .mvn\toolchains.xml `
  -pl mock-platform-control -am `
  '-Dtest=InfrastructureGateTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  '-Dmock.integration.redis-container-exec-transport=true' `
  test
```

要求：4 个测试全部通过且 `Skipped: 0`。该门禁使用临时 MySQL 8.0.36/Redis 7.2，验证 Flyway V1～V3、约束、事务回滚、发布/Outbox/Redis/Runtime ACK、Request Log 日分区维护、Dashboard 预聚合 SQL 和 Audit SQL。

如果出现 `Unsupported Database: MySQL 8.0`：

```powershell
.\mvnw.cmd -t .mvn\toolchains.xml -pl mock-platform-control dependency:tree `
  '-Dincludes=org.flywaydb'
```

必须同时存在版本一致的 `flyway-core` 和 `flyway-mysql`；不要关闭 Flyway 绕过失败。

## 第 5 步：运行全量后端验证

```powershell
.\mvnw.cmd -t .mvn\toolchains.xml `
  '-Dmock.integration.loopback=true' `
  '-Dmock.integration.netty-fault=true' `
  clean verify
```

要求最终为 `BUILD SUCCESS`，且无 skipped。目标测试覆盖 JDK 8/17 SDK、M1 管理/Scenario、M2 发布和配置、M3 Flow/Callback、M4 九接口目录、真实 Netty Reset/Timeout 和 Docker 基础设施。

若仅在 Codex/Windows Java 子进程报告 `Unable to establish loopback connection` 或 `failed to open a new selector`，使用宿主 workaround：

```powershell
.\mvnw.cmd -t .mvn\toolchains.xml `
  '-Djdk.net.unixdomain.tmpdir=Z:\codex-selector-fallback' `
  '-Dmock.integration.loopback=true' `
  '-Dmock.integration.netty-fault=true' `
  clean verify
```

该参数会让 JDK selector 唤醒改走 TCP fallback，只用于当前 Codex Windows 宿主；普通 PowerShell、WSL/Linux CI 或部署环境不要照搬。

## 第 6 步：单独验证九接口和跨 API Flow

```powershell
.\mvnw.cmd -t .mvn\toolchains.xml `
  -pl mock-platform-runtime -am `
  '-Dtest=MvpDemoFixtureTest,MvpCrossApiFlowTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' `
  test
```

要求：3 个测试全部通过。它们验证 OA/e签宝/共享平台各 3 个核心 API、OA `CREATE -> QUERY -> COMMAND -> CALLBACK` 和共享平台 `TIME -> SUCCESS`。

## 第 7 步：构建启动包

若第 5 步已经成功，包已生成；否则只为排查启动问题可执行：

```powershell
.\mvnw.cmd -t .mvn\toolchains.xml package -DskipTests
```

检查：

```powershell
Test-Path mock-platform-control\target\mock-platform-control-0.1.0-SNAPSHOT.jar
Test-Path mock-platform-runtime\target\mock-platform-runtime-0.1.0-SNAPSHOT-exec.jar
Test-Path mock-fake-real-third-party\target\mock-fake-real-third-party-0.1.0-SNAPSHOT-exec.jar
Test-Path mock-sample-jdk8\target\mock-sample-jdk8-0.1.0-SNAPSHOT.jar
Test-Path mock-sample-jdk17\target\mock-sample-jdk17-0.1.0-SNAPSHOT.jar
```

五行都应为 `True`。

## 第 8 步：启动 Control、Runtime 和 Fake Real

分别打开 3 个 PowerShell，每个先执行第 2 步的环境变量导入。

窗口 A，Control：

```powershell
Set-Location D:\workspace\java\mock-platform
& "$env:MOCK_JDK17_HOME\bin\java.exe" `
  -jar mock-platform-control\target\mock-platform-control-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=local
```

要求：Flyway 校验 V1～V3，Tomcat 监听 19090。启动后 Control 会维护未来 Request Log 日分区和 7 天在线保留，并小批量删除过期终态 Callback Task/Attempt；数据库账号必须具备相应 `ALTER`/`DELETE` 权限。若本机数据库曾运行过合并前的 V4～V7，必须删除并重建 MVP 开发库后再启动。

窗口 B，Runtime（本地 M0/M1 无状态 Fixture）：

```powershell
Set-Location D:\workspace\java\mock-platform
& "$env:MOCK_JDK17_HOME\bin\java.exe" `
  -jar mock-platform-runtime\target\mock-platform-runtime-0.1.0-SNAPSHOT-exec.jar `
  --spring.profiles.active=local
```

要求：Netty 监听 19091。

窗口 C，Fake Real：

```powershell
Set-Location D:\workspace\java\mock-platform
& "$env:MOCK_JDK17_HOME\bin\java.exe" `
  -jar mock-fake-real-third-party\target\mock-fake-real-third-party-0.1.0-SNAPSHOT-exec.jar
```

要求：Tomcat 监听 19092。

## 第 9 步：检查健康和管理权限

在第 4 个 PowerShell：

```powershell
$viewer = @{
  'X-Operator-Id' = 'local-viewer'
  'X-Operator-Roles' = 'MOCK_VIEWER'
}
(Invoke-RestMethod http://localhost:19090/api/platform/health).data.status
(Invoke-RestMethod http://localhost:19091/actuator/health).status
(Invoke-RestMethod http://localhost:19092/actuator/health).status
(Invoke-RestMethod http://localhost:19090/api/dashboard/summary -Headers $viewer).code
```

要求依次为 `UP`、`UP`、`UP`、`OK`。

## 第 10 步：验证 Runtime Scenario

```powershell
$headers = @{
  Authorization = 'MockApp local-token-17'
  'X-Mock-Provider' = 'CPS_EQB'
  'X-Mock-Api' = 'CPS_SIGN_CREATE_START'
  'X-Mock-Request-Id' = 'manual-runtime-1'
}
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:19091/sign/create-and-start' `
  -Headers $headers -ContentType 'application/json' -Body '{"settleId":42}'
```

要求返回 Fixture 响应且响应 Header 包含 Scenario、Release、Activation Version 和 Trace ID。

错误身份：

```powershell
try {
  Invoke-WebRequest -UseBasicParsing http://localhost:19091/sign/create-and-start `
    -Headers @{ Authorization='MockApp wrong'; 'X-Mock-Provider'='CPS_EQB'; 'X-Mock-Api'='CPS_SIGN_CREATE_START' }
} catch { [int]$_.Exception.Response.StatusCode }
```

要求为 `401`。

## 第 11 步：启动和验证 JDK 8/JDK 17 Sample

窗口 D：

```powershell
Set-Location D:\workspace\java\mock-platform
$env:MOCK_MODE = 'MOCK'
& "$env:MOCK_JDK8_HOME\bin\java.exe" `
  -jar mock-sample-jdk8\target\mock-sample-jdk8-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=local
```

窗口 E：

```powershell
Set-Location D:\workspace\java\mock-platform
$env:MOCK_MODE = 'MOCK'
& "$env:MOCK_JDK17_HOME\bin\java.exe" `
  -jar mock-sample-jdk17\target\mock-sample-jdk17-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=local
```

调用：

```powershell
Invoke-RestMethod -Method Post 'http://localhost:19093/demo/oa/reviews?businessNo=SETTLE-MANUAL'
Invoke-RestMethod -Method Post 'http://localhost:19094/demo/cps/signatures?settleId=42'
```

MOCK 时来源必须是 Runtime Fixture；停止 Sample，把 `MOCK_MODE=REAL` 后重新启动，来源必须变为 `FAKE_REAL`。任何 MOCK 请求到达 Fake Real 都是失败。

## 第 12 步：构建和打开 Web Console

窗口 F：

```powershell
Set-Location D:\workspace\java\mock-platform\mock-platform-web
npm ci
npm run type-check
npm run lint
npm run build
npm run dev -- --host 127.0.0.1
```

打开 `http://127.0.0.1:5173/mock/dashboard`。逐项访问：Dashboard、Provider、API、Contract、Flow Definition、Scenario、Approval、Security Policy、SDK Config、Release、Flow、Callback、Request、Audit。要求无白屏、无未处理 JavaScript 错误；查看页使用 `MOCK_VIEWER`，写操作使用本地 `MOCK_ADMIN`/`MOCK_APPROVER` 身份。

在 Contract 页面点击“导入契约”，选择本地 OpenAPI 3.0 `.json/.yaml/.yml` 或 JSON Schema 文件。OpenAPI 只有一个 operation 时可省略 Path/Method；多个 operation 必须指定。成功后应新增 `DRAFT` Contract Version。再分别尝试外部 `$ref`、递归 `$ref`、YAML Anchor/Alias、压缩包或超过 5 MB 的文件，要求返回明确校验错误且不新增草稿。

## 第 13 步：理解本地 Fixture 与正式发布的边界

`local` Profile 使用 `FixtureRuntimeSnapshotRepository`，便于无公司 KMS/Service Identity 时验证规则引擎。`mvp-demo-fixture.json` 可编译验证九接口，但带 Flow 的实例仍受 MySQL 对 Release、Scenario Version、Flow Version 和 Security Policy 的真实外键约束。

因此：

- 自动化 `MvpCrossApiFlowTest` 验证完整 Flow 语义；
- 手工验证正式发布数据面时，必须先通过 Control 创建、审批、发布完整目录，并配置共享的 Snapshot 公钥/KMS、Runtime Service Identity、节点发现和 Admission；
- 不能仅把 Runtime 参数改为 `mvp-demo-fixture.json` 后就把带 Flow 的正式发布链路记为通过。

这也是目标环境联调项，不应通过移除外键、关闭签名或改用内存状态绕过。

## 第 14 步：运行性能和故障门禁

在已经发布完整九接口目录的验收环境：

```powershell
.\scripts\perf\runtime-mvp.ps1 -BaseUrl http://runtime-host:19091 -Token '<验收 MockApp Token>'
```

`-Quick` 仅检查脚本和小流量链路，不是正式结果。精确指标、故障演练和记录模板见 [MVP 运维、性能与故障演练](mvp-operations-and-fault-drills.md)。

## 第 15 步：停止与清理

所有 Java/Vite 窗口按 `Ctrl+C`。检查端口：

```powershell
Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
  Where-Object LocalPort -in 5173,19090,19091,19092,19093,19094 |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

停止基础设施但保留数据：

```powershell
docker compose --env-file .env -f deploy\docker-compose.yml down
```

不要添加 `-v`，否则会删除本地数据库卷。

## 最终验收记录

```text
[ ] Docker MySQL/Redis healthy
[ ] InfrastructureGateTest 4/4，Skipped 0
[ ] Maven clean verify 成功
[ ] 九接口目录与跨 API Flow 3/3
[ ] Control/Runtime/Fake Real 健康
[ ] JDK 8 与 JDK 17 的 MOCK/REAL/CANARY 路由
[ ] Mock 请求无真实 Token/Cookie/Signature
[ ] Web type-check/lint/build 和 14 个页面
[ ] OpenAPI/JSON Schema 正常导入和恶意样本拒绝
[ ] Dashboard 读取分钟预聚合，Request Log 日分区维护成功
[ ] 正式性能三档结果已保存
[ ] 节点/Redis/MySQL/发布/Callback 故障演练已记录
[ ] 外部 KMS/SSO/Service Identity/Apollo/Nacos 适配已确认
```
