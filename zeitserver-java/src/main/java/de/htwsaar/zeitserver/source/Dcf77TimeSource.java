package de.htwsaar.zeitserver.source;

import de.htwsaar.zeitserver.config.AppConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest DCF77 am GPIO über {@code gpiomon}. Bei schwachem Sync (&lt; 1,5 s) wird der
 * Minutenanfang über Systemzeit (NTP) gesetzt; Dekodierung nutzt {@link Dcf77MinuteCodec}.
 */
public final class Dcf77TimeSource implements TimeSource {

    private static final Pattern GPIOMON_TS =
            Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s+(rising|falling)\\b");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final AppConfig cfg;
    private final AtomicReference<Instant> lastUtc = new AtomicReference<>();
    private final AtomicReference<String> detail = new AtomicReference<>("Gestartet …");
    /** Letzter GPIO-Impuls (ms); ohne Events gilt die Quelle als offline. */
    private volatile long lastGpioEventMs = System.currentTimeMillis();
    /** Letzte echte Funk-Dekodierung (nicht NTP-Fallback). */
    private volatile long lastRadioDecodeMs;
    private volatile boolean running;
    private Thread gpioThread;
    private Thread clockThread;
    private Process gpiomon;
    private final MinuteFrame frame = new MinuteFrame();
    /** Ohne Impulse so lange → DCF77 offline. */
    private static final long GPIO_SILENCE_MS = 12_000;
    /** Polarität nur selten umschalten — sonst thrashing + „warte auf Impulse“-Schleife. */
    private static final long POLARITY_FLIP_MIN_MS = 45_000;
    /** Funk-Dekodierung max. so lange gültig ohne neue erfolgreiche Minute. */
    private static final long RADIO_STALE_MS = 75_000;
    /** Mindestens so viele Bits für eine echte Minute (sonst Rauschen bei Abdeckung). */
    private static final int MIN_GOOD_BITS = 22;
    private volatile boolean decodeValid;
    private volatile long gpioEdgeCount;
    private volatile boolean activeLowRuntime;
    private volatile long lastPolarityFlipMs;

