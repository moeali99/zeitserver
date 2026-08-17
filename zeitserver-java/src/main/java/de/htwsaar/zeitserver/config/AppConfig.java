package de.htwsaar.zeitserver.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Lädt {@code config.properties} aus dem Classpath, optional überschrieben durch
 * System-Property {@code zeitserver.config} (Pfad zu einer externen Datei).
 */
public final class AppConfig {

    private final Properties p;

    public AppConfig(Properties p) {
        this.p = p;
    }

    public static AppConfig loadDefault() throws IOException {
        Properties props = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                props.load(in);
            }
        }
        String external = System.getProperty("zeitserver.config");
        if (external != null && !external.isBlank()) {
            Path path = Path.of(external.trim());
            if (Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                }
            }
        }
        return new AppConfig(props);
    }

    public boolean ntpEnabled() {
        return bool("ntp.enabled", true);
    }

    public String ntpHost() {
        return p.getProperty("ntp.host", "pool.ntp.org");
    }

    public int ntpTimeoutMs() {
        return parseInt("ntp.timeoutMs", 4000);
    }

    public boolean gpsEnabled() {
        return bool("gps.enabled", false);
    }

    public String gpsSerialPort() {
        return p.getProperty("gps.serial.port", "/dev/ttyUSB0");
    }

    public int gpsBaudRate() {
        return parseInt("gps.baudRate", 9600);
    }

    /** Nur RMC mit Status {@code A} (Fix). {@code false} = Zeit auch ohne Fix anzeigen. */
    public boolean gpsRequireFix() {
        return bool("gps.requireFix", true);
    }

    public boolean rtcEnabled() {
        return bool("rtc.enabled", false);
    }

    public int rtcI2cBus() {
        return parseInt("rtc.i2c.bus", 1);
    }

    public int rtcI2cAddress() {
        String hex = p.getProperty("rtc.i2c.address", "0x68").trim();
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            try {
                return Integer.parseInt(hex.substring(2), 16);
            } catch (NumberFormatException e) {
                return 0x68;
            }
        }
        return parseInt("rtc.i2c.address", 0x68);
    }

    public String rtcSysfsPath() {
        return p.getProperty("rtc.sysfs.epoch", "/sys/class/rtc/rtc0/since_epoch");
    }

    /** Systemzeit (NTP) in den DS3231 schreiben — benötigt root (hwclock -w). */
    public boolean rtcSyncFromSystem() {
        return bool("rtc.syncFromSystem", true);
    }

    public int rtcSyncIntervalMinutes() {
        return parseInt("rtc.syncIntervalMinutes", 5);
    }

    /** I2C-Busse zum Ausprobieren (Komma-Liste), Standard: 1,2,0 */
    public int[] rtcI2cBusCandidates() {
        String raw = p.getProperty("rtc.i2c.busCandidates", "1,2,0");
        String[] parts = raw.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                out[i] = 1;
            }
        }
        return out;
    }

    public boolean dcf77Enabled() {
        return bool("dcf77.enabled", false);
    }

    public int dcf77GpioBcm() {
        return parseInt("dcf77.gpio.bcm", 4);
    }

    public boolean dcf77PullUp() {
        return bool("dcf77.pullUp", true);
    }

    public boolean dcf77ActiveLow() {
        return bool("dcf77.activeLow", true);
    }

    public int dcf77SyncPulseMinMs() {
        return parseInt("dcf77.syncPulseMinMs", 700);
    }

    public int dcf77Bit0LowMinMs() {
        return parseInt("dcf77.bit0LowMinMs", 70);
    }

    public int dcf77Bit0LowMaxMs() {
        return parseInt("dcf77.bit0LowMaxMs", 130);
    }

    public int dcf77Bit1LowMinMs() {
        return parseInt("dcf77.bit1LowMinMs", 170);
    }

    public int dcf77Bit1LowMaxMs() {
        return parseInt("dcf77.bit1LowMaxMs", 230);
    }

    public long monitorIntervalMs() {
        return parseLong("monitor.intervalMs", 2000L);
    }

    public boolean monitorClearScreen() {
        return bool("monitor.clearScreen", false);
    }

    public String statusJsonPath() {
        return p.getProperty("status.json.path", "zeitserver-status.json");
    }

    public double fusionOutlierSeconds() {
        return parseDouble("fusion.outlierSeconds", 2.0);
    }

    /** Abweichung ≤ dieser Schwelle (Sekunden) gilt als „passt zur Referenz“. */
    public double accuracyOkSeconds() {
        return parseDouble("accuracy.okSeconds", 0.1);
    }

    public String webHost() {
        return p.getProperty("web.host", "0.0.0.0");
    }

    public int webPort() {
        return parseInt("web.port", 8080);
    }

    public int historyRetentionMinutes() {
        return parseInt("history.retentionMinutes", 10);
    }

    public boolean failoverEnabled() {
        return bool("failover.enabled", true);
    }

    public int failoverMinSourcesNormal() {
        return parseInt("failover.minSourcesNormal", 2);
    }

    public boolean metricsEnabled() {
        return bool("metrics.enabled", true);
    }

    private boolean bool(String key, boolean def) {
        if (!p.containsKey(key)) {
            return def;
        }
        return Boolean.parseBoolean(p.getProperty(key).trim());
    }

    private int parseInt(String key, int def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private long parseLong(String key, long def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double parseDouble(String key, double def) {
        String v = p.getProperty(key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
