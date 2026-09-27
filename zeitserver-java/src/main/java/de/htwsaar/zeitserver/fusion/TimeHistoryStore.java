package de.htwsaar.zeitserver.fusion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ringpuffer pro Quelle für Failover (z. B. letzte 10 Minuten).
 */
public final class TimeHistoryStore {

    public record Sample(Instant recordedAt, Instant sourceUtc) {}

    /** Punkt für Web-Diagramme (Grafana-ähnlich im Dashboard). */
    public record ChartPoint(long epochMs, Double offsetSeconds, boolean up) {}

    private final long retentionNanos;
    private final Map<String, Deque<Sample>> bySource = new ConcurrentHashMap<>();
    private final Map<String, Deque<ChartPoint>> chartBySource = new ConcurrentHashMap<>();

    public TimeHistoryStore(Duration retention) {
        this.retentionNanos = retention.toNanos();
    }

    public void record(String sourceId, Optional<Instant> utc) {
        if (sourceId == null || utc.isEmpty()) {
            return;
        }
        Deque<Sample> q = bySource.computeIfAbsent(sourceId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Sample(Instant.now(), utc.get()));
            trim(q);
        }
    }

    public void recordAll(List<TimeFusion.SourceReading> readings) {
        for (TimeFusion.SourceReading r : readings) {
            record(r.name(), r.utc());
        }
    }

    public List<String> sourceIds() {
        return new ArrayList<>(bySource.keySet());
    }

    public int sampleCount(String sourceId) {
        Deque<Sample> q = bySource.get(sourceId);
        if (q == null) {
            return 0;
        }
        synchronized (q) {
            return q.size();
        }
    }

    /** Median der Historie, auf den aktuellen Zeitpunkt fortgeschrieben (siehe {@link #utcsInWindow}). */
    public Optional<Instant> medianUtc(String sourceId) {
        return TimeFusion.medianUtc(utcsInWindow(sourceId));
    }

    /**
     * Alle Messwerte der Quelle im Fenster, jeweils auf "jetzt" fortgeschrieben:
     * {@code sourceUtc + (jetzt - recordedAt)}. Ohne diese Korrektur laege der Median
     * einer 10-Minuten-Historie im Mittel Minuten in der Vergangenheit.
     */
    public List<Instant> utcsInWindow(String sourceId) {
        return utcsInWindow(sourceId, Instant.now());
    }

    List<Instant> utcsInWindow(String sourceId, Instant now) {
        Deque<Sample> q = bySource.get(sourceId);
        if (q == null) {
            return List.of();
        }
        synchronized (q) {
            return q.stream()
                    .map(smp -> smp.sourceUtc().plus(Duration.between(smp.recordedAt(), now)))
                    .toList();
        }
    }

    /** Fuer Tests: Messwert mit vorgegebenem Aufnahmezeitpunkt. */
    void record(String sourceId, Instant recordedAt, Instant utc) {
        Deque<Sample> q = bySource.computeIfAbsent(sourceId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new Sample(recordedAt, utc));
        }
    }

    public void recordChart(String sourceId, boolean up, Optional<Double> offsetSeconds) {
        if (sourceId == null) {
            return;
        }
        Deque<ChartPoint> q = chartBySource.computeIfAbsent(sourceId, k -> new ArrayDeque<>());
        Double off = offsetSeconds.orElse(null);
        synchronized (q) {
            q.addLast(new ChartPoint(Instant.now().toEpochMilli(), off, up));
            trimChart(q);
        }
    }

    public List<ChartPoint> chartSeries(String sourceId) {
        Deque<ChartPoint> q = chartBySource.get(sourceId);
        if (q == null) {
            return List.of();
        }
        synchronized (q) {
            return new ArrayList<>(q);
        }
    }

    public List<String> chartSourceIds() {
        return new ArrayList<>(chartBySource.keySet());
    }

    private void trim(Deque<Sample> q) {
        Instant cutoff = Instant.now().minusNanos(retentionNanos);
        while (!q.isEmpty() && q.peekFirst().recordedAt().isBefore(cutoff)) {
            q.removeFirst();
        }
    }

    private void trimChart(Deque<ChartPoint> q) {
        long cutoff = Instant.now().minusNanos(retentionNanos).toEpochMilli();
        while (!q.isEmpty() && q.peekFirst().epochMs() < cutoff) {
            q.removeFirst();
        }
    }
}
