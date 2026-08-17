package de.htwsaar.zeitserver.source;

import de.htwsaar.zeitserver.config.AppConfig;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** DS3231 RTC: lesen (sysfs/hwclock/I2C) und optional Systemzeit synchronisieren. */
public final class RtcDs3231TimeSource implements TimeSource {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final Pattern HWCLOCK_LOCAL =
            Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})");
    private static final Pattern I2C_HEX = Pattern.compile("0x([0-9a-fA-F]{2})");

    private final int i2cAddress;
    private final int[] i2cBuses;
    private final Path sysfsEpoch;
    private final boolean syncFromSystem;
    private final long syncIntervalMs;
    private final AtomicInteger activeBus = new AtomicInteger(-1);
    private final AtomicReference<Instant> lastUtc = new AtomicReference<>();
    private final AtomicReference<String> detail = new AtomicReference<>("Gestartet …");
    private volatile boolean running;
    private volatile long lastSyncAttemptMs;
    private Thread pollThread;

    public RtcDs3231TimeSource(AppConfig cfg) {
        this.i2cAddress = cfg.rtcI2cAddress();
        this.i2cBuses = cfg.rtcI2cBusCandidates();
        this.sysfsEpoch = Path.of(cfg.rtcSysfsPath());
        this.syncFromSystem = cfg.rtcSyncFromSystem();
        this.syncIntervalMs = cfg.rtcSyncIntervalMinutes() * 60_000L;
    }

    @Override
    public String id() {
        return "RTC";
    }

    @Override
    public void start() {
        running = true;
        maybeSyncFromSystem(true);
        refresh();
        pollThread = new Thread(this::pollLoop, "rtc-ds3231");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        while (running) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            maybeSyncFromSystem(false);
            refresh();
        }
    }

    private void maybeSyncFromSystem(boolean force) {
        if (!syncFromSystem) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastSyncAttemptMs < syncIntervalMs) {
            return;
        }
        lastSyncAttemptMs = now;
        if (tryHwclockWrite()) {
            detail.set("Systemzeit → RTC geschrieben (hwclock -w)");
        }
    }

    private boolean tryHwclockWrite() {
        for (String[] cmd : new String[][] {
                {"/usr/sbin/hwclock", "-w", "-f", "/dev/rtc0"},
                {"/usr/sbin/hwclock", "-w"},
                {"hwclock", "-w", "-f", "/dev/rtc0"},
                {"hwclock", "-w"}
        }) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                if (p.waitFor() == 0) {
                    return true;
                }
            } catch (Exception ignored) {
                // root oder /dev/rtc0 fehlt
            }
        }
        return false;
    }

    private void refresh() {
        Optional<Instant> utc = readSysfs().or(this::readHwclock).or(this::readI2c);
        if (utc.isPresent()) {
            lastUtc.set(utc.get());
            return;
        }
        // Fehlerdetail nur setzen, wenn wirklich keine Zeit — sonst bleibt alte
        // Erfolgsmeldung + Zeit stehen (kein „nicht lesbar“ trotz angezeigter Uhr).
        if (lastUtc.get() == null) {
            if (!Files.exists(Path.of("/dev/i2c-1"))) {
                detail.set("I2C deaktiviert — einmalig: sudo bash ~/zeitserver/pi-enable-rtc.sh && sudo reboot");
            } else {
                detail.set("RTC kurz nicht lesbar — i2cdetect -y 1 (0x68)");
            }
        }
    }

    private Optional<Instant> readI2c() {
        for (int bus : i2cBuses) {
            Optional<Instant> utc = readI2cOnBus(bus);
            if (utc.isPresent()) {
                activeBus.set(bus);
                return utc;
            }
        }
        return Optional.empty();
    }

    private Optional<Instant> readSysfs() {
        try {
            if (!Files.isReadable(sysfsEpoch)) {
                return Optional.empty();
            }
            long sec = Long.parseLong(Files.readString(sysfsEpoch).trim());
            detail.set("DS3231 sysfs → " + Instant.ofEpochSecond(sec));
            return Optional.of(Instant.ofEpochSecond(sec));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Instant> readHwclock() {
        try {
            for (String[] cmd : new String[][] {
                    {"/usr/sbin/hwclock", "-r", "-f", "/dev/rtc0"},
                    {"hwclock", "-r"}
            }) {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out = readProcess(p);
                if (p.waitFor() != 0) {
                    continue;
                }
                Matcher m = HWCLOCK_LOCAL.matcher(out);
                if (!m.find()) {
                    continue;
                }
                LocalDateTime ldt = LocalDateTime.of(
                        Integer.parseInt(m.group(1)),
                        Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)),
                        Integer.parseInt(m.group(4)),
                        Integer.parseInt(m.group(5)),
                        Integer.parseInt(m.group(6)));
                detail.set("hwclock -r → " + ldt);
                return Optional.of(ldt.atZone(BERLIN).toInstant());
            }
            return Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Instant> readI2cOnBus(int bus) {
        Optional<Instant> python = readI2cPython(bus);
        if (python.isPresent()) {
            return python;
        }
        try {
            int secReg = readI2cReg(bus, 0x00);
            if (secReg < 0) {
                return Optional.empty();
            }
            if ((secReg & 0x80) != 0) {
                detail.set("RTC Bus " + bus + " angehalten — hwclock -w nötig (sudo)");
                return Optional.empty();
            }
            int minReg = readI2cReg(bus, 0x01);
            int hourReg = readI2cReg(bus, 0x02);
            int dateReg = readI2cReg(bus, 0x04);
            int monthReg = readI2cReg(bus, 0x05);
            int yearReg = readI2cReg(bus, 0x06);
            if (minReg < 0 || hourReg < 0 || dateReg < 0 || monthReg < 0 || yearReg < 0) {
                return Optional.empty();
            }

            int sec = bcd(secReg & 0x7F);
            int min = bcd(minReg & 0x7F);
            int hour = bcd(hourReg & 0x3F);
            int day = bcd(dateReg & 0x3F);
            int month = bcd(monthReg & 0x1F);
            int year = 2000 + bcd(yearReg);
            if ((monthReg & 0x80) != 0) {
                year += 100;
            }

            LocalDateTime ldt = LocalDateTime.of(year, month, day, hour, min, sec);
            // DS3231 speichert UTC (nicht Ortszeit) — sonst MESZ-Offset (~2 h) falsch.
            detail.set(String.format(
                    "I2C Bus %d / 0x%02x (UTC) → %02d.%02d.%04d %02d:%02d:%02dZ",
                    bus, i2cAddress, day, month, year, hour, min, sec));
            return Optional.of(ldt.atZone(ZoneOffset.UTC).toInstant());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Instant> readI2cPython(int bus) {
        String[] scripts = {
                System.getenv("HOME") + "/zeitserver/rtc-i2c-read.py",
                "rtc-i2c-read.py"
        };
        for (String script : scripts) {
            if (script == null || script.isBlank()) {
                continue;
            }
            try {
                Process p = new ProcessBuilder(
                        "python3", script,
                        "--bus", Integer.toString(bus),
                        "--addr", String.format("0x%02x", i2cAddress))
                        .redirectErrorStream(true)
                        .start();
                String out = readProcess(p).trim();
                if (p.waitFor() != 0 || out.startsWith("ERR:")) {
                    continue;
                }
                Instant utc = Instant.parse(out);
                detail.set("Python I2C Bus " + bus + " / 0x" + Integer.toHexString(i2cAddress)
                        + " → " + utc);
                return Optional.of(utc);
            } catch (Exception ignored) {
                // i2cget oder python3 fehlt / Bus nicht vorhanden
            }
        }
        return Optional.empty();
    }

    private int readI2cReg(int bus, int reg) {
        try {
            Process p = new ProcessBuilder(
                    "/usr/sbin/i2cget", "-y", Integer.toString(bus),
                    String.format("0x%02x", i2cAddress),
                    String.format("0x%02x", reg))
                    .redirectErrorStream(true)
                    .start();
            String out = readProcess(p);
            if (p.waitFor() != 0) {
                // Fallback: PATH
                p = new ProcessBuilder(
                        "i2cget", "-y", Integer.toString(bus),
                        String.format("0x%02x", i2cAddress),
                        String.format("0x%02x", reg))
                        .redirectErrorStream(true)
                        .start();
                out = readProcess(p);
                if (p.waitFor() != 0) {
                    return -1;
                }
            }
            Matcher m = I2C_HEX.matcher(out.trim());
            if (!m.find()) {
                return -1;
            }
            return Integer.parseInt(m.group(1), 16);
        } catch (Exception e) {
            return -1;
        }
    }

    private static int bcd(int v) {
        return (v & 0x0F) + ((v >> 4) & 0x0F) * 10;
    }

    private static String readProcess(Process p) throws Exception {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (!sb.isEmpty()) {
                    sb.append(' ');
                }
                sb.append(line);
            }
            return sb.toString();
        }
    }

    @Override
    public Optional<Instant> getUtc() {
        return Optional.ofNullable(lastUtc.get());
    }

    @Override
    public String getDetailStatus() {
        return detail.get();
    }

    @Override
    public void close() {
        running = false;
        if (pollThread != null) {
            try {
                pollThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
