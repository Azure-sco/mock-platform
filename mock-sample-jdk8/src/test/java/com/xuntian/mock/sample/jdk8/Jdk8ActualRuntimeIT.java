package com.xuntian.mock.sample.jdk8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Jdk8ActualRuntimeIT {

    @Test
    void reachesActualReactorNettyRuntimeWithFeignAndRestTemplate() throws Exception {
        Path projectRoot = Paths.get(System.getProperty("mock.project.root"));
        int runtimePort = availablePort();
        Process runtime = startRuntime(projectRoot, runtimePort);
        AtomicInteger fakeRealCalls = new AtomicInteger();
        HttpServer fakeReal = fakeRealServer(fakeRealCalls);
        ConfigurableApplicationContext sample = null;
        try {
            awaitHealthy(runtime, runtimePort, projectRoot);
            sample = new SpringApplicationBuilder(Jdk8SampleApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(
                            "--spring.main.banner-mode=off",
                            "--oa.base-url=http://127.0.0.1:" + fakeReal.getAddress().getPort(),
                            "--xuntian.mock.client.app-code=sample-jdk8",
                            "--xuntian.mock.client.environment=TEST",
                            "--xuntian.mock.client.runtime-base-uri=http://127.0.0.1:" + runtimePort,
                            "--xuntian.mock.client.mock-app-token=local-token-8",
                            "--xuntian.mock.client.mode=MOCK",
                            "--xuntian.mock.client.unavailable-policy=FALLBACK_REAL",
                            "--xuntian.mock.client.allowed-real-hosts[0]=127.0.0.1",
                            "--xuntian.mock.client.allowed-business-headers[0]=X-Business-Tag");

            OaSettlementGateway restGateway = sample.getBean(OaSettlementGateway.class);
            OaNumberGateway feignGateway = sample.getBean(OaNumberGateway.class);
            String restResult = restGateway.createReview("SETTLE-ACTUAL-8");
            String feignResult = feignGateway.queryNumber("FLOW-ACTUAL-8");
            assertThat(restResult).contains("M1_FIXTURE").contains("\"code\":\"200\"");
            assertThat(feignResult).contains("M1_FIXTURE").contains("\"code\":\"200\"");

            assertThat(restGateway.resetProbe("rest-received")).isEqualTo("runtime-received");
            assertThat(feignGateway.resetProbe("feign-received")).isEqualTo("runtime-received");
            assertThat(fakeRealCalls).hasValue(0);
        } finally {
            if (sample != null) {
                sample.close();
            }
            fakeReal.stop(0);
            stop(runtime);
        }
    }

    private Process startRuntime(Path projectRoot, int port) throws Exception {
        Path java = jdk17Java(projectRoot.resolve(".mvn/toolchains.xml"));
        Path runtimeJar = projectRoot.resolve(
                "mock-platform-runtime/target/mock-platform-runtime-0.1.0-SNAPSHOT-exec.jar");
        assertThat(runtimeJar).exists();
        Path log = projectRoot.resolve("mock-sample-jdk8/target/jdk8-actual-runtime-it.log");
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        String unixDomainTmpDir = System.getProperty("jdk.net.unixdomain.tmpdir");
        if (unixDomainTmpDir != null && !unixDomainTmpDir.trim().isEmpty()) {
            command.add("-Djdk.net.unixdomain.tmpdir=" + unixDomainTmpDir);
        }
        command.add("-jar");
        command.add(runtimeJar.toString());
        command.addAll(Arrays.asList(
                "--spring.profiles.active=test",
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--spring.flyway.enabled=false",
                "--management.health.redis.enabled=false",
                "--xuntian.mock.runtime.environment=TEST",
                "--xuntian.mock.runtime.local-app-tokens[local-token-8]=sample-jdk8",
                "--spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration"));
        return new ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
    }

    private Path jdk17Java(Path toolchains) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document document = factory.newDocumentBuilder().parse(toolchains.toFile());
        NodeList entries = document.getElementsByTagName("toolchain");
        for (int index = 0; index < entries.getLength(); index++) {
            Element entry = (Element) entries.item(index);
            String version = entry.getElementsByTagName("version").item(0).getTextContent().trim();
            if ("17".equals(version)) {
                String home = entry.getElementsByTagName("jdkHome").item(0).getTextContent().trim();
                String executable = System.getProperty("os.name").toLowerCase().contains("win")
                        ? "java.exe"
                        : "java";
                return Paths.get(home, "bin", executable);
            }
        }
        throw new IllegalStateException("JDK 17 toolchain is required");
    }

    private HttpServer fakeRealServer(AtomicInteger calls) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                calls.incrementAndGet();
                byte[] response = "{\"source\":\"REAL_TARGET_REACHED\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            }
        });
        server.start();
        return server;
    }

    private void awaitHealthy(Process process, int port, Path projectRoot) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && process.isAlive()) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(
                        "http://127.0.0.1:" + port + "/actuator/health").openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(500);
                if (connection.getResponseCode() == 200) {
                    return;
                }
            } catch (IOException ignored) {
                Thread.sleep(200);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        Path log = projectRoot.resolve("mock-sample-jdk8/target/jdk8-actual-runtime-it.log");
        String output = Files.exists(log)
                ? new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
                : "runtime log is missing";
        throw new IllegalStateException("Runtime did not become healthy: " + output);
    }

    private int availablePort() throws IOException {
        ServerSocket socket = new ServerSocket(0, 1, new InetSocketAddress("127.0.0.1", 0).getAddress());
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private void stop(Process process) throws InterruptedException {
        if (process == null) {
            return;
        }
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }
}
