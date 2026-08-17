package de.htwsaar.zeitserver.monitor;

import de.htwsaar.zeitserver.config.AppConfig;
import de.htwsaar.zeitserver.fusion.FailoverResolver;
import de.htwsaar.zeitserver.fusion.TimeAnalysis;
import de.htwsaar.zeitserver.fusion.TimeFusion;
import de.htwsaar.zeitserver.fusion.TimeFusion.SourceReading;
import de.htwsaar.zeitserver.fusion.TimeHistoryStore;
import de.htwsaar.zeitserver.fusion.ZoneDisplay;
import de.htwsaar.zeitserver.source.NtpTimeSource;
import de.htwsaar.zeitserver.source.TimeSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sammelt alle Quellen, aktualisiert NTP, druckt Tabelle und schreibt optional JSON.
 */
public final class TimeMonitor {

    private final AppConfig cfg;
    private final List<TimeSource> sources;
    private final TimeHistoryStore history;

    public TimeMonitor(AppConfig cfg, List<TimeSource> sources) {
        this.cfg = cfg;
        this.sources = List.copyOf(sources);
        this.history = new TimeHistoryStore(Duration.ofMinutes(cfg.historyRetentionMinutes()));
    }

    public TimeHistoryStore history() {
        return history;
    }

    public void runOnce() {
        refreshNtp();
        Snapshot snap = captureSnapshot();
        printSnapshot(snap);
        writeJsonFile(snap, analyze(snap));
    }

    public void runLoop() throws InterruptedException {
        while (!Thread.currentThread().isInterrupted()) {
            if (cfg.monitorClearScreen()) {
                System.out.print("\033[2J\033[H");
            }
            refreshNtp();
            Snapshot snap = captureSnapshot();
            printSnapshot(snap);
            writeJsonFile(snap, analyze(snap));
            Thread.sleep(cfg.monitorIntervalMs());
        }
    }

    public Snapshot captureSnapshot() {
        refreshNtp();
        return capture();
    }

    public void writeJsonFile(Snapshot snap, TimeAnalysis.Result analysis) {
        String path = cfg.statusJsonPath();
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            Files.writeString(Path.of(path), StatusJson.serialize(snap, analysis, history), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("JSON schreiben fehlgeschlagen: " + e.getMessage());
        }
    }

    private TimeAnalysis.Result analyze(Snapshot snap) {
        return TimeAnalysis.analyze(
                snap.capturedAt(),
                snap.readings(),
                snap.failover().fusedUtc().or(() -> snap.fusedUtc()),
                cfg.accuracyOkSeconds(),
                cfg.fusionOutlierSeconds());
    }

    private TimeAnalysis.Result analyzeBeforeSnapshot(
            List<SourceReading> readings,
            Optional<Instant> fused,
            FailoverResolver.Result failover) {
        return TimeAnalysis.analyze(
                Instant.now(),
                readings,
                failover.fusedUtc().or(() -> fused),
                cfg.accuracyOkSeconds(),
                cfg.fusionOutlierSeconds());
    }

    private void refreshNtp() {
        for (TimeSource s : sources) {
            if (s instanceof NtpTimeSource ntp) {
                ntp.refresh();
            }
        }
    }

    private Snapshot capture() {
        List<SourceReading> readings = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (TimeSource s : sources) {
            Optional<Instant> utc = s.getUtc();
            readings.add(new SourceReading(s.id(), utc));
            String u = utc.map(Instant::toString).orElse("—");
            String det = s.getDetailStatus();
            details.add(det);
            lines.add(String.format("  %-6s  %-30s  %s", s.id(), u, det));
        }
        Optional<Instant> fused = TimeFusion.fusedUtc(readings, cfg.fusionOutlierSeconds());
        FailoverResolver.Result failover = cfg.failoverEnabled()
                ? FailoverResolver.resolve(
                        readings, history, cfg.fusionOutlierSeconds(), cfg.failoverMinSourcesNormal())
                : new FailoverResolver.Result(
                        "NORMAL", fused, Optional.empty(), fused, "Failover deaktiviert", List.of());
        ZoneDisplay.DstInfo dst = ZoneDisplay.now();
        Map<String, Integer> histCounts = new LinkedHashMap<>();
        for (TimeSource s : sources) {
            histCounts.put(s.id(), history.sampleCount(s.id()));
        }
        TimeAnalysis.Result analysis = analyzeBeforeSnapshot(readings, fused, failover);
        for (TimeAnalysis.SourceStatus st : analysis.sources()) {
            history.recordChart(st.id(), st.utc().isPresent(), st.offsetSeconds());
        }
        history.recordChart("FAILOVER", "FAILOVER".equals(failover.mode()), Optional.empty());

        List<String> deltas = TimeFusion.formatPairwiseDeltas(readings);
        return new Snapshot(
                Instant.now(),
                readings,
                fused,
                failover,
                dst,
                cfg.historyRetentionMinutes(),
                histCounts,
                lines,
                deltas,
                details);
    }

    private void printSnapshot(Snapshot snap) {
        TimeAnalysis.Result analysis = analyze(snap);
        System.out.println("=== Zeitserver-Monitor  (lokal: " + snap.capturedAt + ") ===");
        System.out.println("  " + analysis.referenceLabelDe());
        for (TimeAnalysis.SourceStatus st : analysis.sources()) {
            String u = st.utc().map(Instant::toString).orElse("—");
            String off = st.offsetSeconds()
                    .map(o -> String.format(" (Δ %+.3f s — %s)", o, st.labelDe()))
                    .orElse(" — " + st.labelDe());
            System.out.printf("  %-6s  %-30s%s%n", st.id(), u, off);
        }
        System.out.println("--- " + snap.dst.labelDe() + " (" + snap.dst.offsetLabel() + ") ---");
        if ("FAILOVER".equals(snap.failover.mode())) {
            System.out.println("--- Failover ---");
            System.out.println("  " + snap.failover.explanationDe());
            System.out.println("  Gewählt: " + snap.failover.selectedSource().orElse("—")
                    + " → " + snap.failover.selectedUtc().map(Instant::toString).orElse("—"));
        }
        System.out.println("--- Fusion (Median ohne Ausreißer > " + cfg.fusionOutlierSeconds() + " s) ---");
        Instant show = snap.failover.fusedUtc().or(() -> snap.fusedUtc()).orElse(null);
        System.out.println("  " + (show != null ? show : "— (zu wenige Quellen)"));
        if (!snap.deltas.isEmpty()) {
            System.out.println("--- Paarweise Differenzen (s) ---");
            for (String d : snap.deltas) {
                System.out.println("  " + d);
            }
        }
        System.out.println();
    }

    public record Snapshot(
            Instant capturedAt,
            List<SourceReading> readings,
            Optional<Instant> fusedUtc,
            FailoverResolver.Result failover,
            ZoneDisplay.DstInfo dst,
            int historyRetentionMinutes,
            Map<String, Integer> historySampleCounts,
            List<String> tableLines,
            List<String> deltas,
            List<String> details) {

        public int historySampleCount(String sourceId) {
            return historySampleCounts.getOrDefault(sourceId, 0);
        }
    }
}
