# ADR-0001: M2 Release activation authority and projection

- Status: Accepted
- Date: 2026-08-31
- Scope: `mock-platform-control` M2 Scenario/Approval/Release

## Decision

MySQL is the only recoverable authority for Release, Active Release, Activation, captured Targets and Outbox. Redis holds only two rebuildable projections: immutable signed Snapshot bytes at `mock:release-snapshot:{releaseId}` and the monotonic pointer at `mock:active-release:{environment}:{app}`.

The signed object is the recursively key-sorted, compact UTF-8 JSON of the inner Runtime Snapshot. Its schema version is the string `"1"`; checksum is SHA-256; signature is `SHA256withRSA`. The outer envelope and pointer use the field `signatureKeyId`. A Release stores the exact outer envelope bytes in MySQL before Redis prewrite/read-back verification can move it from `PREPARING` to `READY`.

Activation transactions lock the `(environment, app)` Active Release row first, compare `expectedActivationVersion`, then create the immutable Activation, captured Targets and fenced Outbox in that order and in the same transaction as Audit. Redis projection happens after commit and is version-monotonic. ACK, timeout, LEFT and WAIVE transitions use the same Active Release -> Activation -> Targets/Outbox lock order. A rollback creates a higher activation version that points at an existing verified Release and never rewrites that Release Snapshot or its items.

## External adapters and failure mode

Production signing KMS, Runtime discovery, Flow compatibility, Release-class security-policy binding, service identity and traffic governance are explicit ports. Until corporate adapters are configured, production implementations fail closed. Local/test adapters exist only for deterministic tests and local development; they are not production fallbacks.

## Consequences

Redis loss is recovered only from MySQL Release bytes, Active Release and Outbox. `PARTIAL` blocks subsequent publish; completion requires every required Target `READY` and every non-required Target `LEFT` or `WAIVED`. An old Outbox worker cannot finalize after its lease fencing token changes, and a stale projection cannot lower the Redis activation version.
