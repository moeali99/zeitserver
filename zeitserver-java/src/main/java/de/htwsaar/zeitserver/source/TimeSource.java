package de.htwsaar.zeitserver.source;

import java.time.Instant;
import java.util.Optional;

/**
 * Eine Zeitquelle (NTP, GPS, DCF77). Nach {@link #start()} im Hintergrund oder bei Abfrage aktualisieren.
 */
public interface TimeSource extends AutoCloseable {

    String id();

    /** Hintergrund-Threads (GPIO, UART, …) starten. */
    void start();

    /** Letzte gültige UTC-Zeit, falls vorhanden. */
    Optional<Instant> getUtc();

    /** Kurzer Status für die Konsole (z. B. Fehler, letzte Zeile NMEA). */
    String getDetailStatus();

    @Override
    default void close() {
        // optional
    }
}
