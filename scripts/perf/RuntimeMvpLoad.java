import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * JDK 17-only fixed MVP Runtime load harness. It uses no third-party load-test dependency.
 * Flow setup is executed before each measured stage and is excluded from percentile samples.
 */
public final class RuntimeMvpLoad {
    private static final String APP = "sample-jdk17";
    private static final AtomicLong IDS = new AtomicLong();

    private final Config config;
    private final HttpClient client;

    private RuntimeMvpLoad(Config config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        RuntimeMvpLoad runner = new RuntimeMvpLoad(config);
        runner.requireReady();

        runner.runStage("warmup", config.warmupSeconds, config.steadyRps, config.connections, false, false);
        StageResult steady = runner.runStage(
                "steady", config.steadySeconds, config.steadyRps, config.connections, true, false);
        StageResult peak = runner.runStage(
                "peak", config.peakSeconds, config.peakRps, config.connections, true, false);
        StageResult large = config.includeLargePayload
                ? runner.runStage("large-payload", config.largeSeconds, config.largeRps,
                        config.largeConnections, true, true)
                : null;

        Path output = runner.writeReport(steady, peak, large);
        System.out.println("Result: " + output.toAbsolutePath());
        boolean passed = steady.p95Ms < 50 && steady.p99Ms < 100
                && peak.p95Ms < 100 && peak.unexpectedErrorRate < 0.001
                && (large == null || large.p95Ms < 200);
        if (!passed) {
            throw new IllegalStateException("One or more MVP performance thresholds were not met");
        }
    }

    private void requireReady() throws Exception {
        RequestPlan probe = RequestPlan.fixed("probe-" + IDS.incrementAndGet());
        Outcome outcome = send(probe.request(config), System.nanoTime());
        if (!outcome.expected) {
            throw new IllegalStateException(
                    "Runtime is not ready with the published MVP catalog: " + outcome.detail);
        }
    }

    private StageResult runStage(
            String name,
            int seconds,
            int rps,
            int connections,
            boolean record,
            boolean largePayload) throws Exception {
        int total = Math.multiplyExact(seconds, rps);
        List<RequestPlan> plans = new ArrayList<>(total);
        String stageId = name.replace('-', '_') + "_" + Instant.now().toEpochMilli();
        for (int index = 0; index < total; index++) {
            plans.add(largePayload
                    ? RequestPlan.large(stageId + "_" + index)
                    : RequestPlan.mixed(stageId, index));
        }
        prepareFlows(plans, connections);

        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        AtomicLong unexpected = new AtomicLong();
        CountDownLatch completed = new CountDownLatch(total);
        ExecutorService workers = Executors.newFixedThreadPool(connections);
        long intervalNanos = 1_000_000_000L / rps;
        long started = System.nanoTime() + 1_000_000_000L;
        System.out.printf(Locale.ROOT,
                "%s: requests=%d rps=%d connections=%d%n", name, total, rps, connections);
        try {
            for (int index = 0; index < total; index++) {
                long scheduled = started + intervalNanos * index;
                waitUntil(scheduled);
                RequestPlan plan = plans.get(index);
                workers.execute(() -> {
                    try {
                        Outcome outcome = send(plan.request(config), scheduled);
                        if (record) latencies.add(outcome.latencyNanos);
                        if (!outcome.expected) {
                            unexpected.incrementAndGet();
                            if (failures.size() < 20) failures.add(outcome.detail);
                        }
                    } catch (Exception failure) {
                        unexpected.incrementAndGet();
                        if (failures.size() < 20) failures.add(failure.getClass().getSimpleName());
                        if (record) latencies.add(System.nanoTime() - scheduled);
                    } finally {
                        completed.countDown();
                    }
                });
            }
            if (!completed.await(Math.max(30L, seconds + 30L), TimeUnit.SECONDS)) {
                throw new IllegalStateException(name + " did not drain within the bounded timeout");
            }
        } finally {
            workers.shutdownNow();
        }
        StageResult result = StageResult.of(name, total, unexpected.get(), latencies, failures);
        if (record) System.out.println(result.summary());
        return result;
    }

