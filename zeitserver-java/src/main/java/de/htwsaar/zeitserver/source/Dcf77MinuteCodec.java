package de.htwsaar.zeitserver.source;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Dekodiert eine vollständige DCF77-Minute (Bits der Sekunden 1–59) nach
 * <a href="https://en.wikipedia.org/wiki/DCF77">DCF77</a>. Zeitangabe = mitteleuropäische Zivilzeit
 * ({@link ZoneId#of(String) Europe/Berlin}), Ausgabe als UTC-{@link Instant}.
 * <p>
 * {@code secondBits[s]} = Datenbit, das in Sekunde {@code s} übertragen wurde ({@code s = 1 … 59}).
 */
public final class Dcf77MinuteCodec {

    /** Dekodierte Minute inkl. DCF77-Sommer-/Winterzeit-Bits (Sek. 17/18). */
    public record DecodeResult(
            Instant utc,
            boolean summerTimeBit,
            boolean winterTimeBit,
            String dstHintDe) {}

    private Dcf77MinuteCodec() {}

    public static Optional<Instant> decode(boolean[] secondBits) {
        return decodeWithInfo(secondBits).map(DecodeResult::utc);
    }

    /**
     * Baut eine gültige DCF-Minute aus Referenzzeit (z. B. NTP) und vergleicht mit GPIO-Bits.
     * Hilft bei schwachem Empfang, wenn Parität auf dem rohen GPIO-Array scheitert.
     */
    public static Optional<DecodeResult> decodeWithNtpAssist(boolean[] gpioBits, ZonedDateTime ref) {
        if (gpioBits == null || gpioBits.length < 59) {
            return Optional.empty();
        }
        ZonedDateTime refSec = ref.withSecond(0).withNano(0);
        for (int shift = -6; shift <= 6; shift++) {
            boolean[] g = shiftSeconds(gpioBits, shift);
            for (int dm = 0; dm <= 0; dm++) {
                // DCF77 kodiert die Zeit der FOLGE-Minute: nahe Minutenende die naechste Minute waehlen.
                ZonedDateTime candidate = ref.getSecond() >= 30 ? refSec.plusMinutes(1) : refSec;
                boolean[] frame = encodeMinuteFrame(candidate);
                frame[17] = g[17];
                frame[18] = g[18];
                int agree = agreement(g, frame, 21, 58);
                Integer gpioMin = readMinuteRaw(g);
                Integer gpioHour = readHourRaw(g);
                boolean timePlausible = gpioMin != null
                        && gpioHour != null
                        && gpioMin == candidate.getMinute()
                        && gpioHour == candidate.getHour();
                if (timePlausible || agree >= 8) {
                    Optional<DecodeResult> decoded = decodeWithInfo(frame);
                    if (decoded.isPresent()) {
                        return decoded;
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Vollständige DCF-Minute (Sek. 1–59) für {@link ZoneId#of(String) Europe/Berlin}. */
    public static boolean[] encodeMinuteFrame(ZonedDateTime civil) {
        boolean[] b = new boolean[60];
        var c = civil.toLocalDateTime();
        boolean summer = civil.getZone().getRules().isDaylightSavings(civil.toInstant());
        b[17] = summer;
        b[18] = !summer && !b[17];

        setBcdLsb(b, 21, 4, c.getMinute() % 10);
        setBcdLsb(b, 25, 3, c.getMinute() / 10);
        setEvenParityBit(b, 21, 28);

        setBcdLsb(b, 29, 4, c.getHour() % 10);
        setBcdLsb(b, 33, 2, c.getHour() / 10);
        setEvenParityBit(b, 29, 35);

        setBcdLsb(b, 36, 4, c.getDayOfMonth() % 10);
        setBcdLsb(b, 40, 2, c.getDayOfMonth() / 10);
        setBcdLsb(b, 42, 3, c.getDayOfWeek().getValue());
        setBcdLsb(b, 45, 4, c.getMonthValue() % 10);
        b[49] = c.getMonthValue() >= 10;
        int year2 = c.getYear() % 100;
        setBcdLsb(b, 50, 4, year2 % 10);
        setBcdLsb(b, 54, 4, year2 / 10);
        setEvenParityBit(b, 36, 58);
        return b;
    }

    public static Optional<DecodeResult> decodeWithInfo(boolean[] secondBits) {
        if (secondBits == null || secondBits.length < 59) {
            return Optional.empty();
        }
        if (!evenParity(secondBits, 21, 28)) {
            return Optional.empty();
        }
        if (!evenParity(secondBits, 29, 35)) {
            return Optional.empty();
        }
        if (!evenParity(secondBits, 36, 58)) {
            return Optional.empty();
        }

        int minuteOnes = bitsLsbFirst(secondBits, 21, 4);
        int minuteTens = bitsLsbFirst(secondBits, 25, 3);
        int minute = minuteOnes + 10 * minuteTens;
        if (minute < 0 || minute > 59) {
            return Optional.empty();
        }

        int hourOnes = bitsLsbFirst(secondBits, 29, 4);
        int hourTens = bitsLsbFirst(secondBits, 33, 2);
        int hour = hourOnes + 10 * hourTens;
        if (hour < 0 || hour > 23) {
            return Optional.empty();
        }

        int dayOnes = bitsLsbFirst(secondBits, 36, 4);
        int dayTens = bitsLsbFirst(secondBits, 40, 2);
        int day = dayOnes + 10 * dayTens;
        if (day < 1 || day > 31) {
            return Optional.empty();
        }

        int wday = bitsLsbFirst(secondBits, 42, 3);
        if (wday < 1 || wday > 7) {
            return Optional.empty();
        }

        int monthOnes = bitsLsbFirst(secondBits, 45, 4);
        int monthTens = secondBits[49] ? 1 : 0;
        int month = monthOnes + 10 * monthTens;
        if (month < 1 || month > 12) {
            return Optional.empty();
        }

        int yearOnes = bitsLsbFirst(secondBits, 50, 4);
        int yearTens = bitsLsbFirst(secondBits, 54, 4);
        int year2 = yearOnes + 10 * yearTens;
        int year = 2000 + year2;

        LocalDateTime civil;
        try {
            civil = LocalDateTime.of(year, month, day, hour, minute, 0);
        } catch (Exception e) {
            return Optional.empty();
        }

        // Plausibilität Wochentag (DCF: Montag = 1) — bei Rauschen oft falsch, nur Hinweis
        int javaDow = civil.getDayOfWeek().getValue();
        boolean weekdayMismatch = javaDow != wday;

        boolean summerBit = secondBits.length > 17 && secondBits[17];
        boolean winterBit = secondBits.length > 18 && secondBits[18];
        String dstHint = dstHintDe(summerBit, winterBit);
        if (weekdayMismatch) {
            dstHint = dstHint + " (Wochentag im Signal abweichend)";
        }

        ZoneId berlin = ZoneId.of("Europe/Berlin");
        ZonedDateTime z = civil.atZone(berlin);
        return Optional.of(new DecodeResult(z.toInstant(), summerBit, winterBit, dstHint));
    }

    private static String dstHintDe(boolean summerBit, boolean winterBit) {
        if (summerBit && !winterBit) {
            return "DCF77: Sommerzeit (MESZ) signalisiert";
        }
        if (winterBit && !summerBit) {
            return "DCF77: Winterzeit (MEZ) signalisiert";
        }
        if (summerBit && winterBit) {
            return "DCF77: DST-Umschalt-Hinweis (beide Bits)";
        }
        return "DCF77: DST-Bits leer — Zone Europe/Berlin aus Systemregeln";
    }

    private static boolean evenParity(boolean[] b, int from, int to) {
        int c = 0;
        for (int i = from; i <= to; i++) {
            if (i < 0 || i >= b.length) {
                return false;
            }
            if (b[i]) {
                c++;
            }
        }
        return c % 2 == 0;
    }

    /** LSB zuerst: {@code start} … {@code start + count - 1}. */
    private static int bitsLsbFirst(boolean[] b, int start, int count) {
        int v = 0;
        for (int i = 0; i < count; i++) {
            int idx = start + i;
            if (idx < 0 || idx >= b.length) {
                return -1;
            }
            if (b[idx]) {
                v |= (1 << i);
            }
        }
        return v;
    }

    private static Integer readMinuteRaw(boolean[] b) {
        int ones = bitsLsbFirst(b, 21, 4);
        int tens = bitsLsbFirst(b, 25, 3);
        if (ones < 0 || tens < 0) {
            return null;
        }
        int m = ones + 10 * tens;
        return m <= 59 ? m : null;
    }

    private static Integer readHourRaw(boolean[] b) {
        int ones = bitsLsbFirst(b, 29, 4);
        int tens = bitsLsbFirst(b, 33, 2);
        if (ones < 0 || tens < 0) {
            return null;
        }
        int h = ones + 10 * tens;
        return h <= 23 ? h : null;
    }

    private static void setBcdLsb(boolean[] b, int start, int count, int value) {
        for (int i = 0; i < count; i++) {
            b[start + i] = ((value >> i) & 1) == 1;
        }
    }

    private static void setEvenParityBit(boolean[] b, int from, int parityIdx) {
        int ones = 0;
        for (int i = from; i < parityIdx; i++) {
            if (b[i]) {
                ones++;
            }
        }
        b[parityIdx] = (ones % 2) != 0;
    }

    private static boolean[] shiftSeconds(boolean[] bits, int shift) {
        if (shift == 0) {
            return bits;
        }
        boolean[] out = new boolean[bits.length];
        for (int s = 1; s <= 59; s++) {
            int src = s + shift;
            if (src >= 1 && src <= 59) {
                out[s] = bits[src];
            }
        }
        return out;
    }

    private static int agreement(boolean[] a, boolean[] b, int from, int to) {
        int n = 0;
        for (int i = from; i <= to; i++) {
            if (a[i] == b[i]) {
                n++;
            }
        }
        return n;
    }
}
