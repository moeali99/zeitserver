package de.htwsaar.zeitserver.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class TimeHistoryStoreTest {

    @Test
    void historieWirdAufDenAktuellenZeitpunktFortgeschrieben() {
        var store = new TimeHistoryStore(Duration.ofMinutes(10));
        Instant start = Instant.parse("2026-08-07T12:00:00Z");
        // Quelle geht exakt 0,5 s vor; Messungen im Abstand von 60 s
        for (int i = 0; i < 5; i++) {
            Instant recordedAt = start.plusSeconds(60L * i);
            store.record("NTP", recordedAt, recordedAt.plusMillis(500));
        }
        Instant now = start.plusSeconds(300);

        List<Instant> projected = store.utcsInWindow("NTP", now);

        // Jeder alte Messwert entspricht "jetzt + 0,5 s" – nicht einem Zeitpunkt in der Vergangenheit
        projected.forEach(t -> assertEquals(now.plusMillis(500), t));
    }
}
