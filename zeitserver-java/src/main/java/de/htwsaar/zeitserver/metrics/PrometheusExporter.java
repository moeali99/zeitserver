package de.htwsaar.zeitserver.metrics;

import de.htwsaar.zeitserver.fusion.FailoverResolver;
import de.htwsaar.zeitserver.fusion.TimeAnalysis;
import de.htwsaar.zeitserver.fusion.TimeFusion;
import de.htwsaar.zeitserver.fusion.ZoneDisplay;
import de.htwsaar.zeitserver.monitor.TimeMonitor;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** Prometheus-Textformat für Grafana (ohne externe Bibliothek). */
public final class PrometheusExporter {

    private PrometheusExporter() {}

    public static String format(TimeMonitor.Snapshot snap, TimeAnalysis.Result analysis) {
        StringBuilder sb = new StringBuilder(2048);
        long ts = snap.capturedAt().toEpochMilli();

        appendGauge(sb, "zeitserver_up", 1, ts);
        appendGauge(sb, "zeitserver_sources_total", snap.readings().size(), ts);

        ZoneDisplay.DstInfo dst = ZoneDisplay.now();
        appendGauge(sb, "zeitserver_dst_daylight_saving", dst.daylightSaving() ? 1 : 0, ts);

        FailoverResolver.Result fo = snap.failover();
        appendGauge(sb, "zeitserver_failover_active", "FAILOVER".equals(fo.mode()) ? 1 : 0, ts);

        for (TimeAnalysis.SourceStatus st : analysis.sources()) {
            String id = st.id().toLowerCase(Locale.ROOT);
            double alive = st.utc().isPresent() ? 1 : 0;
            appendGauge(sb, "zeitserver_source_up", alive, ts, "source", id);
            if (st.offsetSeconds().isPresent()) {
                appendGauge(sb, "zeitserver_source_offset_seconds", st.offsetSeconds().get(), ts, "source", id);
            }
        }

        for (TimeFusion.SourceReading r : snap.readings()) {
            String id = r.name();
            appendGauge(sb, "zeitserver_history_samples", snap.historySampleCount(id), ts, "source",
                    id.toLowerCase(Locale.ROOT));
        }

        for (TimeAnalysis.PairDelta p : analysis.pairwise()) {
            String a = p.a().toLowerCase(Locale.ROOT);
            String b = p.b().toLowerCase(Locale.ROOT);
            if (a.compareTo(b) > 0) {
                String t = a;
                a = b;
                b = t;
            }
            appendGauge(sb, "zeitserver_pair_delta_seconds", p.deltaSeconds(), ts, "source_a", a, "source_b",
                    b);
        }

        Optional<Instant> fused = snap.fusedUtc();
        if (fused.isPresent() && analysis.referenceUtc().isPresent()) {
            double off = TimeFusion.secondsBetween(fused.get(), analysis.referenceUtc().get());
            appendGauge(sb, "zeitserver_fusion_offset_seconds", off, ts);
        }

        return sb.toString();
    }

    private static void appendGauge(
            StringBuilder sb, String name, double value, long tsMs, String... labels) {
        sb.append("# TYPE ").append(name).append(" gauge\n");
        sb.append(name);
        if (labels.length >= 2) {
            sb.append('{');
            for (int i = 0; i < labels.length; i += 2) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(labels[i]).append("=\"").append(esc(labels[i + 1])).append('"');
            }
            sb.append('}');
        }
        sb.append(' ').append(String.format(Locale.ROOT, "%.6f", value));
        sb.append(' ').append(tsMs).append('\n');
    }

    private static void appendGauge(StringBuilder sb, String name, double value, long tsMs) {
        appendGauge(sb, name, value, tsMs, new String[0]);
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
