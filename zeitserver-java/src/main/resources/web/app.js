const SOURCE_ORDER = ["NTP", "GPS", "DCF77", "RTC"];
const DISPLAY_CHART_MIN = 10;
const BADGE = {
  EXACT: "Genau",
  GOOD: "Gut",
  DRIFT: "Abweichung",
  OUTLIER: "Ungenau",
  MISSING: "Keine Daten",
};

const SOURCE_TITLES = {
  NTP: "NTP — Internet-Zeit (pool.ntp.org)",
  GPS: "GPS — Satelliten-Zeit (NMEA, serielle Schnittstelle)",
  DCF77: "DCF77 — Funkuhr-Signal aus Mainflingen (77,5 kHz)",
  RTC: "RTC — batteriegepufferte Echtzeituhr (DS3231, I2C)",
};

const $ = (id) => document.getElementById(id);

const CHART_COLORS = {
  NTP: { border: "#22c55e", bg: "rgba(34,197,94,0.12)" },
  GPS: { border: "#3b82f6", bg: "rgba(59,130,246,0.12)" },
  DCF77: { border: "#a855f7", bg: "rgba(168,85,247,0.12)" },
  RTC: { border: "#f59e0b", bg: "rgba(245,158,11,0.12)" },
};

const PAIR_DEFS = [
  { key: "GPS|NTP", label: "NTP ↔ GPS", border: "#f97316", css: "pair-ntp-gps" },
  { key: "DCF77|NTP", label: "NTP ↔ DCF77", border: "#06b6d4", css: "pair-ntp-dcf77" },
  { key: "DCF77|GPS", label: "GPS ↔ DCF77", border: "#eab308", css: "pair-gps-dcf77" },
  { key: "RTC|NTP", label: "NTP ↔ RTC", border: "#84cc16", css: "pair-ntp-rtc" },
  { key: "RTC|GPS", label: "GPS ↔ RTC", border: "#14b8a6", css: "pair-gps-rtc" },
  { key: "DCF77|RTC", label: "DCF77 ↔ RTC", border: "#d946ef", css: "pair-dcf77-rtc" },
];

let chartRetentionMin = DISPLAY_CHART_MIN;
const perSourceCharts = {}; // id -> Chart.js Instanz

function pairKey(a, b) {
  return [a, b].sort().join("|");
}

function pairDefFor(a, b) {
  return PAIR_DEFS.find((p) => p.key === pairKey(a, b));
}

function formatClock(iso) {
  if (!iso) {
    return { time: "--:--:--", date: "Keine Zeit", sub: "" };
  }
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) {
      return { time: "--:--:--", date: String(iso), sub: "" };
    }
    const time = d.toLocaleTimeString("de-DE", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
      timeZone: "Europe/Berlin",
    });
    const date = d.toLocaleDateString("de-DE", {
      weekday: "long",
      day: "2-digit",
      month: "long",
      year: "numeric",
      timeZone: "Europe/Berlin",
    });
    const tz = d
      .toLocaleTimeString("de-DE", {
        timeZoneName: "short",
        timeZone: "Europe/Berlin",
      })
      .split(" ")
      .pop();
    return { time, date, sub: "Ortszeit Deutschland · " + tz };
  } catch {
    return { time: "--:--:--", date: String(iso), sub: "" };
  }
}

function formatOffset(sec) {
  if (sec == null) return "";
  const sign = sec >= 0 ? "+" : "";
  if (Math.abs(sec) < 1) {
    return `${sign}${(sec * 1000).toFixed(0)} ms zur Referenz`;
  }
  return `${sign}${sec.toFixed(3)} s zur Referenz`;
}

function accuracyClass(a) {
  return (a || "MISSING").toLowerCase();
}

