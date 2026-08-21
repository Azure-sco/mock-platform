# ADR-0002：可能已送达时禁止回退真实接口

状态：Accepted

## 决策

默认 `FAST_FAIL`。`FALLBACK_REAL` 只有在非生产、可重放、精确 Host 白名单且异常明确发生在连接前时可用。Read timeout、connection reset、部分响应和未知异常一律视为可能已送达。

## 影响

安全性优先于可用性；Runtime 不稳定可能导致业务调用失败，但不会因不确定重放而对真实第三方产生重复副作用。四客户端 reset PoC 和 Fake Real 计数是回归门禁。
