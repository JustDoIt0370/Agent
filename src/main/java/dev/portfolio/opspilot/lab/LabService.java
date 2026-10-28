package dev.portfolio.opspilot.lab;

import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A bounded, loopback-only checkout workload. Observations come from real JDBC/HTTP calls. */
@Service
public class LabService {
    public enum Scenario { HEALTHY, SLOW_SQL, POOL_EXHAUSTION, DOWNSTREAM_TIMEOUT }
    public record Span(String name, long durationMs, String outcome) {}
    public record Trace(String id, Instant startedAt, long durationMs, String outcome, List<Span> spans) {}
    public record LogEntry(Instant timestamp, String level, String traceId, String message) {}
    public record Change(Instant timestamp, String field, String before, String after) {}
    public record Metrics(String windowId, Instant collectedAt, int samples, int errors, double errorRate,
                          long p95Ms, double meanSqlMs, double meanPoolWaitMs, double meanDownstreamMs,
                          int poolActive, int poolIdle, int poolPending, int poolCapacity, List<String> traceIds) {}
    public record State(String windowId, Scenario scenario, boolean faultActive, boolean ready,
                        String service, Instant startedAt, Metrics metrics) {}
    private record Observation(String windowId, Trace trace) {}

    private final HikariDataSource pool;
    private final AtomicInteger workerSequence = new AtomicInteger();
    private final ScheduledExecutorService workers = Executors.newScheduledThreadPool(6, task -> new Thread(task, "checkout-worker-" + workerSequence.incrementAndGet()));
    private final ExecutorService partnerWorkers = new ThreadPoolExecutor(8, 8, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    private final HttpServer partner;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
    private final URI partnerUri;
    private final ArrayDeque<Observation> observations = new ArrayDeque<>();
    private final List<Connection> heldConnections = new ArrayList<>();
    private final List<Change> changes = new ArrayList<>();
    private final AtomicInteger orderSequence = new AtomicInteger();
    private volatile String windowId = UUID.randomUUID().toString();
    private volatile Instant startedAt = Instant.now();
    private volatile Scenario scenario = Scenario.HEALTHY;
    private volatile int sqlDelayMs;
    private volatile int partnerDelayMs;
    private volatile boolean ready;

    public LabService() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:mem:checkout_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        config.setUsername("sa"); config.setPassword("");
        config.setMaximumPoolSize(4); config.setMinimumIdle(4);
        config.setConnectionTimeout(600); config.setPoolName("checkout-lab");
        pool = new HikariDataSource(config);
        try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id INT PRIMARY KEY, amount DECIMAL(12,2))");
            statement.execute("INSERT INTO orders SELECT X, 129.00 FROM SYSTEM_RANGE(1, 32)");
            statement.execute("CREATE ALIAS LAB_DELAY FOR 'dev.portfolio.opspilot.lab.LabDelay.pause'");
        }
        partner = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        partner.setExecutor(partnerWorkers);
        partner.createContext("/inventory", exchange -> {
            try {
                Thread.sleep(partnerDelayMs > 0 ? partnerDelayMs : 12);
                byte[] body = "{\"available\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException ignored) { /* Client timeout intentionally closes the socket. */ }
            finally { exchange.close(); }
        });
        partner.start();
        partnerUri = URI.create("http://127.0.0.1:" + partner.getAddress().getPort() + "/inventory");
        ready = true;
        for (int i = 0; i < 6; i++) workers.scheduleWithFixedDelay(this::request, i * 30L, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized State activate(Scenario selected) {
        ready = false;
        releaseHolders();
        sqlDelayMs = 0; partnerDelayMs = 0;
        String nextWindow = UUID.randomUUID().toString();
        try {
            if (selected == Scenario.SLOW_SQL) sqlDelayMs = 180;
            if (selected == Scenario.DOWNSTREAM_TIMEOUT) partnerDelayMs = 900;
            if (selected == Scenario.POOL_EXHAUSTION) {
                for (int i = 0; i < 4; i++) heldConnections.add(pool.getConnection());
            }
            changes.clear();
            changes.add(new Change(Instant.now(), "workload.concurrency", "6", "6"));
            if (sqlDelayMs > 0) changes.add(new Change(Instant.now(), "checkout.sqlDelayMs", "0", "180"));
            if (partnerDelayMs > 0) changes.add(new Change(Instant.now(), "inventory.latencyMs", "12", "900"));
            if (!heldConnections.isEmpty()) changes.add(new Change(Instant.now(), "checkout.reservedConnections", "0", "4"));
            synchronized (observations) { observations.clear(); }
            scenario = selected; startedAt = Instant.now(); windowId = nextWindow;
        } catch (SQLException e) {
            releaseHolders(); sqlDelayMs = 0; partnerDelayMs = 0;
            scenario = Scenario.HEALTHY; windowId = nextWindow; startedAt = Instant.now();
            throw new IllegalStateException("故障窗口初始化失败，请重试", e);
        } finally { ready = true; }
        return state();
    }

    /** The only mutation surface exposed to the approval workflow. No shell/SQL supplied by a model. */
    public synchronized Instant repair(String expectedWindow, String action) {
        requireWindow(expectedWindow);
        switch (action) {
            case "RESET_SQL_DELAY" -> { changes.add(new Change(Instant.now(), "checkout.sqlDelayMs", "" + sqlDelayMs, "0")); sqlDelayMs = 0; }
            case "RELEASE_POOL_HOLDERS" -> { changes.add(new Change(Instant.now(), "checkout.reservedConnections", "" + heldConnections.size(), "0")); releaseHolders(); }
            case "RESET_PARTNER_LATENCY" -> { changes.add(new Change(Instant.now(), "inventory.latencyMs", "" + partnerDelayMs, "12")); partnerDelayMs = 0; }
            default -> throw new IllegalArgumentException("修复动作不在白名单内");
        }
        return Instant.now();
    }

    public synchronized State state() {
        return new State(windowId, scenario, sqlDelayMs > 0 || partnerDelayMs > 0 || !heldConnections.isEmpty(),
                ready, "checkout-service", startedAt, metrics(windowId, startedAt));
    }
    public void requireWindow(String expected) {
        if (!ready || !windowId.equals(expected)) throw new IllegalStateException("故障窗口已变化，请重新发起诊断");
    }
    public Metrics metrics(String expected, Instant since) {
        requireWindow(expected);
        var traces = traces(expected, since);
        long[] durations = traces.stream().mapToLong(Trace::durationMs).sorted().toArray();
        int errors = (int) traces.stream().filter(t -> !t.outcome().equals("OK")).count();
        long p95 = durations.length == 0 ? 0 : durations[Math.max(0, (int) Math.ceil(durations.length * .95) - 1)];
        var bean = pool.getHikariPoolMXBean();
        return new Metrics(expected, Instant.now(), traces.size(), errors,
                traces.isEmpty() ? 0 : Math.round(errors * 1000.0 / traces.size()) / 1000.0,
                p95, mean(traces, "jdbc.query"), mean(traces, "pool.acquire"), mean(traces, "http.inventory"),
                bean.getActiveConnections(), bean.getIdleConnections(), bean.getThreadsAwaitingConnection(), 4,
                traces.stream().sorted(Comparator.comparingLong(Trace::durationMs).reversed()).limit(5).map(Trace::id).toList());
    }
    public List<LogEntry> logs(String expected, int limit) {
        return traces(expected, startedAt).reversed().stream().limit(Math.clamp(limit, 1, 20))
                .map(t -> new LogEntry(t.startedAt(), t.outcome().equals("OK") ? "INFO" : "ERROR", t.id(),
                        "POST /checkout " + t.outcome() + " duration=" + t.durationMs() + "ms"))
                .toList();
    }
    public Trace trace(String expected, String id) {
        return traces(expected, startedAt).stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("调用链不存在或已超出采样窗口"));
    }
    public synchronized List<Change> changes(String expected) { requireWindow(expected); return List.copyOf(changes); }
    public Metrics awaitSamples(String expected, Instant since, int minimum, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        Metrics result;
        do {
            result = metrics(expected, since);
            if (result.samples() >= minimum) return result;
            Thread.sleep(80);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("有效采样不足，请稍后重试诊断");
    }
    private List<Trace> traces(String expected, Instant since) {
        requireWindow(expected);
        synchronized (observations) {
            return observations.stream().filter(o -> o.windowId().equals(expected) && !o.trace().startedAt().isBefore(since))
                    .map(Observation::trace).toList();
        }
    }
    private static double mean(List<Trace> traces, String span) {
        return Math.round(traces.stream().flatMap(t -> t.spans().stream()).filter(s -> s.name().equals(span))
                .mapToLong(Span::durationMs).average().orElse(0) * 10.0) / 10.0;
    }
    private void request() {
        if (!ready || Thread.currentThread().isInterrupted()) return;
        String window = windowId;
        Instant started = Instant.now(); long start = System.nanoTime(), stage = start;
        List<Span> spans = new ArrayList<>(); String outcome = "OK";
        boolean acquired = false;
        try {
            try (Connection connection = pool.getConnection()) {
                acquired = true; spans.add(new Span("pool.acquire", elapsed(stage), "OK")); stage = System.nanoTime();
                int delay = sqlDelayMs;
                if (delay > 0) {
                    try (var statement = connection.prepareStatement("CALL LAB_DELAY(?)")) { statement.setInt(1, delay); statement.execute(); }
                }
                try (var statement = connection.prepareStatement("SELECT amount FROM orders WHERE id = ?")) {
                    statement.setInt(1, Math.floorMod(orderSequence.getAndIncrement(), 32) + 1);
                    try (var rows = statement.executeQuery()) { if (!rows.next()) throw new SQLException("fixture missing"); }
                }
                spans.add(new Span("jdbc.query", elapsed(stage), "OK"));
            }
            stage = System.nanoTime();
            var response = client.send(HttpRequest.newBuilder(partnerUri).timeout(Duration.ofMillis(300)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) throw new java.io.IOException("partner status");
            spans.add(new Span("http.inventory", elapsed(stage), "OK"));
        } catch (SQLException e) {
            outcome = acquired ? "SQL_ERROR" : "POOL_ACQUIRE_TIMEOUT";
            spans.add(new Span(acquired ? "jdbc.query" : "pool.acquire", elapsed(stage), outcome));
        } catch (HttpTimeoutException e) {
            outcome = "DOWNSTREAM_TIMEOUT"; spans.add(new Span("http.inventory", elapsed(stage), outcome));
        } catch (java.io.IOException e) {
            outcome = "DOWNSTREAM_ERROR"; spans.add(new Span("http.inventory", elapsed(stage), outcome));
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        catch (RuntimeException e) { outcome = "INTERNAL_ERROR"; }
        var trace = new Trace(UUID.randomUUID().toString().substring(0, 12), started, elapsed(start), outcome, List.copyOf(spans));
        synchronized (observations) {
            if (!window.equals(windowId)) return;
            observations.addLast(new Observation(window, trace));
            while (observations.size() > 240) observations.removeFirst();
        }
    }
    private static long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private void releaseHolders() {
        for (Connection connection : heldConnections) try { connection.close(); } catch (SQLException ignored) {}
        heldConnections.clear();
    }
    @PreDestroy public synchronized void close() {
        ready = false; workers.shutdownNow(); releaseHolders(); partner.stop(0); partnerWorkers.shutdownNow(); client.close(); pool.close();
    }
}