    public Dcf77TimeSource(AppConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String id() {
        return "DCF77";
    }

    @Override
    public void start() {
        running = true;
        activeLowRuntime = cfg.dcf77ActiveLow();
        clockThread = new Thread(this::clockLoop, "dcf77-clock");
        clockThread.setDaemon(true);
        clockThread.start();
        gpioThread = new Thread(this::gpioLoop, "dcf77-gpio");
        gpioThread.setDaemon(true);
        gpioThread.start();
    }

    private void clockLoop() {
        while (running) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastGpioEventMs > GPIO_SILENCE_MS) {
                long waitedSec = Math.max(0, (nowMs - lastGpioEventMs) / 1000);
                clearUtc("Kein Funksignal (keine GPIO-Impulse, " + waitedSec
                        + "s, activeLow=" + activeLowRuntime
                        + ") — Antenne ans Fenster / BCM 4 prüfen");
                // Polarität selten umschalten (sonst Dauer-Neustart → immer „warte …“).
                if (gpioEdgeCount == 0 && nowMs - lastPolarityFlipMs > POLARITY_FLIP_MIN_MS) {
                    activeLowRuntime = !activeLowRuntime;
                    lastPolarityFlipMs = nowMs;
                    destroyGpiomon();
                }
            } else if (lastRadioDecodeMs > 0 && nowMs - lastRadioDecodeMs > RADIO_STALE_MS) {
                clearUtc("Funk-Zeit veraltet — warte auf neue DCF77-Minute");
            }
            ZonedDateTime z = ZonedDateTime.now(BERLIN);
            int sec = z.getSecond();
            // ~1 Bit/s erwartet; zu wenig Bits mitten in der Minute → offline (Folie/Raum).
            if (sec >= 20) {
                int got = frame.receivedCount();
                int minOk = Math.max(8, sec / 3);
                if (got < minOk) {
                    if (decodeValid || lastUtc.get() != null) {
                        clearUtc("Schwacher Funkempfang (" + got + "/" + minOk
                                + " Bits bei S" + sec + ") — Fenster/Lage, ggf. Abdeckung");
                    } else if (gpioEdgeCount > 0 && got < Math.max(2, sec / 4)) {
                        detail.set("Schwacher Empfang (" + got + "/" + minOk
                                + " gültige Bits, Pulse=" + gpioEdgeCount
                                + ") — oft Rauschen im Raum; Antenne ans Fenster");
                    }
                }
            }
            long minuteEpoch = z.withSecond(0).withNano(0).toEpochSecond();
            if (sec <= 1) {
                frame.beginMinuteIfNew(minuteEpoch);
            }
            if (sec == 59) {
                frame.tryFinishMinute(cfg);
            }
        }
    }

    private void gpioLoop() {
        int bcm = cfg.dcf77GpioBcm();
        if (!Files.isExecutable(Path.of("/usr/bin/gpiomon"))) {
            detail.set("gpiomon nicht gefunden — sudo apt install gpiod");
            return;
        }
        while (running) {
            try {
                runGpiomonSession(bcm);
            } catch (Exception e) {
                if (running) {
                    detail.set("gpiomon: " + shortMsg(e) + " — Neustart…");
                }
            }
            destroyGpiomon();
            if (!running) {
                break;
            }
            clearUtc("Keine GPIO-Events — Neustart gpiomon / Kabel prüfen");
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void runGpiomonSession(int bcm) throws Exception {
        String pull = cfg.dcf77PullUp() ? "pull-up" : "pull-down";
        boolean useActiveLow = activeLowRuntime;
        // stdbuf -oL: ohne TTY puffert gpiomon stdout blockweise → Java sieht nie Impulse.
        boolean haveStdbuf = Files.isExecutable(Path.of("/usr/bin/stdbuf"));
        java.util.List<String> cmd = new java.util.ArrayList<>();
        if (haveStdbuf) {
            cmd.add("/usr/bin/stdbuf");
            cmd.add("-oL");
            cmd.add("-eL");
        }
        cmd.add("/usr/bin/gpiomon");
        if (useActiveLow) {
            cmd.add("-l");
        }
        cmd.add("-b");
        cmd.add(pull);
        cmd.add("-c");
        cmd.add("gpiochip0");
        cmd.add("-e");
        cmd.add("both");
        // Kein gpiomon -p Debounce: schon 2ms hat auf dem Pi ALLE Flanken geschluckt
        // (0 Edges). Software filtert kurze Spikes (< 45 ms LOW).
        cmd.add("-F");
        cmd.add("%S %E ");
        cmd.add(Integer.toString(bcm));
        ProcessBuilder pb = shellCommand(cmd.toArray(String[]::new));
        gpiomon = pb.start();
        gpioEdgeCount = 0;
        // Nicht auf „jetzt“ setzen — sonst maskiert der Start echte Stille.
        if (lastGpioEventMs <= 0) {
            lastGpioEventMs = System.currentTimeMillis();
        }
        detail.set("GPIO BCM " + bcm + " (gpiomon"
                + (useActiveLow ? ", active-low" : "")
                + (haveStdbuf ? ", line-buf" : "")
                + ") — warte auf Impulse …");
        int syncMin = cfg.dcf77SyncPulseMinMs();
        Double fallSec = null;
        int wallSec = -1;
        int pendingLowMs = 0;
        long pulseCount = 0;
        long noiseSkipped = 0;
        // Nach fallender Flanke den Impuls „sperren“ — sonst setzt Rauschen
        // fallSec alle 25 ms neu und echte 100/200 ms LOWs entstehen nie.
        final double minPulseSec = 0.045;
        final double maxPulseSec = Math.max(0.55, syncMin / 1000.0 + 0.2);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(gpiomon.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.toLowerCase().contains("watching lines")
                        || trimmed.startsWith("gpiomon")) {
                    continue;
                }
                Matcher m = GPIOMON_TS.matcher(trimmed);
                if (!m.find()) {
                    if (trimmed.contains("rising") || trimmed.contains("falling")) {
                        lastGpioEventMs = System.currentTimeMillis();
                    }
                    continue;
                }
                double sec = Double.parseDouble(m.group(1));
                String edge = m.group(2);
                lastGpioEventMs = System.currentTimeMillis();

                if ("falling".equals(edge)) {
                    if (fallSec == null) {
                        fallSec = sec;
                    } else if ((sec - fallSec) > maxPulseSec) {
                        // hängengeblieben — neu ansetzen
                        fallSec = sec;
                        noiseSkipped++;
                    } else {
                        noiseSkipped++;
                    }
                    continue;
                }
                if (!"rising".equals(edge)) {
                    continue;
                }
                if (fallSec == null) {
                    noiseSkipped++;
                    continue;
                }
                double widthSec = sec - fallSec;
                if (widthSec < minPulseSec) {
                    // Bounce — Impulsstart behalten, weiter warten
                    noiseSkipped++;
                    continue;
                }
                fallSec = null;
                if (widthSec > maxPulseSec) {
                    noiseSkipped++;
                    continue;
                }
                pulseCount++;
                gpioEdgeCount = pulseCount;
                int rawLowMs = (int) Math.round(widthSec * 1000.0);
                int lowMs = normalizeLowMs(rawLowMs, syncMin);
                if (lowMs < 45) {
                    continue;
                }

                if (pulseCount <= 5 || pulseCount % 20 == 0) {
                    detail.set("GPIO Impulse OK (Pulse=" + pulseCount
                            + ", Rauschen~" + noiseSkipped
                            + ", activeLow=" + useActiveLow + ") — Bits sammeln …");
                }

                int nowSec = (int) ZonedDateTime.now(BERLIN).toEpochSecond();
                if (wallSec < 0) {
                    wallSec = nowSec;
                }

                if (lowMs >= syncMin) {
                    if (pendingLowMs >= 45) {
                        frame.ingest(pendingLowMs, syncMin, wallSec, cfg);
                        pendingLowMs = 0;
                    }
                    frame.beginFromDcf(lowMs);
                    wallSec = nowSec;
                    continue;
                }

                if (nowSec != wallSec) {
                    if (pendingLowMs >= 45) {
                        frame.ingest(pendingLowMs, syncMin, wallSec, cfg);
                    }
                    wallSec = nowSec;
                    pendingLowMs = lowMs;
                } else {
                    pendingLowMs = preferPulse(pendingLowMs, lowMs, cfg);
                }
            }
        }
    }

    private void clearUtc(String reason) {
        lastUtc.set(null);
        lastRadioDecodeMs = 0;
        decodeValid = false;
        detail.set(reason);
    }

    private final class MinuteFrame {
        private final boolean[] bits = new boolean[60];
        private final boolean[] received = new boolean[60];
        private long minuteEpoch = -1;
        private long lastStartedMinute = -1;
        private boolean decodedThisMinute;

        synchronized void beginMinuteIfNew(long minuteEpoch) {
            if (minuteEpoch == lastStartedMinute) {
                return;
            }
            if (this.minuteEpoch >= 0) {
                tryDecode(bits, received);
            }
            Arrays.fill(bits, false);
            Arrays.fill(received, false);
            decodedThisMinute = false;
            this.minuteEpoch = minuteEpoch;
            lastStartedMinute = minuteEpoch;
            // Letzte gültige Funkzeit behalten; Fehlerdetail vom tryDecode nicht überschreiben.
            if (lastUtc.get() != null && decodeValid) {
                detail.set("Neue Minute — Bits sammeln (letzte Funkzeit bleibt) …");
            } else {
                String d = detail.get();
                if (d == null || d.startsWith("Bit S") || d.startsWith("DCF-Sync")
                        || d.startsWith("GPIO") || d.startsWith("Minutenanfang")
                        || d.startsWith("Neue Minute")) {
                    detail.set("Minutenanfang — Bits werden gesammelt …");
                }
            }
        }

        synchronized void beginFromDcf(int lowMs) {
            ZonedDateTime z = ZonedDateTime.now(BERLIN);
            long epoch = z.withSecond(0).withNano(0).toEpochSecond();
            if (epoch == lastStartedMinute) {
                return;
            }
            beginMinuteIfNew(epoch);
            detail.set("DCF-Sync ~" + lowMs + " ms — neue Minute (Europe/Berlin)");
        }

        synchronized void ingest(int lowMs, int syncMin, int pulseWallSec, AppConfig cfg) {
            if (minuteEpoch < 0 || lowMs >= syncMin) {
                return;
            }
            int idx = pulseWallSec - (int) minuteEpoch;
            if (idx < 1 || idx > 59) {
                return;
            }
            Optional<Integer> bit = classifyBit(lowMs, cfg);
            if (bit.isEmpty()) {
                return;
            }
            bits[idx] = bit.get() == 1;
            received[idx] = true;
            int got = countReceived(received);
            detail.set("Bit S" + idx + "=" + bit.get() + " (" + got + "/59)"
                    + (decodeValid && lastUtc.get() != null ? " · Zeit aktiv" : ""));
            // Folie: erst spät in der Minute und klar zu wenig Bits → offline.
            if (idx >= 25 && got < Math.max(8, idx / 3)) {
                decodeValid = false;
                lastUtc.set(null);
            }
        }

        synchronized void tryFinishMinute(AppConfig cfg) {
            if (minuteEpoch < 0 || decodedThisMinute) {
                return;
            }
            decodedThisMinute = true;
            tryDecode(bits, received);
        }

        synchronized int receivedCount() {
            return countReceived(received);
        }
    }

    private void tryDecode(boolean[] bits, boolean[] received) {
        int got = countReceived(received);
        if (got < MIN_GOOD_BITS || !hasGoodBitSpread(received, got)) {
            if (lastUtc.get() == null || !decodeValid) {
                clearUtc("Nur " + got + "/59 Bits — kein gültiger Funkempfang");
            } else {
                detail.set("Nur " + got + "/59 Bits — behalte letzte Funkzeit");
            }
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(BERLIN);

        for (int shift = -8; shift <= 8; shift++) {
            boolean[] attempt = shift == 0 ? bits : shiftBits(bits, shift);
            Optional<Dcf77MinuteCodec.DecodeResult> decoded = Dcf77MinuteCodec.decodeWithInfo(attempt);
            if (decoded.isPresent()
                    && applyDecoded(decoded.get(), got, shift == 0 ? "" : " (Versatz " + shift + " s)", 5)) {
                return;
            }
        }

        // Fehlende Bits als 0 brechen die Parität — Lücken mit NTP-Rahmen füllen,
        // aber nur übernehmen wenn die *empfangenen* Bits stark übereinstimmen.
        // DCF77 kodiert die Zeit der *folgenden* Minute.
        for (int dm = 0; dm <= 1; dm++) {
            ZonedDateTime civil = now.withSecond(0).withNano(0).plusMinutes(dm);
            boolean[] ntpFrame = Dcf77MinuteCodec.encodeMinuteFrame(civil);
            boolean[] filled = bits.clone();
            for (int i = 1; i <= 59; i++) {
                if (!received[i]) {
                    filled[i] = ntpFrame[i];
                }
            }
            int agree = 0;
            int compared = 0;
            for (int i = 21; i <= 58; i++) {
                if (received[i]) {
                    compared++;
                    if (filled[i] == ntpFrame[i]) {
                        agree++;
                    }
                }
            }
            if (compared >= 12 && agree * 100 >= compared * 55) {
                Optional<Dcf77MinuteCodec.DecodeResult> patched = Dcf77MinuteCodec.decodeWithInfo(filled);
                if (patched.isPresent()
                        && applyDecoded(patched.get(), got,
                        " (Lücken gefüllt, " + agree + "/" + compared + " OK)", 5)) {
                    return;
                }
            }
        }

        int agree = 0;
        int compared = 0;
        boolean[] probe = Dcf77MinuteCodec.encodeMinuteFrame(now.withSecond(0).withNano(0).plusMinutes(1));
        for (int i = 21; i <= 58; i++) {
            if (received[i]) {
                compared++;
                if (bits[i] == probe[i]) {
                    agree++;
                }
            }
        }

        Optional<Dcf77MinuteCodec.DecodeResult> assisted = Dcf77MinuteCodec.decodeWithNtpAssist(bits, now);
        if (assisted.isPresent() && got >= MIN_GOOD_BITS) {
            // Bei genug Bits NTP nur als Paritäts-/Rahmenhilfe — bei Folie (wenige Bits) offline.
            if (applyDecoded(assisted.get(), got, " (NTP-Stütze, " + got + "/59)", 5)) {
                return;
            }
        }
        if (assisted.isPresent()) {
            if (lastUtc.get() == null || !decodeValid) {
                clearUtc("Funk schwach (" + got + "/59 Bits, NTP-Abgleich) — DCF77 offline | "
                        + assisted.get().dstHintDe());
            } else {
                detail.set("Funk schwach (" + got + "/59) — behalte letzte Funkzeit | "
                        + assisted.get().dstHintDe());
            }
            return;
        }

        if (lastUtc.get() == null || !decodeValid) {
            clearUtc("Dekodierung fehlgeschlagen — " + got + "/59 Bits"
                    + " (Übereinstimmung Zeitbits " + agree + "/" + compared + ")");
        } else {
            detail.set("Dekodierung fehlgeschlagen (" + got + "/59, " + agree + "/" + compared
                    + ") — behalte letzte Funkzeit");
        }
    }

    private boolean applyDecoded(Dcf77MinuteCodec.DecodeResult r, int got, String suffix, long maxDeltaSec) {
        if (got < MIN_GOOD_BITS) {
            clearUtc("Nur " + got + "/59 Bits — Dekodierung verworfen");
            return false;
        }
        long delta = Math.abs(Duration.between(r.utc(), Instant.now()).getSeconds());
        if (delta > maxDeltaSec) {
            clearUtc("Funk-Parität OK, aber " + delta + " s von Systemzeit entfernt — DCF77 verworfen");
            return false;
        }
        lastUtc.set(r.utc());
        lastRadioDecodeMs = System.currentTimeMillis();
        decodeValid = true;
        detail.set("Dekodiert OK (" + got + "/59 Bits)" + suffix + " → " + r.utc() + " | " + r.dstHintDe());
        return true;
    }

    private static int countReceived(boolean[] received) {
        int n = 0;
        for (int i = 1; i <= 59; i++) {
            if (received[i]) {
                n++;
            }
        }
        return n;
    }

    /** Echter Funk: Bits über die Minute verteilt; Rauschen nur wenige Sprünge. */
    private static boolean hasGoodBitSpread(boolean[] received, int got) {
        int min = 60;
        int max = 0;
        for (int i = 1; i <= 59; i++) {
            if (received[i]) {
                min = Math.min(min, i);
                max = Math.max(max, i);
            }
        }
        if (max <= 0) {
            return false;
        }
        return (max - min + 1) >= Math.max(MIN_GOOD_BITS, got * 2 / 3);
    }

    private static boolean[] shiftBits(boolean[] bits, int shift) {
        boolean[] out = new boolean[bits.length];
        for (int s = 1; s <= 59; s++) {
            int src = s + shift;
            if (src >= 1 && src <= 59) {
                out[s] = bits[src];
            }
        }
        return out;
    }

    private static int preferPulse(int current, int candidate, AppConfig cfg) {
        if (current < 45) {
            return candidate;
        }
        int qc = classifyBit(current, cfg).isPresent() ? 100 : 10;
        int qn = classifyBit(candidate, cfg).isPresent() ? 100 : 10;
        if (qn != qc) {
            return qn > qc ? candidate : current;
        }
        int dc = Math.min(Math.abs(current - 100), Math.abs(current - 200));
        int dn = Math.min(Math.abs(candidate - 100), Math.abs(candidate - 200));
        return dn < dc ? candidate : current;
    }

    /**
     * Kurzer DCF-Impuls (100/200 ms) wird oft als ~800–950 ms gemessen, wenn das
     * Rising-Edge knapp nach einer Wandsekundengrenze liegt → {@code 1000 − gemessen}.
     */
    private static int normalizeLowMs(int lowMs, int syncMin) {
        if (lowMs >= syncMin) {
            return lowMs;
        }
        if (lowMs >= 500 && lowMs < syncMin) {
            int inverted = 1000 - lowMs;
            if (inverted >= 45 && inverted <= 400) {
                return inverted;
            }
        }
        return lowMs;
    }

    private static Optional<Integer> classifyBit(int lowMs, AppConfig cfg) {
        int ms = normalizeLowMs(lowMs, cfg.dcf77SyncPulseMinMs());
        if (ms >= cfg.dcf77Bit0LowMinMs() && ms <= cfg.dcf77Bit0LowMaxMs()) {
            return Optional.of(0);
        }
        if (ms >= cfg.dcf77Bit1LowMinMs() && ms <= cfg.dcf77Bit1LowMaxMs()) {
            return Optional.of(1);
        }
        return Optional.empty();
    }

    private static ProcessBuilder shellCommand(String... cmd) {
        ProcessBuilder b = new ProcessBuilder(cmd);
        b.redirectErrorStream(true);
        b.environment().put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        return b;
    }

    private void destroyGpiomon() {
        if (gpiomon != null) {
            gpiomon.destroyForcibly();
            gpiomon = null;
        }
    }

    private static String shortMsg(Throwable t) {
        String m = t.getMessage();
        return m != null && !m.isBlank() ? m : t.getClass().getSimpleName();
    }

    @Override
    public Optional<Instant> getUtc() {
        Instant utc = lastUtc.get();
        if (utc == null || !decodeValid) {
            return Optional.empty();
        }
        long nowMs = System.currentTimeMillis();
        if (lastRadioDecodeMs > 0 && nowMs - lastRadioDecodeMs > RADIO_STALE_MS) {
            return Optional.empty();
        }
        // Folie/Ausfall: mitten in der Minute deutlich zu wenig Bits.
        int sec = ZonedDateTime.now(BERLIN).getSecond();
        int got = frame.receivedCount();
        if (sec >= 20 && got < Math.max(5, sec / 3)) {
            return Optional.empty();
        }
        String d = detail.get();
        if (d != null && (d.contains("Kein Funksignal")
                || d.contains("Keine GPIO")
                || d.contains("abgedeckt")
                || d.contains("veraltet")
                || d.contains("DCF77 offline"))) {
            return Optional.empty();
        }
        long tickMs = System.currentTimeMillis() - lastRadioDecodeMs;
        if (lastRadioDecodeMs > 0 && tickMs > 0 && tickMs < RADIO_STALE_MS) {
            return Optional.of(utc.plusMillis(tickMs));
        }
        return Optional.of(utc);
    }

    @Override
    public String getDetailStatus() {
        return detail.get();
    }

    @Override
    public void close() {
        running = false;
        destroyGpiomon();
        for (Thread t : new Thread[] {gpioThread, clockThread}) {
            if (t != null) {
                try {
                    t.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