function applyLiveOfflineRules(s) {
  const detail = s.detail || "";
  if (s.id === "DCF77") {
    if (
      detail.startsWith("Minutenanfang") ||
      detail.startsWith("Bit S") ||
      detail.includes("abgedeckt") ||
      detail.includes("Schwacher") ||
      detail.includes("Fenster") ||
      detail.includes("Rauschen") ||
      detail.includes("kein gültiger") ||
      detail.includes("Kein Funksignal") ||
      detail.includes("DCF77 offline") ||
      detail.includes("warte auf Impulse")
    ) {
      return {
        ...s,
        utc: null,
        offsetSeconds: null,
        accuracy: "MISSING",
        label: "Keine Zeit verfügbar",
      };
    }
  }
  if (s.id === "GPS") {
    if (detail.startsWith("GPS-Uhr ohne Positions-Fix")) {
      return s;
    }
    if (
      detail.includes("abgedeckt") ||
      detail.includes("ohne Fix") ||
      detail.includes("kein GPS") ||
      detail.includes("Kein GPS") ||
      detail.includes("Satelliten-Fix") ||
      detail.includes("Port nicht geöffnet") ||
      /,V[,*]/.test(detail) ||
      /,V,N/.test(detail)
    ) {
      return {
        ...s,
        utc: null,
        offsetSeconds: null,
        accuracy: "MISSING",
        label: "Keine Zeit verfügbar",
      };
    }
  }
  return s;
}

function normalizeSources(sources) {
  const map = new Map((sources || []).map((s) => [s.id, s]));
  return SOURCE_ORDER.map((id) => {
    if (map.has(id)) return applyLiveOfflineRules(map.get(id));
    const hints = {
      GPS: "Nicht aktiv oder kein Port/Fix — Pi: gps.enabled=true, Port /dev/serial0, hciuart stoppen",
      DCF77: "Nicht aktiv oder noch keine Minute dekodiert — Pi: dcf77.enabled=true, GPIO4 (BCM, Pin 7), 1–2 Min warten",
      NTP: "NTP deaktiviert — ntp.enabled=true",
      RTC: "RTC deaktiviert — rtc.enabled=true, DS3231 an I2C 0x68, dtparam=i2c_arm=on",
    };
    return {
      id,
      utc: null,
      offsetSeconds: null,
      accuracy: "MISSING",
      label: "Keine Zeit verfügbar",
      detail: hints[id] || "",
    };
  });
}

function addClock(parent, c) {
  const clock = document.createElement("div");
  clock.className = "clock card-clock";
  const t = document.createElement("span");
  t.className = "clock-time";
  t.textContent = c.time;
  const d = document.createElement("span");
  d.className = "clock-date";
  d.textContent = c.date;
  clock.appendChild(t);
  clock.appendChild(d);
  if (c.sub) {
    const sub = document.createElement("span");
    sub.className = "clock-sub";
    sub.textContent = c.sub;
    clock.appendChild(sub);
  }
  parent.appendChild(clock);
}

function renderSources(sources) {
  const el = $("sources");
  el.innerHTML = "";
  for (const s of normalizeSources(sources)) {
    const c = formatClock(s.utc);
    const card = document.createElement("article");
    card.className = `card ${accuracyClass(s.accuracy)}`;

    const header = document.createElement("div");
    header.className = "card-header";
    const h3 = document.createElement("h3");
    h3.textContent = s.id;
    const badge = document.createElement("span");
    badge.className = "badge";
    badge.textContent = BADGE[s.accuracy] || s.accuracy;
    header.appendChild(h3);
    header.appendChild(badge);
    card.appendChild(header);

    addClock(card, c);

    if (s.offsetSeconds != null) {
      const p = document.createElement("p");
      p.className = "offset";
      const strong = document.createElement("strong");
      strong.textContent = formatOffset(s.offsetSeconds);
      p.appendChild(strong);
      card.appendChild(p);
    }
    if (s.accuracy === "MISSING" && s.detail) {
      const p = document.createElement("p");
      p.className = "detail-warn";
      p.textContent = s.detail;
      card.appendChild(p);
    } else if (s.label) {
      const p = document.createElement("p");
      p.className = "label";
      p.textContent = s.label;
      card.appendChild(p);
    }
    if (s.detail && s.accuracy !== "MISSING") {
      const p = document.createElement("p");
      p.className = "detail";
      p.textContent = s.detail;
      card.appendChild(p);
    }
    el.appendChild(card);
  }
}

/* ==================== Klartext-Vergleich Quelle ↔ Quelle ==================== */

function deltaSentence(a, b, d) {
  const abs = Math.abs(d);
  if (abs < 0.5) return `${a} und ${b} zeigen praktisch dieselbe Zeit.`;
  if (abs < 2) return `${a} und ${b} weichen leicht voneinander ab.`;
  const ahead = d >= 0 ? a : b;
  const behind = d >= 0 ? b : a;
  return `${ahead} geht ${abs.toFixed(1).replace(".", ",")} s vor gegenüber ${behind}.`;
}

