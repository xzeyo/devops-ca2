import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * hello-service — zero-dependency Java HTTP service for DevOps CA-II.
 *
 * Endpoints:
 *   GET /         -> JSON {service, version, hostname, time}
 *   GET /health   -> "OK"                       (readiness + liveness probes)
 *   GET /metrics  -> Prometheus text format     (requests, latency histogram, errors)
 *   anything else -> 404 (counted as an error for the error-rate panel)
 *
 * Task 4: metrics now include a latency histogram (p95) and per-status-code
 * counters so Prometheus/Grafana can chart uptime, latency and error rate.
 */
public class Main {

    private static final String SERVICE = "hello-service";
    private static final String VERSION = System.getenv().getOrDefault("APP_VERSION", "1.0.0");

    private static final Map<String, AtomicLong> CODE_COUNTS = new ConcurrentHashMap<>();

    private static final double[] BUCKETS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};
    private static final long[] BUCKET_COUNTS = new long[BUCKETS.length + 1]; // last = +Inf
    private static double durationSum = 0.0;
    private static final Object STAT_LOCK = new Object();

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", Main::root);
        server.createContext("/health", Main::health);
        server.createContext("/metrics", Main::metrics);

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println(SERVICE + " " + VERSION + " listening on port " + port);
    }

    private static void root(HttpExchange ex) throws IOException {
        long t0 = System.nanoTime();
        if (!ex.getRequestURI().getPath().equals("/")) {
            countCode("404");
            observe(t0);
            send(ex, 404, "application/json", "{\"error\":\"not found\"}");
            return;
        }
        countCode("200");
        observe(t0);
        String hostname = InetAddress.getLocalHost().getHostName();
        String body = "{\"service\":\"" + SERVICE + "\","
                + "\"version\":\"" + VERSION + "\","
                + "\"hostname\":\"" + hostname + "\","
                + "\"time\":\"" + Instant.now() + "\"}";
        send(ex, 200, "application/json", body);
    }

    private static void health(HttpExchange ex) throws IOException {
        send(ex, 200, "text/plain", "OK");
    }

    private static void metrics(HttpExchange ex) throws IOException {
        StringBuilder sb = new StringBuilder();

        sb.append("# HELP http_requests_total Total HTTP requests handled, by status code.\n");
        sb.append("# TYPE http_requests_total counter\n");
        for (Map.Entry<String, AtomicLong> e : new TreeMap<>(CODE_COUNTS).entrySet()) {
            sb.append("http_requests_total{code=\"").append(e.getKey()).append("\"} ")
              .append(e.getValue().get()).append("\n");
        }

        sb.append("# HELP http_request_duration_seconds Request latency in seconds.\n");
        sb.append("# TYPE http_request_duration_seconds histogram\n");
        synchronized (STAT_LOCK) {
            for (int i = 0; i < BUCKETS.length; i++) {
                sb.append("http_request_duration_seconds_bucket{le=\"").append(BUCKETS[i])
                  .append("\"} ").append(BUCKET_COUNTS[i]).append("\n");
            }
            sb.append("http_request_duration_seconds_bucket{le=\"+Inf\"} ")
              .append(BUCKET_COUNTS[BUCKETS.length]).append("\n");
            sb.append("http_request_duration_seconds_sum ").append(durationSum).append("\n");
            sb.append("http_request_duration_seconds_count ")
              .append(BUCKET_COUNTS[BUCKETS.length]).append("\n");
        }

        send(ex, 200, "text/plain; version=0.0.4", sb.toString());
    }

    private static void countCode(String code) {
        CODE_COUNTS.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
    }

    private static void observe(long startNanos) {
        double seconds = (System.nanoTime() - startNanos) / 1e9;
        synchronized (STAT_LOCK) {
            durationSum += seconds;
            for (int i = 0; i < BUCKETS.length; i++) {
                if (seconds <= BUCKETS[i]) {
                    BUCKET_COUNTS[i]++;
                }
            }
            BUCKET_COUNTS[BUCKETS.length]++;
        }
    }

    private static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}