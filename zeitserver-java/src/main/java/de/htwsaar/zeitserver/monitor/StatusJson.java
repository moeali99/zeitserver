package de.htwsaar.zeitserver.monitor;

import de.htwsaar.zeitserver.fusion.FailoverResolver;
import de.htwsaar.zeitserver.fusion.TimeAnalysis;
import de.htwsaar.zeitserver.fusion.TimeHistoryStore;
import de.htwsaar.zeitserver.fusion.ZoneDisplay;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** JSON für Web-API und {@code zeitserver-status.json}. */
public final class StatusJson {

    private StatusJson() {}

    public static String serialize(
            TimeMonitor.Snapshot snap, TimeAnalysis.Result analysis, TimeHistoryStore history) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");
        sb.append("  \"buildId\": \"").append(esc(de.htwsaar.zeitserver.BuildInfo.BUILD_ID)).append("\",\n");
        appendInstant(sb, "capturedAt", snap.capturedAt());
        sb.append(",\n");
        Optional<Instant> showFusion = snap.failover().fusedUtc().or(() -> snap.fusedUtc());
        appendInstant(sb, "fusedUtc", showFusion);
        sb.append(",\n");
        appendInstant(sb, "referenceUtc", analysis.referenceUtc());
        sb.append(",\n  \"referenceLabel\": \"").append(esc(analysis.referenceLabelDe())).append("\",\n");

        FailoverResolver.Result fo = snap.failover();
        sb.append("  \"failover\": {\n");
        sb.append("    \"mode\": \"").append(esc(fo.mode())).append("\",\n");
        sb.append("    \"explanation\": \"").append(esc(fo.explanationDe())).append("\",\n");
        sb.append("    \"selectedSource\": ");
        sb.append(fo.selectedSource().map(s -> "\"" + esc(s) + "\"").orElse("null"));
        sb.append(",\n    \"selectedUtc\": ");
        sb.append(fo.selectedUtc().map(u -> "\"" + u + "\"").orElse("null"));
        sb.append("\n  },\n");

        ZoneDisplay.DstInfo dst = snap.dst();
        sb.append("  \"dst\": {\n");
        sb.append("    \"zoneId\": \"").append(esc(dst.zoneId())).append("\",\n");
        sb.append("    \"daylightSaving\": ").append(dst.daylightSaving()).append(",\n");
        sb.append("    \"label\": \"").append(esc(dst.labelDe())).append("\",\n");
        sb.append("    \"offset\": \"").append(esc(dst.offsetLabel())).append("\"\n");
        sb.append("  },\n");

        sb.append("  \"historyRetentionMinutes\": ").append(snap.historyRetentionMinutes()).append(",\n");
        sb.append("  \"historySamples\": {");
        int h = 0;
        for (Map.Entry<String, Integer> e : snap.historySampleCounts().entrySet()) {
            if (h++ > 0) {
                sb.append(',');
            }
            sb.append("\n    \"").append(esc(e.getKey())).append("\": ").append(e.getValue());
        }
        sb.append("\n  },\n");

        sb.append("  \"sources\": [\n");
        for (int i = 0; i < analysis.sources().size(); i++) {
            TimeAnalysis.SourceStatus st = analysis.sources().get(i);
            String detail = i < snap.details().size() ? snap.details().get(i) : "";
            if (i > 0) {
                sb.append(",\n");
            }
            sb.append("    {\n");
            sb.append("      \"id\": \"").append(esc(st.id())).append("\",\n");
            sb.append("      \"utc\": ");
            sb.append(st.utc().map(u -> "\"" + u + "\"").orElse("null"));
            sb.append(",\n");
            sb.append("      \"detail\": \"").append(esc(detail)).append("\",\n");
            if (st.offsetSeconds().isPresent()) {
                sb.append("      \"offsetSeconds\": ")
                        .append(String.format(Locale.ROOT, "%.6f", st.offsetSeconds().get()))
                        .append(",\n");
            } else {
                sb.append("      \"offsetSeconds\": null,\n");
            }
            sb.append("      \"accuracy\": \"").append(st.accuracy().name()).append("\",\n");
            sb.append("      \"label\": \"").append(esc(st.labelDe())).append("\",\n");
            sb.append("      \"historySamples\": ").append(snap.historySampleCount(st.id())).append("\n");
            sb.append("    }");
        }
        sb.append("\n  ],\n  \"pairwise\": [\n");
        for (int i = 0; i < analysis.pairwise().size(); i++) {
            TimeAnalysis.PairDelta p = analysis.pairwise().get(i);
            if (i > 0) {
                sb.append(",\n");
            }
            sb.append("    { \"a\": \"").append(esc(p.a())).append("\", ");
            sb.append("\"b\": \"").append(esc(p.b())).append("\", ");
            sb.append("\"deltaSeconds\": ")
                    .append(String.format(Locale.ROOT, "%.6f", p.deltaSeconds()))
                    .append(" }");
        }
        sb.append("\n  ],\n  \"charts\": ");
        appendCharts(sb, snap, history);
        sb.append(",\n  \"pairwiseText\": [\n");
        for (int i = 0; i < snap.deltas().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\n    \"").append(esc(snap.deltas().get(i))).append("\"");
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    private static void appendCharts(StringBuilder sb, TimeMonitor.Snapshot snap, TimeHistoryStore history) {
        sb.append("{\n    \"retentionMinutes\": ").append(snap.historyRetentionMinutes()).append(",\n");
        sb.append("    \"series\": {\n");
        boolean first = true;
        for (String id : snap.historySampleCounts().keySet()) {
            if ("FAILOVER".equals(id)) {
                continue;
            }
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            appendChartSeries(sb, id, history.chartSeries(id));
        }
        sb.append("\n    }\n  }");
    }

    private static void appendChartSeries(StringBuilder sb, String id, List<TimeHistoryStore.ChartPoint> points) {
        sb.append("      \"").append(esc(id)).append("\": [\n");
        for (int i = 0; i < points.size(); i++) {
            TimeHistoryStore.ChartPoint p = points.get(i);
            if (i > 0) {
                sb.append(",\n");
            }
            sb.append("        { \"t\": ").append(p.epochMs()).append(", \"up\": ").append(p.up());
            sb.append(", \"offset\": ");
            if (p.offsetSeconds() == null || p.offsetSeconds().isNaN()) {
                sb.append("null");
            } else {
                sb.append(String.format(Locale.ROOT, "%.6f", p.offsetSeconds()));
            }
            sb.append(" }");
        }
        sb.append("\n      ]");
    }

    private static void appendInstant(StringBuilder sb, String key, Optional<Instant> instant) {
        sb.append("  \"").append(key).append("\": ");
        sb.append(instant.map(i -> "\"" + i + "\"").orElse("null"));
    }

    private static void appendInstant(StringBuilder sb, String key, Instant instant) {
        sb.append("  \"").append(key).append("\": \"").append(instant).append("\"");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