function deltaClass(d) {
  const abs = Math.abs(d);
  if (abs < 0.5) return "delta-ok";
  if (abs < 2) return "delta-warn";
  return "delta-bad";
}

function renderMatrix(pairwise) {
  const el = $("matrix");
  el.innerHTML = "";
  if (!pairwise || pairwise.length === 0) {
    const p = document.createElement("p");
    p.className = "hint";
    p.textContent = "Mindestens zwei Quellen mit Zeit nötig für Differenzen.";
    el.appendChild(p);
    return;
  }
  for (const pair of pairwise) {
    const def = pairDefFor(pair.a, pair.b);
    const cell = document.createElement("div");
    cell.className = "delta-cell " + deltaClass(pair.deltaSeconds) + (def ? " " + def.css : "");
    const title = document.createElement("div");
    title.className = "pair";
    title.textContent = def ? def.label : `${pair.a} ↔ ${pair.b}`;
    const val = document.createElement("div");
    val.className = "val";
    val.textContent = `${pair.deltaSeconds >= 0 ? "+" : ""}${pair.deltaSeconds.toFixed(3)} s`;
    const sentence = document.createElement("div");
    sentence.className = "sentence";
    sentence.textContent = deltaSentence(pair.a, pair.b, pair.deltaSeconds);
    cell.appendChild(title);
    cell.appendChild(val);
    cell.appendChild(sentence);
    el.appendChild(cell);
  }
}

function showError(msg) {
  let banner = document.querySelector(".error-banner");
  if (!banner) {
    banner = document.createElement("div");
    banner.className = "error-banner";
    document.body.prepend(banner);
  }
  banner.textContent = msg;
  banner.classList.add("visible");
}

function hideError() {
  const banner = document.querySelector(".error-banner");
  if (banner) banner.classList.remove("visible");
}

function tickBrowserClock() {
  const el = $("browser-clock");
  if (!el) return;
  const t = new Date().toLocaleTimeString("de-DE", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  });
  el.textContent = "Dein PC jetzt: " + t;
}

function renderBanners(data) {
  const el = $("status-banners");
  if (!el) return;
  el.innerHTML = "";
  if (data.dst) {
    const d = document.createElement("div");
    d.className = "info-banner";
    d.textContent =
      (data.dst.label || "Zeitzone") +
      " · Offset " +
      (data.dst.offset || "") +
      (data.dst.daylightSaving ? " (Sommerzeit)" : " (Winterzeit)");
    el.appendChild(d);
  }
  if (data.failover && data.failover.mode === "FAILOVER") {
    const f = document.createElement("div");
    f.className = "warn-banner";
    f.textContent =
      "Failover aktiv: " +
      (data.failover.explanation || "") +
      (data.failover.selectedSource
        ? " → Quelle " + data.failover.selectedSource
        : "");
    el.appendChild(f);
  }
}

/* ==================== Ein Diagramm pro Quelle ==================== */

function relMinutes(epochMs, nowMs) {
  return Math.round(((epochMs - nowMs) / 60000) * 10) / 10;
}

function sourceChartOptions() {
  const ret = chartRetentionMin;
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: { duration: 0 },
    interaction: { mode: "index", intersect: false },
    scales: {
      x: {
        type: "linear",
        min: -ret,
        max: 0,
        title: { display: false },
        ticks: {
          stepSize: 2,
          color: "#94a3b8",
          callback: (v) => (v >= -0.05 ? "jetzt" : `${v} min`),
        },
        grid: { color: "rgba(148,163,184,0.08)" },
      },
      y: {
        title: { display: true, text: "Abweichung (s)", color: "#94a3b8" },
        ticks: { maxTicksLimit: 5, color: "#94a3b8" },
        grid: {
          color: (ctx) =>
            ctx.tick && ctx.tick.value === 0
              ? "rgba(226,232,240,0.55)"
              : "rgba(148,163,184,0.08)",
          lineWidth: (ctx) => (ctx.tick && ctx.tick.value === 0 ? 2 : 1),
        },
      },
    },
    plugins: {
      legend: { display: false },
      tooltip: {
        callbacks: {
          title: (items) => {
            const x = items[0]?.parsed?.x ?? 0;
            return x >= -0.05 ? "Jetzt" : `vor ${Math.abs(x).toFixed(1)} Min`;
          },
          label: (item) => ` ${item.parsed.y.toFixed(3)} s zur Referenz`,
        },
      },
    },
  };
}

