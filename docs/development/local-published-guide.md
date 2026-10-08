# 本地页面配置与动态发布

`local,local-published` 使用真实 MySQL/Redis 和持久 RSA 签名，支持在页面维护无状态 Mock 响应并动态发布。原来的 `local` Fixture 模式仍可独立使用。这个入口仅用于本机 TEST，不替代生产 SSO/KMS/Admission/流量治理。

## 启动

先按 README 配好 `.env`、双 JDK Toolchains 并启动 Compose。PowerShell 7：

```powershell
$env:JAVA_HOME = '<JDK 17 目录>'
.\mvnw.cmd -t .mvn\toolchains.xml `
  '-Djdk.net.unixdomain.tmpdir=Z:\codex-selector-fallback' `
  '-Dmock.integration.loopback=true' '-Dmock.integration.netty-fault=true' verify
.\scripts\start-local-published.ps1
```

Windows 打包前应停止占用旧 JAR 的 Control/Runtime。`start-local-published.ps1 -Restart` 只会停止端口上命令行与当前工作区 JAR 路径匹配的进程，不能用它终止其他项目。

启动脚本读取现有 `.env`；在 `.codex-tmp/local-published` 生成一次 RSA 密钥，并设置仅当前用户可读写的 ACL。密钥不进入 Git、日志或普通配置文件。重启复用这些密钥，不能删除或重新生成，否则历史 Release 无法验证。Control/Runtime 都只监听 `127.0.0.1`。

其余服务沿用 README 启动方式：Web 5173、替身 19092、JDK 8 Sample 19093、JDK 17 Sample 19094。示例使用 `MOCK_MODE=MOCK`。

## 首次创建固定响应

### 推荐：接口 Mock 工作台

打开 `/mock/interfaces`（Web 默认首页）。固定响应现在在一个页面完成：

1. 输入已接入的应用编码，选择顶栏环境、服务商和接口。App/Token 仍需管理员事先配置。
2. 新接口点击「新建接口」，可以就地新增服务商。填写名称、方法、路径与 Content-Type；编码默认生成，已有 SDK 时在「接入编码」填写一致的 Provider/API 编码。填写请求、响应示例，点击「从示例生成格式」，确认 Schema 后点击「确认格式并保存接口」。系统依次保存、校验并发布契约；示例不会自动成为必填或固定值。
3. 点击「添加响应」，填写名称，通过条件表单设置请求体字段（如 `$.result`）、查询参数或请求头的等值规则。响应内容直接填写 JSON，不必手工转义。无条件表示默认响应；条件重叠仍由服务端拒绝或依据明确配置的优先级处理。
4. 点击「保存草稿」会保存并校验；点击「提交发布」会自动保存、校验并提交审批。校验失败会显示原因，不会继续审批。再次编辑会产生新版本，已发布内容不原地修改。
5. 另一位审批人在同页查看匹配条件和「查看内容」后点击「批准」。本地开发可以切换为本地审批人；不能自审，也不会自动切换身份。审批人不具有发布权限时，管理员切回本页发布。
6. 勾选要发布的已批准响应，点击「发布已批准响应」，确认变更名称、应用、环境及保留响应数量。系统自动生成完整 Release，保留当前已生效的其他响应，无需抄版本 ID 或填写 Release Code。后台状态自动刷新为「已生效」后调用接口。

成功和失败响应分别添加一条，例如同一字段 `$.result` 分别等于 `SUCCESS` 和 `FAIL`。后续更新重复编辑、提交、审批、发布，无需重启。默认使用当前已生效的契约；更换契约时同一接口的其他响应也必须兼容，发布校验不会忽略这一限制。

网络失败可重试已保存步骤。若提示期间已有其他发布，先刷新并重新核对；若显示发布未完整生效，到「发布与回滚」处理。刷新页面可重新读取已提交草稿、审批和 Active 状态，未保存表单不会保留。高级配置与治理菜单默认折叠，原页面及历史记录仍可访问。

### 高级页面逐项操作

