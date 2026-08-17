package de.htwsaar.zeitserver.fusion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Bewertet jede Quelle gegen eine Referenzzeit (Fusion-Median, sonst NTP, sonst erste Quelle).
 */
public final class TimeAnalysis {

    public enum Accuracy {
        MISSING,
        EXACT,
        GOOD,
        DRIFT,
        OUTLIER
    }

    public record PairDelta(String a, String b, double deltaSeconds) {}

    public record SourceStatus(
            String id,
            Optional<Instant> utc,
            String detail,
            Optional<Double> offsetSeconds,
            Accuracy accuracy,
            String labelDe) {}

    public record Result(
            Instant capturedAt,
            Optional<Instant> referenceUtc,
            String referenceLabelDe,
            List<SourceStatus> sources,
            List<PairDelta> pairwise,
            Optional<Instant> fusedUtc) {}

    private TimeAnalysis() {}

    public static Result analyze(
            Instant capturedAt,
            List<TimeFusion.SourceReading> readings,
            Optional<Instant> fusedUtc,
            double okSeconds,
            double outlierSeconds) {

        Optional<Instant> reference = pickReference(readings, fusedUtc);
        String refLabel = referenceLabel(reference, fusedUtc);

        List<SourceStatus> statuses = new ArrayList<>();
        for (TimeFusion.SourceReading r : readings) {
            statuses.add(assessSource(r, reference, okSeconds, outlierSeconds));
        }

        List<PairDelta> pairs = new ArrayList<>();
        List<TimeFusion.SourceReading> valid = readings.stream()
                .filter(r -> r.utc().isPresent())
                .toList();
        for (int i = 0; i < valid.size(); i++) {
            for (int j = i + 1; j < valid.size(); j++) {
                Instant a = valid.get(i).utc().orElseThrow();
                Instant b = valid.get(j).utc().orElseThrow();
                pairs.add(new PairDelta(
                        valid.get(i).name(),
                        valid.get(j).name(),
                        TimeFusion.secondsBetween(a, b)));
            }
        }

        return new Result(capturedAt, reference, refLabel, statuses, pairs, fusedUtc);
    }

    private static Optional<Instant> pickReference(
            List<TimeFusion.SourceReading> readings, Optional<Instant> fusedUtc) {
        if (fusedUtc.isPresent()) {
            return fusedUtc;
        }
        for (TimeFusion.SourceReading r : readings) {
            if ("NTP".equalsIgnoreCase(r.name()) && r.utc().isPresent()) {
                return r.utc();
            }
        }
        return readings.stream()
                .map(TimeFusion.SourceReading::utc)
                .flatMap(Optional::stream)
                .findFirst();
    }

    private static String referenceLabel(Optional<Instant> reference, Optional<Instant> fusedUtc) {
        if (fusedUtc.isPresent() && reference.equals(fusedUtc)) {
            return "Referenz: Median-Fusion (Ausreißer gefiltert)";
        }
        if (reference.isPresent()) {
            return "Referenz: NTP (keine Fusion möglich)";
        }
        return "Referenz: —";
    }

    private static SourceStatus assessSource(
            TimeFusion.SourceReading r,
            Optional<Instant> reference,
            double okSeconds,
            double outlierSeconds) {
        if (r.utc().isEmpty()) {
            return new SourceStatus(
                    r.name(), Optional.empty(), "", Optional.empty(),
                    Accuracy.MISSING, "Keine Zeit verfügbar");
        }
        if (reference.isEmpty()) {
            return new SourceStatus(
                    r.name(), r.utc(), "", Optional.empty(),
                    Accuracy.GOOD, "Zeit vorhanden, keine Referenz");
        }
        double off = TimeFusion.secondsBetween(r.utc().get(), reference.get());
        double abs = Math.abs(off);
        double ok = Math.abs(okSeconds);
        double out = Math.abs(outlierSeconds);

        if (abs <= ok) {
            return new SourceStatus(
                    r.name(), r.utc(), "", Optional.of(off),
                    Accuracy.EXACT, "Passt zur Referenz (≤ " + formatSec(ok) + " s)");
        }
        if (abs <= out * 0.5) {
            return new SourceStatus(
                    r.name(), r.utc(), "", Optional.of(off),
                    Accuracy.GOOD, "Geringe Abweichung");
        }
        if (abs <= out) {
            return new SourceStatus(
                    r.name(), r.utc(), "", Optional.of(off),
                    Accuracy.DRIFT, "Abweicht merklich von der Referenz");
        }
        return new SourceStatus(
                r.name(), r.utc(), "", Optional.of(off),
                Accuracy.OUTLIER, "Stark abweichend — vermutlich ungenau");
    }

    private static String formatSec(double s) {
        if (s < 0.001) {
            return String.format("%.0f ms", s * 1000);
        }
        return String.format("%.2f", s);
    }
}
