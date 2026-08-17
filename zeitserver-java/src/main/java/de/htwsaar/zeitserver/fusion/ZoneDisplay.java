package de.htwsaar.zeitserver.fusion;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;

/**
 * Sommer-/Winterzeit (MESZ/MEZ) für Anzeige und DCF77-Abgleich.
 */
public final class ZoneDisplay {

    public record DstInfo(
            String zoneId,
            boolean daylightSaving,
            String labelDe,
            String offsetLabel) {}

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private ZoneDisplay() {}

    public static DstInfo now() {
        return at(Instant.now());
    }

    public static DstInfo at(Instant instant) {
        ZonedDateTime z = instant.atZone(BERLIN);
        ZoneRules rules = BERLIN.getRules();
        boolean dst = rules.isDaylightSavings(instant);
        String label = dst ? "Sommerzeit (MESZ / CEST)" : "Winterzeit (MEZ / CET)";
        String offset = z.getOffset().getId();
        return new DstInfo(BERLIN.getId(), dst, label, offset);
    }
}
