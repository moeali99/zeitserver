package de.htwsaar.zeitserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/** SNTP (RFC 4330) und Daytime (RFC 867). */
public final class NetworkTimeClients {

    private static final long NTP_DELTA = 2208988800L;

    private NetworkTimeClients() {}

    /** SNTP, UDP Port 123 → UTC. */
    public static ZonedDateTime querySntpUtc(String host, int timeoutMs) throws IOException {
        byte[] buf = new byte[48];
        buf[0] = 0x23;

        int received;
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(timeoutMs);
            InetAddress addr = InetAddress.getByName(host);
            socket.send(new DatagramPacket(buf, buf.length, addr, 123));
            DatagramPacket in = new DatagramPacket(buf, buf.length);
            socket.receive(in);
            received = in.getLength();
        }

        if (received < 48) {
            throw new IOException("NTP response too short");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf);
        int txSeconds = bb.getInt(40);
        int txFraction = bb.getInt(44);
        if (txSeconds == 0) {
            throw new IOException("Invalid NTP transmit time");
        }
        long unixSeconds = (txSeconds & 0xFFFF_FFFFL) - NTP_DELTA;
        double frac = (txFraction & 0xFFFF_FFFFL) / (double) (1L << 32);
        long nanos = Math.round(frac * 1_000_000_000L);
        return Instant.ofEpochSecond(unixSeconds, nanos).atZone(ZoneOffset.UTC);
    }

    public static ZonedDateTime querySntpUtc(String host) throws IOException {
        return querySntpUtc(host, 5000);
    }

    public static Instant querySntpInstant(String host, int timeoutMs) throws IOException {
        return querySntpUtc(host, timeoutMs).toInstant();
    }

    /** Daytime-Protokoll, TCP Port 13. */
    public static String fetchDaytime(String host, int port, int timeoutMs) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            var reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(line);
            }
            return sb.toString().trim();
        }
    }

    public static String fetchDaytime(String host) throws IOException {
        return fetchDaytime(host, 13, 10_000);
    }
}