function centerYAxis(chart) {
  if (!chart?.data?.datasets?.length) return;
  let min = Infinity;
  let max = -Infinity;
  for (const ds of chart.data.datasets) {
    for (const pt of ds.data) {
      if (pt?.y == null || Number.isNaN(pt.y)) continue;
      min = Math.min(min, pt.y);
      max = Math.max(max, pt.y);
    }
  }
  if (!Number.isFinite(min)) {
    chart.options.scales.y.min = -1;
    chart.options.scales.y.max = 1;
    return;
  }
  const pad = Math.max(0.05, (max - min) * 0.15, Math.abs(max) * 0.1, Math.abs(min) * 0.1);
  const bound = Math.max(Math.abs(min), Math.abs(max), 0.5) + pad;
  chart.options.scales.y.min = -bound;
  chart.options.scales.y.max = bound;
}

function statusWordFor(s) {
  if (!s || s.accuracy === "MISSING") return { text: "AUSGEFALLEN — keine Daten", cls: "st-bad" };
  if (s.accuracy === "OUTLIER") return { text: "weicht stark ab", cls: "st-bad" };
  if (s.accuracy === "DRIFT") return { text: "leichte Abweichung", cls: "st-warn" };
  return { text: "liefert Zeit — in Ordnung", cls: "st-ok" };
}

function ensureSourceBlock(id) {
  const wrap = $("per-source-charts");
  let block = document.getElementById("src-block-" + id);
  if (block) return block;
  block = document.createElement("div");
  block.className = "src-block";
  block.id = "src-block-" + id;

  const head = document.createElement("div");
  head.className = "src-head";
  const dot = document.createElement("span");
  dot.className = "src-dot";
  dot.style.background = (CHART_COLORS[id] || {}).border || "#94a3b8";
  const title = document.createElement("span");
  title.className = "src-title";
  title.textContent = SOURCE_TITLES[id] || id;
  const status = document.createElement("span");
  status.className = "src-status";
  status.id = "src-status-" + id;
  head.appendChild(dot);
  head.appendChild(title);
  head.appendChild(status);
  block.appendChild(head);

  const card = document.createElement("div");
  card.className = "chart-card src-chart-card";
  const canvas = document.createElement("canvas");
  canvas.id = "src-chart-" + id;
  canvas.height = 140;
  card.appendChild(canvas);
  block.appendChild(card);

  const tlLabel = document.createElement("div");
  tlLabel.className = "tl-label";
  tlLabel.textContent = "Zeitstrahl: farbig = Zeit empfangen · grau = Ausfall";
  block.appendChild(tlLabel);

  const tl = document.createElement("div");
  tl.className = "timeline";
  tl.id = "src-tl-" + id;
  block.appendChild(tl);

  wrap.appendChild(block);
  return block;
}

function renderSourceTimeline(id, pts, now) {
  const tl = $("src-tl-" + id);
  if (!tl) return;
  tl.innerHTML = "";
  const windowMs = chartRetentionMin * 60 * 1000;
  const start = now - windowMs;
  if (!pts.length) {
    const seg = document.createElement("div");
    seg.className = "tl-seg";
    seg.style.left = "0%";
    seg.style.width = "100%";
    seg.style.background = "#334155";
    seg.title = "Keine Daten im Zeitfenster";
    tl.appendChild(seg);
    return;
  }
  const color = (CHART_COLORS[id] || {}).border || "#94a3b8";
  // Zusammenhängende Abschnitte gleichen Zustands bilden
  let segStart = Math.max(pts[0].t, start);
  let segUp = !!pts[0].up;
  const flush = (endT) => {
    const left = ((segStart - start) / windowMs) * 100;
    const width = Math.max(((endT - segStart) / windowMs) * 100, 0.4);
    const seg = document.createElement("div");
    seg.className = "tl-seg";
    seg.style.left = left + "%";
    seg.style.width = width + "%";
    seg.style.background = segUp ? color : "#334155";
    seg.title = segUp ? "Zeit empfangen" : "Ausfall / keine Daten";
    tl.appendChild(seg);
  };
  for (let i = 1; i < pts.length; i++) {
    const up = !!pts[i].up;
    if (up !== segUp) {
      flush(pts[i].t);
      segStart = pts[i].t;
      segUp = up;
    }
  }
  flush(now);
}

