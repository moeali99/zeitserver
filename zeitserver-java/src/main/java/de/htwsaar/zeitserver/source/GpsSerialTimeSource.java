package de.htwsaar.zeitserver.source;

import com.fazecast.jSerialComm.SerialPort;

import de.htwsaar.zeitserver.config.AppConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GPS-Zeit: serielle NMEA-Zeilen, {@code $GPRMC}/{@code $GNRMC} mit Status A.
 */
public final class GpsSerialTimeSource implements TimeSource {

    /** Ohne gültigen Fix so lange → GPS offline (Alufolie-Demo, schnell sichtbar). */
    private static final long FIX_STALE_MS = 3_000;

    private final AppConfig cfg;
    private final String portName;
    private final int baud;
    private final AtomicReference<Instant> lastUtc = new AtomicReference<>();
    private final AtomicReference<String> detail = new AtomicReference<>("Gestartet …");
    private volatile long lastGoodFixMs;
    private volatile int lastFixQuality;
    private volatile int lastSatellites;
    private volatile long lastGgaMs;
    private volatile boolean running;
    private Thread readerThread;
    private Thread watchdogThread;
    private SerialPort port;

    public GpsSerialTimeSource(AppConfig cfg) {
        this.cfg = cfg;
        this.portName = cfg.gpsSerialPort();
        this.baud = cfg.gpsBaudRate();
    }

    @Override
    public String id() {
        return "GPS";
    }

    @Override
    public void start() {
        running = true;
        lastGoodFixMs = System.currentTimeMillis();
        readerThread = new Thread(this::runReader, "gps-nmea");
        readerThread.setDaemon(true);
        readerThread.start();
        watchdogThread = new Thread(this::watchdogLoop, "gps-watchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
    }

    private void watchdogLoop() {
        while (running) {
            sleepMs(500);
            if (lastUtc.get() == null) {
                continue;
            }
            if (System.currentTimeMillis() - lastGoodFixMs > FIX_STALE_MS) {
                lastUtc.set(null);
                detail.set("Kein GPS-Fix seit " + (FIX_STALE_MS / 1000)
                        + " s — zu wenig Satelliten (Fenster nötig, nicht nur Folie)");
            }
        }
    }

    private void runReader() {
        detail.set("Öffne " + portName + " …");
        // jSerialComm openPort hängt auf manchen Pis (serial-getty/miniUART) —
        // primär Raw-Device lesen, Fallback jSerialComm.
        if (runReaderViaFile(portName) || runReaderViaFile(resolveTtyPath(portName))) {
            return;
        }
        runReaderViaJSerial();
    }

    private static String resolveTtyPath(String name) {
        if ("/dev/serial0".equals(name)) {
            return "/dev/ttyS0";
        }
        return name;
    }

    /** Linux: /dev/ttyS0 direkt (zuverlässig auf Raspberry Pi). */
    private boolean runReaderViaFile(String path) {
        try {
            Path p = Path.of(path);
            if (!Files.exists(p)) {
                return false;
            }
            detail.set("Verbunden " + path + " @ " + baud + " (raw)");
            // Baud setzen (best effort)
            try {
                new ProcessBuilder("stty", "-F", path, String.valueOf(baud), "raw", "-echo")
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {
                // weiterlesen trotzdem
            }
            try (var in = Files.newInputStream(p);
                    var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
                readNmeaLoop(reader);
            }
            return true;
        } catch (Exception e) {
            detail.set("Raw-Open " + path + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return false;
        }
    }

    private void runReaderViaJSerial() {
        port = SerialPort.getCommPort(portName);
        port.setBaudRate(baud);
        port.setNumDataBits(8);
        port.setNumStopBits(SerialPort.ONE_STOP_BIT);
        port.setParity(SerialPort.NO_PARITY);
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 500, 0);
        boolean opened = false;
        try {
            opened = port.openPort(2000);
        } catch (Exception e) {
            detail.set("jSerialComm: " + e.getMessage());
            return;
        }
        if (!opened) {
            detail.set("Port nicht geöffnet: " + portName
                    + " — Pi: sudo systemctl mask serial-getty@ttyS0; ls -l /dev/ttyS0");
            return;
        }
        detail.set("Verbunden " + portName + " @ " + baud);
        try (var in = port.getInputStream();
                var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII))) {
            readNmeaLoop(reader);
        } catch (Exception e) {
            detail.set("Lesefehler: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            if (port != null && port.isOpen()) {
                port.closePort();
            }
        }
    }