    private void prepareFlows(List<RequestPlan> plans, int connections) throws Exception {
        List<RequestPlan> flows = plans.stream().filter(RequestPlan::needsPreparation).toList();
        if (flows.isEmpty()) return;
        System.out.printf("Preparing %d persisted Flow instances outside the timed stage%n", flows.size());
        ExecutorService workers = Executors.newFixedThreadPool(connections);
        CountDownLatch completed = new CountDownLatch(flows.size());
        ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();
        try {
            for (RequestPlan plan : flows) {
                workers.execute(() -> {
                    try {
                        for (HttpRequest request : plan.preparation(config)) {
                            Outcome outcome = send(request, System.nanoTime());
                            if (!outcome.expected) failures.add(outcome.detail);
                        }
                    } catch (Exception failure) {
                        failures.add(failure.getClass().getSimpleName() + ":" + failure.getMessage());
                    } finally {
                        completed.countDown();
                    }
                });
            }
            if (!completed.await(Math.max(60L, flows.size() / 20L), TimeUnit.SECONDS)) {
                throw new IllegalStateException("Flow preparation timed out");
            }
        } finally {
            workers.shutdownNow();
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("Flow preparation failed: " + failures.peek());
        }
    }

    private Outcome send(HttpRequest request, long scheduledNanos)
            throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long latency = Math.max(0, System.nanoTime() - scheduledNanos);
        boolean noMatch = request.headers().firstValue("X-Expected-No-Match").isPresent();
        boolean expected = noMatch
                ? response.body().contains("MOCK_NO_MATCH")
                : response.statusCode() >= 200 && response.statusCode() < 300
                        && response.body().contains("MVP_DEMO");
        return new Outcome(expected, latency,
                response.statusCode() + ":" + abbreviate(response.body()));
    }

    private Path writeReport(StageResult steady, StageResult peak, StageResult large) throws IOException {
        Files.createDirectories(config.outputDirectory);
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneId.systemDefault()).format(Instant.now());
        Path output = config.outputDirectory.resolve("runtime-mvp-" + timestamp + ".json");
        StringBuilder json = new StringBuilder();
        json.append("{\n  \"generatedAt\":\"").append(Instant.now()).append("\",")
                .append("\n  \"baseUrl\":\"").append(config.baseUrl).append("\",")
                .append("\n  \"trafficModel\":\"50% fixed, 20% template, 15% QUERY_COUNT Flow, ")
                .append("5% Flow transition plus Callback Task, 10% expected MOCK_NO_MATCH\",")
                .append("\n  \"steady\":").append(steady.json()).append(',')
                .append("\n  \"peak\":").append(peak.json());
        if (large != null) json.append(',').append("\n  \"largePayload\":").append(large.json());
        json.append("\n}\n");
        Files.writeString(output, json.toString(), StandardCharsets.UTF_8);
        return output;
    }

    private static void waitUntil(long targetNanos) {
        long remaining;
        while ((remaining = targetNanos - System.nanoTime()) > 0) {
            LockSupport.parkNanos(Math.min(remaining, 2_000_000L));
        }
    }

    private static String abbreviate(String value) {
        String oneLine = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160);
    }

    private record Outcome(boolean expected, long latencyNanos, String detail) { }

    private enum Kind { FIXED, TEMPLATE, QUERY_FLOW, CALLBACK_FLOW, NO_MATCH, LARGE }

    private record RequestPlan(Kind kind, String id, String businessNo) {
        static RequestPlan mixed(String stage, int index) {
            int bucket = index % 100;
            String id = stage + "_" + index;
            if (bucket < 50) return fixed(id);
            if (bucket < 70) return new RequestPlan(Kind.TEMPLATE, id, null);
            if (bucket < 85) return new RequestPlan(Kind.QUERY_FLOW, id, "PERF-Q-" + id);
            if (bucket < 90) return new RequestPlan(Kind.CALLBACK_FLOW, id, "PERF-C-" + id);
            return new RequestPlan(Kind.NO_MATCH, id, null);
        }

        static RequestPlan fixed(String id) { return new RequestPlan(Kind.FIXED, id, null); }
        static RequestPlan large(String id) { return new RequestPlan(Kind.LARGE, id, null); }
        boolean needsPreparation() { return kind == Kind.QUERY_FLOW || kind == Kind.CALLBACK_FLOW; }

        List<HttpRequest> preparation(Config config) {
            if (!needsPreparation()) return List.of();
            List<HttpRequest> requests = new ArrayList<>();
            requests.add(oaCreate(config, id + "_prep_create", businessNo));
            if (kind == Kind.CALLBACK_FLOW) {
                requests.add(oaQuery(config, id + "_prep_query", businessNo));
            }
            return requests;
        }

        HttpRequest request(Config config) {
            return switch (kind) {
                case FIXED -> json(config, "/b2b/org-auth-check", "CPS_EQB", "CPS_ORG_AUTH_CHECK",
                        "eqb-auth-ok", id, "{}");
                case TEMPLATE -> json(config, "/sign/create-and-start", "CPS_EQB",
                        "CPS_SIGN_CREATE_START", "eqb-create-success", id,
                        "{\"settleId\":" + Math.abs(id.hashCode()) + "}");
                case QUERY_FLOW -> oaQuery(config, id, businessNo);
                case CALLBACK_FLOW -> oaCommand(config, id, businessNo);
                case NO_MATCH -> json(config, "/b2b/org-auth-check", "CPS_EQB", "CPS_ORG_AUTH_CHECK",
                        "scenario-does-not-exist", id, "{}", true);
                case LARGE -> json(config, "/b2b/org-auth-check", "CPS_EQB", "CPS_ORG_AUTH_CHECK",
                        "eqb-auth-ok", id, "{\"payload\":\"" + "x".repeat(1_047_000) + "\"}");
            };
        }

        private static HttpRequest oaCreate(Config c, String id, String businessNo) {
            return base(c, "/api/km-review/kmReviewRestService/addReviewNew", "OA", "OA_SETTLE_CREATE",
                    "oa-create-success", id)
                    .header("X-Mock-Business-No", businessNo)
                    .header("Content-Type", "multipart/form-data; boundary=mvp")
                    .POST(HttpRequest.BodyPublishers.ofString("--mvp--\r\n"))
                    .build();
        }

        private static HttpRequest oaQuery(Config c, String id, String businessNo) {
            String query = URLEncoder.encode(businessNo, StandardCharsets.UTF_8);
            return base(c, "/api/tcl-cpms/cpmsAuditRestService/getAuditInfosNew?fdIdList=" + query,
                    "OA", "OA_NUMBER_QUERY", "oa-number-processing", id)
                    .GET().build();
        }

        private static HttpRequest oaCommand(Config c, String id, String businessNo) {
            return base(c, "/api/km-review/kmReviewRestService/approveProcessNew", "OA",
                    "OA_PROCESS_COMMAND", "oa-command-approve", id)
                    .header("X-Mock-Business-No", businessNo)
                    .header("X-Business-Action", "approve")
                    .header("Content-Type", "multipart/form-data; boundary=mvp")
                    .POST(HttpRequest.BodyPublishers.ofString("--mvp--\r\n"))
                    .build();
        }

        private static HttpRequest json(
                Config c, String path, String provider, String api, String scenario,
                String id, String body) {
            return json(c, path, provider, api, scenario, id, body, false);
        }

        private static HttpRequest json(
                Config c, String path, String provider, String api, String scenario,
                String id, String body, boolean noMatch) {
            HttpRequest.Builder request = base(c, path, provider, api, scenario, id)
                    .header("Content-Type", "application/json");
            if (noMatch) request.header("X-Expected-No-Match", "true");
            return request.POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }

        private static HttpRequest.Builder base(
                Config c, String path, String provider, String api, String scenario, String id) {
            String requestId = "perf-" + Integer.toUnsignedString(id.hashCode(), 36)
                    + "-" + Long.toUnsignedString(IDS.incrementAndGet(), 36);
            return HttpRequest.newBuilder(URI.create(c.baseUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "MockApp " + c.token)
                    .header("X-Mock-App", APP)
                    .header("X-Mock-Provider", provider)
                    .header("X-Mock-Api", api)
                    .header("X-Mock-Request-Id", requestId)
                    .header("X-Mock-Explicit-Scenario", scenario);
        }
    }

    private record StageResult(
            String name, int requests, long unexpectedErrors, double unexpectedErrorRate,
            double p50Ms, double p95Ms, double p99Ms, double maxMs, List<String> samples) {
        static StageResult of(
                String name, int requests, long unexpected, ConcurrentLinkedQueue<Long> values,
                ConcurrentLinkedQueue<String> failures) {
            List<Long> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            return new StageResult(name, requests, unexpected,
                    requests == 0 ? 0 : (double) unexpected / requests,
                    percentile(sorted, 0.50), percentile(sorted, 0.95),
                    percentile(sorted, 0.99), percentile(sorted, 1.0), List.copyOf(failures));
        }

        String summary() {
            return String.format(Locale.ROOT,
                    "%s: p95=%.2fms p99=%.2fms errors=%d (%.4f%%)",
                    name, p95Ms, p99Ms, unexpectedErrors, unexpectedErrorRate * 100);
        }

        String json() {
            return String.format(Locale.ROOT,
                    "{\"requests\":%d,\"unexpectedErrors\":%d,\"unexpectedErrorRate\":%.8f,"
                            + "\"p50Ms\":%.3f,\"p95Ms\":%.3f,\"p99Ms\":%.3f,\"maxMs\":%.3f}",
                    requests, unexpectedErrors, unexpectedErrorRate, p50Ms, p95Ms, p99Ms, maxMs);
        }

        private static double percentile(List<Long> sorted, double percentile) {
            if (sorted.isEmpty()) return 0;
            int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
            return sorted.get(index) / 1_000_000.0;
        }
    }

    private record Config(
            String baseUrl, String token, Path outputDirectory, int connections,
            int warmupSeconds, int steadySeconds, int peakSeconds, int steadyRps, int peakRps,
            boolean includeLargePayload, int largeSeconds, int largeRps, int largeConnections) {
        static Config parse(String[] args) {
            Map<String, String> values = new java.util.HashMap<>();
            boolean quick = false;
            boolean skipLarge = false;
            for (int i = 0; i < args.length; i++) {
                if ("--quick".equals(args[i])) quick = true;
                else if ("--skip-large".equals(args[i])) skipLarge = true;
                else if (args[i].startsWith("--") && i + 1 < args.length) values.put(args[i], args[++i]);
                else throw new IllegalArgumentException("Unknown or incomplete argument: " + args[i]);
            }
            return new Config(
                    trimSlash(values.getOrDefault("--base-url", "http://127.0.0.1:19091")),
                    values.getOrDefault("--token", "local-token-17"),
                    Path.of(values.getOrDefault("--output", "build/perf-results")),
                    integer(values, "--connections", quick ? 20 : 100),
                    integer(values, "--warmup-seconds", quick ? 5 : 300),
                    integer(values, "--steady-seconds", quick ? 10 : 600),
                    integer(values, "--peak-seconds", quick ? 5 : 120),
                    integer(values, "--steady-rps", quick ? 20 : 200),
                    integer(values, "--peak-rps", quick ? 50 : 500),
                    !skipLarge,
                    integer(values, "--large-seconds", quick ? 5 : 300),
                    integer(values, "--large-rps", quick ? 5 : 20),
                    integer(values, "--large-connections", quick ? 5 : 20));
        }

        private static int integer(Map<String, String> values, String key, int fallback) {
            int value = Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
            if (value <= 0) throw new IllegalArgumentException(key + " must be positive");
            return value;
        }

        private static String trimSlash(String value) {
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }
}
