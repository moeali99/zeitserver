package de.htwsaar.zeitserver.fusion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Median-Fusion und paarweise Abweichungen zwischen Zeitquellen. */
public final class TimeFusion {

    public record SourceReading(String name, Optional<Instant> utc) {}

    private TimeFusion() {}

    public static List<String> formatPairwiseDeltas(List<SourceReading> readings) {
        List<SourceReading> valid = readings.stream()
                .filter(r -> r.utc().isPresent())
                .toList();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < valid.size(); i++) {
            for (int j = i + 1; j < valid.size(); j++) {
                Instant a = valid.get(i).utc().orElseThrow();
                Instant b = valid.get(j).utc().orElseThrow();
                double sec = secondsBetween(a, b);
                lines.add(String.format("%s vs %s: %.6f s", valid.get(i).name(), valid.get(j).name(), sec));
            }
        }
        return lines;
    }

    /** Differenz in Sekunden: a − b. */
    public static double secondsBetween(Instant a, Instant b) {
        return Duration.between(b, a).toNanos() / 1_000_000_000.0;
    }

    public static Optional<Instant> medianUtc(List<Instant> times) {
        if (times.isEmpty()) {
            return Optional.empty();
        }
        List<Instant> sorted = new ArrayList<>(times);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return Optional.of(sorted.get(mid));
        }
        Instant t1 = sorted.get(mid - 1);
        Instant t2 = sorted.get(mid);
        long nanos = (t1.getEpochSecond() * 1_000_000_000L + t1.getNano()
                + t2.getEpochSecond() * 1_000_000_000L + t2.getNano()) / 2;
        return Optional.of(Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L));
    }

    /**
     * Median nach Entfernen von Ausreißern (Abstand zum ersten Median &gt; {@code outlierSeconds}).
     */
    public static Optional<Instant> fusedUtc(List<SourceReading> readings, double outlierSeconds) {
        List<Instant> times = readings.stream()
                .map(SourceReading::utc)
                .flatMap(Optional::stream)
                .toList();
        Optional<Instant> med0 = medianUtc(times);
        if (med0.isEmpty()) {
            return Optional.empty();
        }
        Instant m0 = med0.get();
        double thr = Math.abs(outlierSeconds);
        List<Instant> filtered = times.stream()
                .filter(t -> Math.abs(secondsBetween(t, m0)) <= thr)
                .toList();
        return medianUtc(filtered.isEmpty() ? times : filtered);
    }
}
