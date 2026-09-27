package de.htwsaar.zeitserver.fusion;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Wenn eine oder zwei Quellen ausfallen: letzte 10 Minuten vergleichen und
 * die verbleibende Zeit wählen, die am nächsten am Konsens liegt (ohne starke Ausreißer).
 */
public final class FailoverResolver {

    public record Candidate(String sourceId, Instant medianUtc, double spreadSeconds, int samples) {}

    public record Result(
            String mode,
            Optional<Instant> selectedUtc,
            Optional<String> selectedSource,
            Optional<Instant> fusedUtc,
            String explanationDe,
            List<Candidate> candidates) {}

    private FailoverResolver() {}

    public static Result resolve(
            List<TimeFusion.SourceReading> current,
            TimeHistoryStore history,
            double outlierSeconds,
            int minSourcesForNormalFusion) {

        history.recordAll(current);

        List<TimeFusion.SourceReading> alive = current.stream()
                .filter(r -> r.utc().isPresent())
                .toList();

        Optional<Instant> normalFusion = TimeFusion.fusedUtc(current, outlierSeconds);

        if (alive.size() >= minSourcesForNormalFusion) {
            return new Result(
                    "NORMAL",
                    normalFusion,
                    Optional.empty(),
                    normalFusion,
                    "Alle Quellen aktiv — Median-Fusion (" + alive.size() + " Quellen).",
                    List.of());
        }

        List<Candidate> candidates = buildCandidates(history, outlierSeconds);
        if (candidates.isEmpty()) {
            return new Result(
                    "FAILOVER",
                    Optional.empty(),
                    Optional.empty(),
                    normalFusion,
                    "Failover: keine Historie in den letzten 10 Minuten.",
                    List.of());
        }

        Optional<Instant> consensus = TimeFusion.medianUtc(
                candidates.stream().map(Candidate::medianUtc).toList());
        if (consensus.isEmpty()) {
            return new Result(
                    "FAILOVER",
                    Optional.empty(),
                    Optional.empty(),
                    normalFusion,
                    "Failover: Konsens nicht berechenbar.",
                    candidates);
        }

        Instant ref = consensus.get();
        double thr = Math.abs(outlierSeconds);
        Candidate best = candidates.stream()
                .filter(c -> Math.abs(TimeFusion.secondsBetween(c.medianUtc(), ref)) <= thr)
                .min(Comparator.comparingDouble(Candidate::spreadSeconds)
                        .thenComparing(c -> Math.abs(TimeFusion.secondsBetween(c.medianUtc(), ref))))
                .orElseGet(() -> candidates.stream()
                        .min(Comparator.comparingDouble(c ->
                                Math.abs(TimeFusion.secondsBetween(c.medianUtc(), ref))))
                        .orElseThrow());

        String dead = current.stream()
                .filter(r -> r.utc().isEmpty())
                .map(TimeFusion.SourceReading::name)
                .reduce((a, b) -> a + ", " + b)
                .orElse("deaktivierte Quellen");

        String expl = String.format(
                LocaleDe.FAILOVER_TEMPLATE,
                dead,
                alive.size(),
                best.sourceId(),
                best.samples(),
                best.spreadSeconds());

        Optional<Instant> failoverFusion = fuseWithHistory(current, history, outlierSeconds, best.medianUtc());

        return new Result(
                "FAILOVER",
                Optional.of(best.medianUtc()),
                Optional.of(best.sourceId()),
                failoverFusion.isPresent() ? failoverFusion : Optional.of(best.medianUtc()),
                expl,
                candidates);
    }

    private static Optional<Instant> fuseWithHistory(
            List<TimeFusion.SourceReading> current,
            TimeHistoryStore history,
            double outlierSeconds,
            Instant failoverPick) {
        List<TimeFusion.SourceReading> mix = new ArrayList<>(current.stream()
                .filter(r -> r.utc().isPresent())
                .toList());
        mix.add(new TimeFusion.SourceReading("FAILOVER", Optional.of(failoverPick)));
        for (String id : history.sourceIds()) {
            history.medianUtc(id).ifPresent(m ->
                    mix.add(new TimeFusion.SourceReading(id + "_hist", Optional.of(m))));
        }
        return TimeFusion.fusedUtc(mix, outlierSeconds);
    }

    private static List<Candidate> buildCandidates(TimeHistoryStore history, double outlierSeconds) {
        List<Candidate> list = new ArrayList<>();
        for (String id : history.sourceIds()) {
            List<Instant> utcs = history.utcsInWindow(id);
            if (utcs.size() < 3) {
                continue;
            }
            Optional<Instant> med = TimeFusion.medianUtc(utcs);
            if (med.isEmpty()) {
                continue;
            }
            Instant m = med.get();
            List<Instant> filtered = utcs.stream()
                    .filter(t -> Math.abs(TimeFusion.secondsBetween(t, m)) <= outlierSeconds)
                    .toList();
            List<Instant> use = filtered.isEmpty() ? utcs : filtered;
            Optional<Instant> med2 = TimeFusion.medianUtc(use);
            if (med2.isEmpty()) {
                continue;
            }
            double spread = use.stream()
                    .mapToDouble(t -> Math.abs(TimeFusion.secondsBetween(t, med2.get())))
                    .max()
                    .orElse(0);
            list.add(new Candidate(id, med2.get(), spread, use.size()));
        }
        return list;
    }

    private static final class LocaleDe {
        static final String FAILOVER_TEMPLATE =
                "Ausfall: %s — nur %d Quelle(n) live. Aus 10-Min-Historie: beste Quelle %s "
                        + "(%d Messungen, Spread ≤ %.3f s), nächste zur Referenz ohne starke Abweichung.";
    }
}
