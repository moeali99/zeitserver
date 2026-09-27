package de.htwsaar.zeitserver.fusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.htwsaar.zeitserver.fusion.TimeFusion.SourceReading;

class TimeFusionTest {

    private static final Instant T = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void medianBeiUngeraderAnzahl() {
        var median = TimeFusion.medianUtc(List.of(T.plusSeconds(5), T, T.minusSeconds(3)));
        assertEquals(Optional.of(T), median);
    }

    @Test
    void medianBeiGeraderAnzahlIstMittelwertDerMittlerenWerte() {
        var median = TimeFusion.medianUtc(List.of(T, T.plusSeconds(2), T.plusMillis(500), T.plusSeconds(10)));
        assertEquals(Optional.of(T.plusMillis(1250)), median);
    }

    @Test
    void leereListeLiefertKeinenMedian() {
        assertTrue(TimeFusion.medianUtc(List.of()).isEmpty());
    }

    @Test
    void ausreisserWirdBeiDerFusionIgnoriert() {
        var readings = List.of(
                new SourceReading("NTP", Optional.of(T)),
                new SourceReading("GPS", Optional.of(T.plusMillis(20))),
                new SourceReading("RTC", Optional.of(T.minusMillis(40))),
                new SourceReading("DCF77", Optional.of(T.plusSeconds(3600))));  // eine Stunde daneben

        var fused = TimeFusion.fusedUtc(readings, 1.0);

        // Ohne Ausreisser bleiben NTP, GPS und RTC; deren Median ist T
        assertEquals(Optional.of(T), fused);
    }

    @Test
    void ausgefalleneQuellenWerdenUebersprungen() {
        var readings = List.of(
                new SourceReading("NTP", Optional.of(T)),
                new SourceReading("GPS", Optional.empty()),
                new SourceReading("DCF77", Optional.empty()));

        assertEquals(Optional.of(T), TimeFusion.fusedUtc(readings, 1.0));
    }

    @Test
    void ohneDatenGibtEsKeineFusion() {
        var readings = List.of(new SourceReading("GPS", Optional.empty()));
        assertTrue(TimeFusion.fusedUtc(readings, 1.0).isEmpty());
    }
}