    private void readNmeaLoop(BufferedReader reader) throws Exception {
        while (running) {
            try {
                String line = reader.readLine();
                if (line == null) {
                    sleepMs(100);
                    continue;
                }
                final String nmea = line.trim();
                if (nmea.isEmpty()) {
                    continue;
                }
                handleNmeaLine(nmea);
            } catch (Exception e) {
                if (!running) {
                    break;
                }
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("timed out") || msg.contains("Timeout")) {
                    continue;
                }
                detail.set("Lesefehler: " + (msg.isEmpty() ? e.getClass().getSimpleName() : msg));
                sleepMs(200);
            }
        }
    }

    private void handleNmeaLine(String nmea) {
                    if (isGgaLine(nmea)) {
                        updateGgaStatus(nmea);
                        if (!hasValidGgaFix()) {
                            if (cfg.gpsRequireFix()) {
                                lastUtc.set(null);
                                detail.set("GPS ohne Satelliten-Fix (GGA: Qualität="
                                        + lastFixQuality + ", Sats=" + lastSatellites
                                        + ") — Modul sendet, aber kein Fix (Raum/Fenster)");
                                return;
                            }
                            // Demo/Innenraum: Uhrzeit aus GGA auch ohne Positions-Fix.
                            Optional<Instant> ggaTime = parseGgaUtc(nmea);
                            if (ggaTime.isPresent()) {
                                lastUtc.set(ggaTime.get());
                                lastGoodFixMs = System.currentTimeMillis();
                                detail.set("GPS-Uhr ohne Positions-Fix (Sats=" + lastSatellites
                                        + ") — für genauen Fix: Fenster/Freisicht");
                            } else {
                                detail.set("GPS ohne Satelliten-Fix (GGA: Qualität="
                                        + lastFixQuality + ", Sats=" + lastSatellites
                                        + ") — warte auf Zeitfeld");
                            }
                            return;
                        }
                    }
                    // Bei requireFix: kein Fix (V) → offline.
                    if (cfg.gpsRequireFix()) {
                        if (isRmcLine(nmea) && !"A".equals(rmcStatus(nmea))) {
                            lastUtc.set(null);
                            detail.set("NMEA OK, kein GPS-Fix (" + rmcStatus(nmea)
                                    + ") — zu wenig Satelliten, nicht Folie");
                            return;
                        }
                        if (isGllLine(nmea) && !"A".equals(gllStatus(nmea))) {
                            lastUtc.set(null);
                            detail.set("GLL ohne Fix (" + gllStatus(nmea)
                                    + ") — zu wenig Satelliten (Innenraum), nicht Folie");
                            return;
                        }
                        if (!hasValidGgaFix()) {
                            return;
                        }
                    }
                    Optional<Instant> parsed = parseNmeaUtc(nmea, cfg.gpsRequireFix());
                    if (parsed.isEmpty() && isGgaLine(nmea) && !cfg.gpsRequireFix()) {
                        parsed = parseGgaUtc(nmea);
                    }
                    if (parsed.isPresent()) {
                        lastUtc.set(parsed.get());
                        lastGoodFixMs = System.currentTimeMillis();
                        if (hasValidGgaFix()) {
                            detail.set(shortNmea(nmea));
                        } else {
                            detail.set("GPS-Uhr ohne Positions-Fix (Sats=" + lastSatellites
                                    + ") — " + shortNmea(nmea));
                        }
                    }
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String shortNmea(String nmea) {
        return nmea.length() > 72 ? nmea.substring(0, 72) + "…" : nmea;
    }

    private static boolean isRmcLine(String line) {
        return line.startsWith("$GPRMC") || line.startsWith("$GNRMC");
    }

    private static boolean isGllLine(String line) {
        return line.startsWith("$GPGLL") || line.startsWith("$GNGLL");
    }

    private static boolean isGgaLine(String line) {
        return line.startsWith("$GPGGA") || line.startsWith("$GNGGA");
    }

    private void updateGgaStatus(String line) {
        String[] f = line.split(",", -1);
        if (f.length < 8) {
            lastFixQuality = 0;
            lastSatellites = 0;
            lastGgaMs = System.currentTimeMillis();
            return;
        }
        try {
            lastFixQuality = f[6].isEmpty() ? 0 : Integer.parseInt(f[6].trim());
            lastSatellites = f[7].isEmpty() ? 0 : Integer.parseInt(f[7].trim());
        } catch (NumberFormatException e) {
            lastFixQuality = 0;
            lastSatellites = 0;
        }
        lastGgaMs = System.currentTimeMillis();
    }

    /** Gültiger 3D/2D-Fix mit mindestens 3 Satelliten (Alufolie → meist 0). */
    private boolean hasValidGgaFix() {
        if (System.currentTimeMillis() - lastGgaMs > 5_000) {
            return false;
        }
        return lastFixQuality >= 1 && lastSatellites >= 3;
    }

    private static String rmcStatus(String line) {
        String[] f = line.split(",", -1);
        return f.length > 2 ? f[2] : "";
    }

    private static String gllStatus(String line) {
        String[] f = line.split(",", -1);
        return f.length > 6 ? f[6] : "";
    }

    /** RMC oder GLL (u-blox sendet oft nur GLL). */
    static Optional<Instant> parseNmeaUtc(String line, boolean requireFix) {
        Optional<Instant> rmc = parseRmcUtc(line, requireFix);
        if (rmc.isPresent()) {
            return rmc;
        }
        return parseGllUtc(line, requireFix);
    }

    /** NMEA RMC → UTC-Instant; mit {@code requireFix} nur bei Status A. */
    static Optional<Instant> parseRmcUtc(String line, boolean requireFix) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String t = line.trim();
        if (!t.startsWith("$") || t.length() < 10) {
            return Optional.empty();
        }
        int star = t.indexOf('*');
        if (star > 0) {
            t = t.substring(0, star);
        }
        String[] f = t.split(",", -1);
        if (f.length < 10) {
            return Optional.empty();
        }
        String head = f[0];
        if (!head.equals("$GPRMC") && !head.equals("$GNRMC")) {
            return Optional.empty();
        }
        if (requireFix && !"A".equals(f[2])) {
            return Optional.empty();
        }
        if (f[1].isEmpty() || f[9].isEmpty()) {
            return Optional.empty();
        }
        String time = f[1];
        String date = f[9];
        if (time.length() < 6 || date.length() < 6) {
            return Optional.empty();
        }
        try {
            int h = Integer.parseInt(time.substring(0, 2));
            int min = Integer.parseInt(time.substring(2, 4));
            int sec = Integer.parseInt(time.substring(4, 6));
            int day = Integer.parseInt(date.substring(0, 2));
            int month = Integer.parseInt(date.substring(2, 4));
            int year = 2000 + Integer.parseInt(date.substring(4, 6));
            LocalDate d = LocalDate.of(year, month, day);
            LocalTime lt = LocalTime.of(h, min, sec);
            ZonedDateTime z = ZonedDateTime.of(d, lt, ZoneOffset.UTC);
            return Optional.of(z.toInstant());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * GLL: Zeit in Feld 5, Gültigkeit in Feld 6. Kein Datum — Datum von UTC-Systemuhr (NTP).
     */
    static Optional<Instant> parseGllUtc(String line, boolean requireFix) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String t = line.trim();
        if (!isGllLine(t)) {
            return Optional.empty();
        }
        int star = t.indexOf('*');
        if (star > 0) {
            t = t.substring(0, star);
        }
        String[] f = t.split(",", -1);
        if (f.length < 7) {
            return Optional.empty();
        }
        if (requireFix && !"A".equals(f[6])) {
            return Optional.empty();
        }
        if (f[1].isEmpty() || f[2].isEmpty() || f[3].isEmpty() || f[4].isEmpty()) {
            return Optional.empty();
        }
        String time = f[5];
        if (time.isEmpty() || time.length() < 6) {
            return Optional.empty();
        }
        try {
            int h = Integer.parseInt(time.substring(0, 2));
            int min = Integer.parseInt(time.substring(2, 4));
            int sec = Integer.parseInt(time.substring(4, 6));
            LocalDate d = LocalDate.now(ZoneOffset.UTC);
            LocalTime lt = LocalTime.of(h, min, sec);
            return Optional.of(ZonedDateTime.of(d, lt, ZoneOffset.UTC).toInstant());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * GGA: Uhrzeit in Feld 1 auch ohne Positions-Fix (Qualität 0). Datum von UTC-Tag.
     */
    static Optional<Instant> parseGgaUtc(String line) {
        if (line == null || line.isBlank() || !isGgaLine(line.trim())) {
            return Optional.empty();
        }
        String t = line.trim();
        int star = t.indexOf('*');
        if (star > 0) {
            t = t.substring(0, star);
        }
        String[] f = t.split(",", -1);
        if (f.length < 2 || f[1].isEmpty() || f[1].length() < 6) {
            return Optional.empty();
        }
        try {
            String time = f[1];
            int h = Integer.parseInt(time.substring(0, 2));
            int min = Integer.parseInt(time.substring(2, 4));
            int sec = Integer.parseInt(time.substring(4, 6));
            LocalDate d = LocalDate.now(ZoneOffset.UTC);
            LocalTime lt = LocalTime.of(h, min, sec);
            return Optional.of(ZonedDateTime.of(d, lt, ZoneOffset.UTC).toInstant());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Instant> getUtc() {
        String d = detail.get();
        // Bewusst erlaubte Demo-Anzeige ohne Satelliten-Fix.
        if (d != null && d.startsWith("GPS-Uhr ohne Positions-Fix")) {
            return Optional.ofNullable(lastUtc.get());
        }
        if (d != null && (d.contains("kein GPS-Fix") || d.contains("ohne Fix")
                || d.contains("abgedeckt") || d.contains("Kein GPS-Fix")
                || d.contains("ohne Satelliten-Fix"))) {
            return Optional.empty();
        }
        return Optional.ofNullable(lastUtc.get());
    }

    @Override
    public String getDetailStatus() {
        return detail.get();
    }

    @Override
    public void close() {
        running = false;
        if (port != null && port.isOpen()) {
            port.closePort();
        }
        for (Thread t : new Thread[] {readerThread, watchdogThread}) {
            if (t != null) {
                try {
                    t.join(2000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
