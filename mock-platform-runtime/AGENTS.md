# Runtime data plane

- Java 17, WebFlux/Reactor Netty, HTTP/1.1 for the M0 mock entry.
- Runtime is the only future scenario decision point. Never call real third parties.
- JDBC work must use the bounded JDBC scheduler.
- Verify with `mvn -pl mock-platform-runtime -am test`.
