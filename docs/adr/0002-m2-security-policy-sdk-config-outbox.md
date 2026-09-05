# ADR-0002: M2 Security Policy and SDK Config projection fencing

- Status: Accepted
- Date: 2026-08-31
- Scope: `mock-platform-control` M2 Security Policy / SDK Config

## Decision

MySQL is the only recoverable authority for immutable Security Policy Versions, their desired/effective Bindings, SDK Config Envelopes, Active SDK Config, Activations, captured Targets, ACK evidence and Config Publish Outbox. Redis and Apollo/Nacos contain rebuildable projections only and never update MySQL authority in reverse.

Security Policy approval is bound to the immutable Version checksum. `PUBLISHED` describes Version lifecycle, `BOUND` means the desired Binding was committed/projected, and `EFFECTIVE` is consumer evidence: all current READY Runtime nodes for `APP_ACL`, a successfully applied SDK ConfigVersion for SDK policies, or a successfully activated Release for Release policies. These states are not collapsed.

An SDK Config publish preallocates ActivationId and publishedAt, recursively canonicalizes and signs the complete Envelope and Activation Wrapper once, then saves the Wrapper's exact protected canonical bytes and checksum in the same transaction as Active Config, Activation, Targets and Audit. Outbox replay decrypts and publishes those bytes verbatim; it never regenerates a timestamp, checksum or signature. Recovery of a deleted Apollo/Nacos key follows `mock_active_sdk_config.activation_id` to the retained published Outbox row.

Config Outbox claim uses a bounded 30-second lease, increments both attempt count and fencing token under a row lock, and retries at most eight times with bounded exponential backoff. External adapters must be idempotent by aggregate ID and complete inside the lease. Finalize requires the same `lease_owner + fencing_token`; a former worker cannot mutate Outbox or aggregate state after reclaim. The fixed finalize lock order is Active SDK Config -> SDK Activation -> Outbox, or Security Policy Binding -> Outbox. Identical external writes can occur after an uncertain timeout, but they always contain the same signed bytes.

`APP_ACL` Outbox performs the initial signed lease projection. The lease projector subsequently rereads the current MySQL desired Binding and signs a fresh lease every 20 seconds, with `notAfter - issuedAt <= 60 seconds`; it also recovers a current `PUBLISHING` Binding whose initial lease expired. Redis uses monotonic `(bindingVersion, issuedAt)` replacement. It cannot renew an obsolete desired version.

## External adapters and failure mode

Payload protection/signing, Apollo/Nacos publication, Redis admission publication and instance discovery/traffic governance are explicit ports. Local/test adapters are profile-scoped fixtures. Production adapters fail closed until corporate KMS, configuration-center and discovery implementations are configured. Internal SDK/Runtime event endpoints additionally require their own signed service identity even though browser SSO is bypassed for `/api/internal/v1/**`.

## Consequences

`PARTIAL` blocks ordinary SDK publish. Recovery may activate only a newly validated/approved, higher ConfigVersion whose contents exactly copy the last applied Envelope. A single ACK never establishes EFFECTIVE; all persisted required Targets must be `APPLIED`, while non-required Targets must be audited `LEFT` or `WAIVED`.