1. 在 Web 顶栏选择“本地管理员”；创建 Provider 和 API，填写真实调用使用的编码、方法、路径、Content-Type。
2. 创建 Contract，填写请求与响应 JSON Schema，依次校验、发布。
3. 创建 Scenario，选择该 Provider/API，然后新建版本，选择已发布 Contract。无状态固定响应不选 Flow，Callbacks 填 `[]`。
4. Scope 可填 `{"environments":["TEST"],"apps":["sample-jdk17"],"tenants":[],"testAccounts":[]}`。Match Rules 填 `[]` 表示不加额外匹配条件（仍须通过身份、接口及 Contract 校验）。
5. Response 示例：

```json
{
  "httpStatus": 200,
  "headers": {"Content-Type": "application/json"},
  "bodyTemplate": "{\"code\":\"0\",\"message\":\"success\",\"data\":{\"status\":\"FIXED\"}}"
}
```

6. 保存场景版本，校验并提交审批。切换“本地审批人”，在审批中心批准；服务端仍禁止最后修改人自审。
7. 切回“本地管理员”，进入“发布与回滚”，使用环境 `TEST`、实际 App（示例为 `sample-jdk8` / `sample-jdk17`）创建 Release，填写批准后的场景版本 ID。每个 Release 是该 App 的完整场景集合；更新时保留仍需使用的其他场景版本。
8. 发布，刷新 Activation，确认 `APPLIED` 且目标节点 `READY`，再调用接口。常规传播为秒级；无需重启 Runtime。

后续修改：创建新场景版本 → 校验 → 换人审批 → 创建新 Release → 发布。已发布版本不能原地修改。回滚也走发布页面，会生成新的 activationVersion。

## 可执行验收与示例目录

```powershell
.\scripts\verify-local-published.ps1
```

此脚本会通过管理 API 创建/复用 OA、CPS_EQB 的四个示例 API、契约和场景，并留下可在页面查看的新版本和发布记录。不要对包含需要保留的同 App 活跃配置的环境直接运行：脚本会将两个示例 App 的活跃 Release 切换到演示场景集合。

验收包括拒绝自审、发布 V1、更新固定响应到 V2、回滚 V1、恢复 V2，检查真实 Sample 返回和目标 READY，并断言整个发布过程中 Runtime PID 不变。最终 OA 返回 `FIXED_V1`，CPS 签署返回 `FIXED_V2`，`source` 为 `PUBLISHED_LOCAL`。

## 边界与排查

- `PROJECTED` 仅表示发布指针已投影，必须等到 `APPLIED` / 节点 `READY` 才算生效。若节点为 `FAILED`，检查 Runtime ACK 的 `error_masked`；`COMPILE_FAILED` 表示快照无法编译。未配置的可选业务键提取器 `{}` 按不存在处理，Runtime 也兼容旧快照的全空字段对象；部分配置或 `required: true` 不会被忽略。升级修复后的服务后，当前发布会被自动重新加载，无需重建响应或修改已签名快照。
- 无有效发布时返回 Release 不可用，绝不回退内置 Fixture。Provider/API 页面读取管理数据库，请求记录中的目录现在可以对应到这些配置。
- 仅支持已配置 Token 和发布应用的本机 TEST 请求。新增 API/场景不需要重启；新增接入 App/Token 或修改可信密钥属于启动配置，仍需配置并重启。
- Flow/Callback 发布需要完整适配，本地模式会拒绝激活这类 Release，也拒绝覆盖已有 Flow 实例的 App。
- 没有对应 App 的 Runtime 心跳时不能发布。默认节点为 `runtime-local-1`；启动脚本为两个示例 App 开启轮询。
- 发布状态只根据匹配的持久化 Runtime ACK 收敛。此模式关闭旧测试签名 HTTP ACK 入口；缺失摘流能力时不会伪造成功。
- Redis 投影可从 MySQL 重建，签名不通过的快照不激活。数据库和签名文件应一起保留。
- Control/Runtime 日志分别位于各模块 `target/local-service.out.log` 和 `target/local-service.err.log`，不要提交。
