# Zeitserver — Raspberry Pi mit NTP, GPS, DCF77 und RTC

Projektarbeit „Entwicklung eines Zeit-Servers" (Prof. Dr. Peter Birkner, htw saar).
Ein Raspberry Pi empfängt die Uhrzeit über **vier unabhängige Quellen** — NTP (Internet),
GPS (Satellit), DCF77 (Langwellen-Funk) und eine batteriegepufferte RTC (DS3231) —,
vergleicht sie laufend, erkennt fehlerhafte oder ausgefallene Quellen und bildet per
Median-Fusion eine robuste Referenzzeit. Ein Web-Dashboard visualisiert alles live.

Build-Stand: `1.0-abgabe-2026-08-07`

## Schnellstart

```bash
./setup-pi.sh          # baut das Jar, kopiert alles auf den Pi, startet das Dashboard
# Dashboard: http://<pi-adresse>:8080/
```

Voraussetzungen auf dem Mac: Java 17+, Maven, SSH-Zugang zum Pi.
Auf dem Pi: Raspberry Pi OS, `gpiod` (gpiomon), `i2c-tools`, Java 17+ (installiert das Skript bei Bedarf).

## Hardware

| Quelle | Anschluss |
|---|---|
| GPS (NMEA, u-blox) | UART `/dev/serial0`, 9600 Baud |
| DCF77-Empfänger | GPIO BCM 4 (Pin 7), Ferritantenne |
| RTC DS3231 | I2C Bus 1, Adresse 0x68 |
| NTP | WLAN/Internet, `pool.ntp.org` |

## Architektur (wichtigste Code-Pfade)

| Baustein | Datei |
|---|---|
| Einstieg/CLI | `zeitserver-java/src/main/java/de/htwsaar/zeitserver/Main.java` |
| NTP-Quelle | `.../source/NtpTimeSource.java` |
| GPS-Quelle (NMEA RMC/GLL) | `.../source/GpsSerialTimeSource.java` |
| DCF77-Impulserfassung | `.../source/Dcf77TimeSource.java` |
| DCF77-Telegramm-Codec | `.../source/Dcf77MinuteCodec.java` |
| RTC-Quelle | `.../source/RtcDs3231TimeSource.java` |
| Median-Fusion | `.../fusion/TimeFusion.java` |
| Failover (10-Min-Historie) | `.../fusion/FailoverResolver.java` |
| Webserver + API | `.../web/TimeWebServer.java` |
| Dashboard | `zeitserver-java/src/main/resources/web/` |
| Pi-Konfiguration | `zeitserver-java/src/main/resources/config.pi.properties` |

## Grundprinzip: ehrliche Quellen

Jede Quelle liefert **ausschließlich selbst empfangene Zeit** oder meldet sich als
ausgefallen (`keine Daten`). Es gibt keine stille Ersatzzeit aus anderen Quellen.
Konkret: GPS nur mit echtem Satelliten-Fix (`gps.requireFix=true`), DCF77 nur nach
paritätsgeprüfter Dekodierung, RTC nur bei erfolgreichem Chip-Zugriff. Veraltete Werte
verfallen automatisch (DCF77: 75 s, sonst wenige Sekunden). Fällt eine Quelle aus,
laufen die übrigen weiter; die Fusion erkennt und ignoriert Ausreißer.

## Dokumentierte Entwurfsentscheidungen

**DCF77-Polarität (`dcf77.activeLow=false`).** Empirisch per Zwei-Phasen-Messung an
gpiomon ermittelt: Bei invertierter Annahme wurden statt der 100/200-ms-Sekundenimpulse
die Pausen (≈0,8–3 s) vermessen — 0 gültige Bits. Mit korrekter Polarität zeigt das
Pulsbreiten-Histogramm die erwarteten Berge bei ~100 ms (Bit 0) und ~200 ms (Bit 1).

**Mindestbitzahl `MIN_GOOD_BITS = 22` (statt 35).** Abwägung Verfügbarkeit gegen
Strenge für Innenraum-Empfang (~20–40 von 59 Bits/Minute am Teststandort). Die Absicherung
gegen Fehldekodierung leisten drei nachgelagerte Prüfungen: die drei Paritätsbits des
DCF77-Telegramms, eine Mindest-Übereinstimmung der empfangenen Bits mit dem erwarteten
Rahmen (≥ 55 %) und eine Plausibilitätsschranke von ±5 s zur NTP-Systemzeit.

**±5-s-Plausibilität statt ±120 s.** Ein dekodiertes Telegramm, das mehr als 5 s von
der NTP-gestützten Systemzeit abweicht, wird verworfen. Das eliminiert die klassische
Off-by-one-Minute-Falle (DCF77 kodiert stets die *folgende* Minute) in allen
Dekodierpfaden; der Codec wählt die Zielminute zusätzlich explizit anhand der
Sekundenposition (Minutenende → Folgeminute).

**Weiterlaufende DCF77-Uhr.** Zwischen zwei Minuten-Dekodierungen läuft die zuletzt
dekodierte Funkzeit monoton weiter (Systemuhr als Taktgeber, max. 75 s), statt auf dem
Minutenanfang einzufrieren — dadurch nimmt DCF77 sinnvoll an der Fusion teil.

## Failover-Demonstration

1. **GPS**: Antennenstecker ziehen → nach Sekunden `Keine Daten`, übrige Quellen laufen
   weiter; nach dem Anstecken Fix in 1–3 Min. (Alufolie genügt nicht — nur eine rundum
   geschlossene Metallhülle dämpft 1,575 GHz zuverlässig.)
2. **DCF77**: Ferritstab senkrecht stellen oder in Nullrichtung (Längsachse Richtung
   Mainflingen) drehen → nach max. 75 s `Keine Daten`; zurückdrehen → nächste gute
   Minute bringt die Quelle zurück. (Alufolie ist bei 77,5 kHz physikalisch wirkungslos.)
3. **RTC**: SDA-Jumper abziehen → `Keine Daten`; anstecken → sofort zurück.

Das Dashboard zeigt Ausfälle pro Quelle als graue Lücken im Zeitstrahl.

## Wichtige Konfigurationsschalter (`config.pi.properties`)

```properties
gps.requireFix=true        # Zeit nur mit Satelliten-Fix (Status A)
dcf77.activeLow=false      # Polarität des Empfängers (empirisch ermittelt)
failover.minSourcesNormal=2
fusion.outlierSeconds=2.0  # Ausreißer-Schwelle der Median-Fusion
history.retentionMinutes=10
```

## Bekannte Grenzen

- DCF77-Innenraumempfang ist störempfindlich (Netzteile, Monitore, Einplatinenrechner);
  die Quelle pendelt bei Grenzempfang ehrlich zwischen aktiv und ausgefallen.
- Die RTC wird mangels Kernel-RTC-Treibers (`/dev/rtc0` nicht aktiviert) direkt per I2C
  gelesen; das automatische Rückschreiben der Systemzeit erfordert das einmalige
  Aktivieren des Overlays (`scripts/pi-enable-rtc.sh`) oder manuelles Stellen per `i2cset`.
- Nach jedem Kopieren des Jars auf den Pi ist ein Prozess-Neustart erforderlich
  (`pi-on-pi-start-web.sh`); ein Jar-Tausch unter laufender JVM führt zu
  `ClassNotFoundException` durch verschobene Zip-Offsets.