function renderCharts(data) {
  if (!data.charts || typeof Chart === "undefined") return;
  const serverRetention = data.charts.retentionMinutes || DISPLAY_CHART_MIN;
  chartRetentionMin = Math.min(serverRetention, DISPLAY_CHART_MIN);
  const hint = $("chart-retention-hint");
  if (hint) {
    const samples = data.historySamples || {};
    const vals = Object.values(samples);
    const n = vals.length ? Math.max(...vals) : 0;
    hint.textContent =
      `Verlauf der letzten ${chartRetentionMin} Minuten (${n} Messpunkte pro Quelle, alle 2 s).`;
  }
  const series = data.charts.series || {};
  const now = Date.now();
  const cutoff = now - chartRetentionMin * 60 * 1000;
  const srcMap = new Map(normalizeSources(data.sources || []).map((s) => [s.id, s]));

  for (const id of SOURCE_ORDER) {
    ensureSourceBlock(id);
    const pts = (series[id] || []).filter((p) => p.t && p.t >= cutoff);
    const c = CHART_COLORS[id] || { border: "#94a3b8", bg: "rgba(148,163,184,0.1)" };

    // Statuszeile im Kopf
    const st = statusWordFor(srcMap.get(id));
    const statusEl = $("src-status-" + id);
    if (statusEl) {
      const s = srcMap.get(id);
      const offTxt = s && s.offsetSeconds != null
        ? " · aktuell " + formatOffset(s.offsetSeconds).replace(" zur Referenz", "")
        : "";
      statusEl.textContent = st.text + offTxt;
      statusEl.className = "src-status " + st.cls;
    }

    // Liniendiagramm (nur Punkte, an denen die Quelle Zeit hatte)
    let chart = perSourceCharts[id];
    if (!chart) {
      chart = new Chart($("src-chart-" + id), {
        type: "line",
        data: { labels: [], datasets: [] },
        options: sourceChartOptions(),
      });
      perSourceCharts[id] = chart;
    }
    chart.data.datasets = [
      {
        label: id,
        borderColor: c.border,
        backgroundColor: c.bg,
        data: pts
          .filter((p) => p.up && p.offset != null)
          .map((p) => ({ x: relMinutes(p.t, now), y: p.offset })),
        tension: 0.15,
        spanGaps: false,
        fill: false,
        pointRadius: 0,
        borderWidth: 2,
      },
    ];
    chart.options.scales.x.min = -chartRetentionMin;
    chart.options.scales.x.max = 0;
    centerYAxis(chart);
    chart.update("none");

    renderSourceTimeline(id, pts, now);
  }
}

function render(data) {
  renderBanners(data);
  renderCharts(data);
  $("ref-label").textContent = data.referenceLabel || "Referenz";
  const ref = formatClock(data.referenceUtc);
  $("ref-clock-time").textContent = ref.time;
  $("ref-clock-date").textContent =
    ref.date + (ref.sub ? " · " + ref.sub : "");
  $("updated").textContent =
    (data.buildId ? "Build: " + data.buildId + " · " : "") +
    "Letztes Update: " +
    (data.capturedAt ? formatClock(data.capturedAt).time + " Uhr" : "");
  const fused = data.fusedUtc ? formatClock(data.fusedUtc) : null;
  $("fused-hint").textContent = fused
    ? "Fusion (Median): " + fused.time + " Uhr"
    : "";
  renderSources(data.sources || []);
  renderMatrix(data.pairwise || []);
}

let waitingForServer = false;

async function poll() {
  try {
    const res = await fetch("/api/status", { cache: "no-store" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    const text = await res.text();
    let data;
    try {
      data = JSON.parse(text);
    } catch {
      throw new Error("Ungültiges JSON — bitte Webserver neu starten.");
    }
    waitingForServer = false;
    render(data);
    hideError();
  } catch (e) {
    if (!waitingForServer) {
      waitingForServer = true;
      showError("Keine Verbindung zum Zeitserver — bitte kurz warten oder Pi neu starten.");
    }
    console.error(e);
  }
}

tickBrowserClock();
setInterval(tickBrowserClock, 1000);
poll();
setInterval(poll, 2000);
