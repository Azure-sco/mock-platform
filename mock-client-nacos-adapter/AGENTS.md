# Nacos adapter module

- Java 17. It adapts configuration input only and must not redefine routing rules.
- M0 provides an explicit local bridge; do not claim production Nacos integration.
- Verify with `mvn -pl mock-client-nacos-adapter -am test`.
