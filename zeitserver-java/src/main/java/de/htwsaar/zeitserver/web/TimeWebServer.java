package de.htwsaar.zeitserver.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import de.htwsaar.zeitserver.config.AppConfig;
import de.htwsaar.zeitserver.fusion.TimeAnalysis;
import de.htwsaar.zeitserver.metrics.PrometheusExporter;
import de.htwsaar.zeitserver.monitor.StatusJson;
import de.htwsaar.zeitserver.monitor.TimeMonitor;
import de.htwsaar.zeitserver.source.TimeSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lokaler Webserver: Dashboard mit NTP, GPS, DCF77, Differenzen und Genauigkeit.
 */
public final class TimeWebServer {

    private final AppConfig cfg;
    private final List<TimeSource> sources;
    private final AtomicReference<String> lastJson = new AtomicReference<>("{}");
    private final AtomicReference<String> lastMetrics = new AtomicReference<>("");
    private volatile TimeMonitor monitor;

    public TimeWebServer(AppConfig cfg, List<TimeSource> sources) {
        this.cfg = cfg;
        this.sources = sources;
    }

    public void start() throws IOException, InterruptedException {
        String host = cfg.webHost();
        int port = cfg.webPort();
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 0);
        http.createContext("/api/status", this::handleApi);
        if (cfg.metricsEnabled()) {
            http.createContext("/metrics", this::handleMetrics);
        }
        http.createContext("/", this::handleStatic);
        http.setExecutor(Executors.newCachedThreadPool());
        http.start();

        System.out.println("Zeitserver-Web: http://" + displayHost(host) + ":" + port + "/");
        System.out.println("API:          http://" + displayHost(host) + ":" + port + "/api/status");
        if (cfg.metricsEnabled()) {
            System.out.println("Metrics:      http://" + displayHost(host) + ":" + port + "/metrics");
        }

        monitor = new TimeMonitor(cfg, sources);
        Thread updater = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    TimeMonitor.Snapshot snap = monitor.captureSnapshot();
                    TimeAnalysis.Result analysis = TimeAnalysis.analyze(
                            snap.capturedAt(),
                            snap.readings(),
                            snap.failover().fusedUtc().or(() -> snap.fusedUtc()),
                            cfg.accuracyOkSeconds(),
                            cfg.fusionOutlierSeconds());
                    lastJson.set(StatusJson.serialize(snap, analysis, monitor.history()));
                    if (cfg.metricsEnabled()) {
                        lastMetrics.set(PrometheusExporter.format(snap, analysis));
                    }
                    monitor.writeJsonFile(snap, analysis);
                    Thread.sleep(cfg.monitorIntervalMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "web-monitor");
        updater.setDaemon(true);
        updater.start();

        Thread.currentThread().join();
    }

    private static String displayHost(String host) {
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            return "localhost";
        }
        return host;
    }

    private void handleMetrics(HttpExchange ex) throws IOException {
        byte[] body = lastMetrics.get().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void handleApi(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            send(ex, 204, "", "text/plain");
            return;
        }
        byte[] body = lastJson.get().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/index.html";
        }
        String resource = "/web" + path;
        try (InputStream in = TimeWebServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "Not found", "text/plain");
                return;
            }
            String contentType = contentType(path);
            byte[] body = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", contentType);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private static void send(HttpExchange ex, int code, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
