# ADR-0003: Request Log daily partition and retention maintenance

- Status: Accepted
- Date: 2026-08-31
- Scope: `mock-platform-control` Request Log storage

## Decision

`mock_request_log` keeps `created_at TIMESTAMP(6)` as the event timestamp and adds the stored generated column `created_day = DATE(created_at)` solely as a MySQL 8 partition key. MySQL rejects `UNIX_TIMESTAMP(TIMESTAMP(6))` because the fractional result is not a valid integer partition function, and rejects `TO_DAYS(TIMESTAMP)` as timezone-dependent. `RANGE COLUMNS(created_day)` is supported by MySQL 8.0.36 and preserves microsecond event time.

The primary key is `(created_at, id, created_day)`. The generated partition column is functionally determined by `created_at`, so this does not create a second business identity. Request Log intentionally has no cross-partition unique request key: network retries may create several transport logs, while `mock_request_execution` remains the idempotency authority.

Flyway V6 creates daily partitions, a bounded legacy partition, and `p_future`. `RequestLogPartitionMaintenance` runs with a MySQL named lock so only one Control instance reorganizes `p_future` or drops expired partitions. It maintains 31 future UTC-day partitions and a seven-day online retention by default. Identifiers and boundaries are generated from validated `LocalDate` values; no request input reaches DDL. Cleanup uses `DROP PARTITION`, not a large row-by-row delete.

## Operational constraints

The Flyway/Control database principal requires the documented `ALTER` privilege for partition maintenance. Operations must alert on maintenance failure and keep `p_future`; ingestion remains available if future partition creation is temporarily delayed. Retention and future-window changes are bounded configuration values. The deployment timezone for database sessions must remain consistent because the generated date is derived from a `TIMESTAMP` value.

## Consequences

Request Log detail queries retain the original `created_at` semantics and indexes. Dashboard request metrics use the V7 minute aggregate and do not scan detail partitions; aggregate buckets follow the same online retention. Expired Request Logs are physically unrecoverable after their partition is dropped, which is the intended seven-day data-retention policy. Longer-lived audit, Flow, Execution and Callback evidence uses its own tables and retention policy.
