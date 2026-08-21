# Boot 2 starter

- Java 8, Spring Boot 2, and `javax` ecosystem only; never import `jakarta.*`.
- Transport adapters delegate policy decisions to `mock-client-core`.
- Verify with `mvn -pl mock-client-boot2-starter -am test` using the JDK 8 toolchain.
