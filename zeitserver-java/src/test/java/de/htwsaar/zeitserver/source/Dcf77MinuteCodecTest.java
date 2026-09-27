package de.htwsaar.zeitserver.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Dcf77MinuteCodecTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-01-15T08:42",   // Winterzeit
            "2026-07-01T23:59",   // Sommerzeit, letzte Minute des Tages
            "2026-12-31T00:00",   // Jahresende, Monat >= 10
            "2029-02-28T12:07",
    })
    void encodeUndDecodeErgebenDieselbeMinute(String localDateTime) {
        ZonedDateTime civil = java.time.LocalDateTime.parse(localDateTime).atZone(BERLIN);

        boolean[] frame = Dcf77MinuteCodec.encodeMinuteFrame(civil);
        var decoded = Dcf77MinuteCodec.decodeWithInfo(frame);

        assertTrue(decoded.isPresent(), "gueltiger Rahmen muss dekodierbar sein");
        assertEquals(civil.toInstant(), decoded.get().utc());
        assertEquals(BERLIN.getRules().isDaylightSavings(civil.toInstant()), decoded.get().summerTimeBit());
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 30, 40, 55})
    void einzelnesGekipptesBitWirdPerParitaetVerworfen(int bit) {
        boolean[] frame = Dcf77MinuteCodec.encodeMinuteFrame(ZonedDateTime.of(2026, 3, 10, 14, 25, 0, 0, BERLIN));
        frame[bit] = !frame[bit];

        assertFalse(Dcf77MinuteCodec.decode(frame).isPresent());
    }

    @Test
    void zuKurzeOderFehlendeRahmenWerdenAbgelehnt() {
        assertFalse(Dcf77MinuteCodec.decode(null).isPresent());
        assertFalse(Dcf77MinuteCodec.decode(new boolean[30]).isPresent());
    }

    @Test
    void ungueltigerTagWirdTrotzKorrekterParitaetAbgelehnt() {
        // Nur Nullen: Paritaet stimmt, aber Tag 0 / Monat 0 ist kein Datum
        assertFalse(Dcf77MinuteCodec.decode(new boolean[60]).isPresent());
    }
}
