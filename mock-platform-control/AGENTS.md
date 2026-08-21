# Control plane

- Java 17, Spring MVC. It never participates in per-request scenario decisions.
- Phase 0 scope is startup, health, DB/Redis/Flyway, response/error, identity and permission ports.
- Verify with `mvn -pl mock-platform-control -am test`.
