package de.htwsaar.zeitserver;

import de.htwsaar.zeitserver.config.AppConfig;
import de.htwsaar.zeitserver.monitor.TimeMonitor;
import de.htwsaar.zeitserver.web.TimeWebServer;
import de.htwsaar.zeitserver.source.Dcf77TimeSource;
import de.htwsaar.zeitserver.source.GpsSerialTimeSource;
import de.htwsaar.zeitserver.source.NtpTimeSource;
import de.htwsaar.zeitserver.source.RtcDs3231TimeSource;
import de.htwsaar.zeitserver.source.TimeSource;

import java.util.ArrayList;
import java.util.List;

/**
 * CLI: {@code monitor}, {@code once}, {@code sntp}, {@code daytime}.
 * Konfiguration extern: {@code -Dzeitserver.config=/pfad/zur/config.properties}
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }
        String cmd = args[0].toLowerCase();
        switch (cmd) {
            case "monitor" -> runMonitor(false);
            case "once" -> runMonitor(true);
            case "web" -> runWeb();
            case "sntp" -> {
                String host = args.length > 1 ? args[1] : "pool.ntp.org";
                System.out.println(NetworkTimeClients.querySntpUtc(host));
            }
            case "daytime" -> {
                String host = args.length > 1 ? args[1] : "time.nist.gov";
                System.out.println(NetworkTimeClients.fetchDaytime(host));
            }
            default -> printUsage();
        }
    }

    private static List<TimeSource> buildSources(AppConfig cfg) {
        List<TimeSource> list = new ArrayList<>();
        if (cfg.ntpEnabled()) {
            list.add(new NtpTimeSource(cfg));
        }
        if (cfg.gpsEnabled()) {
            list.add(new GpsSerialTimeSource(cfg));
        }
        if (cfg.dcf77Enabled()) {
            list.add(new Dcf77TimeSource(cfg));
        }
        if (cfg.rtcEnabled()) {
            list.add(new RtcDs3231TimeSource(cfg));
        }
        return list;
    }

    private static void runWeb() throws Exception {
        AppConfig cfg = AppConfig.loadDefault();
        List<TimeSource> sources = buildSources(cfg);
        if (sources.isEmpty()) {
            System.err.println("Keine Quelle aktiv — in config.properties mindestens ntp.enabled=true setzen.");
            return;
        }
        for (TimeSource s : sources) {
            s.start();
        }
        try {
            new TimeWebServer(cfg, sources).start();
        } finally {
            for (TimeSource s : sources) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void runMonitor(boolean singleShot) throws Exception {
        AppConfig cfg = AppConfig.loadDefault();
        List<TimeSource> sources = buildSources(cfg);
        if (sources.isEmpty()) {
            System.err.println("Keine Quelle aktiv — in config.properties mindestens ntp.enabled=true setzen.");
            return;
        }
        for (TimeSource s : sources) {
            s.start();
        }
        try {
            TimeMonitor mon = new TimeMonitor(cfg, sources);
            if (singleShot) {
                mon.runOnce();
            } else {
                mon.runLoop();
            }
        } finally {
            for (TimeSource s : sources) {
                try {
                    s.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void printUsage() {
        System.out.println("Zeitserver Projektarbeit (Java)");
        System.out.println();
        System.out.println("  java -jar zeitserver-1.0-SNAPSHOT-all.jar monitor");
        System.out.println("  java -jar ... web");
        System.out.println("  java -jar ... once");
        System.out.println("  java -jar ... sntp [host]");
        System.out.println("  java -jar ... daytime [host]");
        System.out.println();
        System.out.println("Konfiguration: classpath config.properties oder");
        System.out.println("  -Dzeitserver.config=/pfad/zur/eigene.properties");
        System.out.println();
        System.out.println("GPS: gps.enabled=true, gps.serial.port=/dev/ttyUSB0");
        System.out.println("DCF77 (nur Pi): dcf77.enabled=true, dcf77.gpio.bcm=4");
        System.out.println("RTC DS3231 (Pi I2C): rtc.enabled=true, rtc.i2c.address=0x68");
    }
}
