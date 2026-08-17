package de.htwsaar.zeitserver.source;

import de.htwsaar.zeitserver.NetworkTimeClients;
import de.htwsaar.zeitserver.config.AppConfig;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Internet-Zeit per SNTP bei jeder Abfrage (oder letzter erfolgreicher Wert).
 */
public final class NtpTimeSource implements TimeSource {

    private final String host;
    private final int timeoutMs;
    private final AtomicReference<Instant> last = new AtomicReference<>();
    private final AtomicReference<String> lastError = new AtomicReference<>("noch nicht abgefragt");

    public NtpTimeSource(AppConfig cfg) {
        this.host = cfg.ntpHost();
        this.timeoutMs = cfg.ntpTimeoutMs();
    }

    @Override
    public String id() {
        return "NTP";
    }

    @Override
    public void start() {
        refresh();
    }

    /** Aktuelle SNTP-Abfrage; bei Fehler bleibt letzter guter Wert erhalten. */
    public void refresh() {
        try {
            Instant i = NetworkTimeClients.querySntpInstant(host, timeoutMs);
            last.set(i);
            lastError.set("OK, Server " + host);
        } catch (Exception e) {
            lastError.set(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Override
    public Optional<Instant> getUtc() {
        return Optional.ofNullable(last.get());
    }

    @Override
    public String getDetailStatus() {
        return lastError.get();
    }
}
