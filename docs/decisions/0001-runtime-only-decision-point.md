# ADR-0001：Runtime 是唯一 Mock 决策点

状态：Accepted

## 决策

SDK 只决定传输目标并携带可信 Context；Control 只管理配置；Scenario、Flow、Response 和 Fault 的业务决策只能在 Runtime 执行。Runtime 不代理真实第三方。

## 影响

M0 Runtime 仅以可替换的固定 Dispatcher 验证边界。M1 才能在 Runtime 增加 Contract/Scenario；不得把匹配逻辑复制到 SDK 或 Control。
