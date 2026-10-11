package dev.mythiceditor.core.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * One-time tickets (from /mythiceditor) exchanged for a browser session cookie, plus session bookkeeping
 * (sliding idle timeout, hard lifetime cap, per-player session limit, quit-triggered invalidation).
 */
public final class SessionManager {
    public record Ticket(String token, UUID playerId, String playerName, long expiresAt, boolean singleUse, java.util.concurrent.atomic.AtomicBoolean used) {}

    public record Session(String token, UUID playerId, String playerName, long createdAt, long maxExpiresAt, java.util.concurrent.atomic.AtomicLong lastSeen, String csrf) {
        public boolean expired(long now, long idleMillis) {
            return now > maxExpiresAt || now - lastSeen.get() > idleMillis;
        }
    }

    private static final SecureRandom RNG = new SecureRandom();

    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final int idleMinutes;
    private final int maxLifetimeMinutes;
    private final int ticketTtlSeconds;
    private final boolean singleUseTicket;
    private final int maxPerPlayer;
    private final ScheduledFuture<?> sweeper;

    public SessionManager(ScheduledExecutorService exec, int idleMinutes, int maxLifetimeMinutes, int ticketTtlSeconds, boolean singleUseTicket, int maxPerPlayer) {
        this.idleMinutes = idleMinutes;
        this.maxLifetimeMinutes = maxLifetimeMinutes;
        this.ticketTtlSeconds = ticketTtlSeconds;
        this.singleUseTicket = singleUseTicket;
        this.maxPerPlayer = maxPerPlayer;
        this.sweeper = exec.scheduleWithFixedDelay(this::sweep, 1, 1, TimeUnit.MINUTES);
    }

    public static String randomToken() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public Ticket issueTicket(UUID playerId, String playerName) {
        String token = randomToken();
        Ticket t = new Ticket(token, playerId, playerName, System.currentTimeMillis() + ticketTtlSeconds * 1000L, singleUseTicket, new java.util.concurrent.atomic.AtomicBoolean(false));
        tickets.put(token, t);
        return t;
    }

    /** Consumes the ticket (if single-use) and returns a fresh session, or null if the ticket is invalid/expired. */
    public Session redeem(String token) {
        Ticket t = tickets.get(token);
        if (t == null) return null;
        if (System.currentTimeMillis() > t.expiresAt()) {
            tickets.remove(token);
            return null;
        }
        if (t.singleUse() && !t.used().compareAndSet(false, true)) return null;
        if (!t.singleUse()) tickets.remove(token);

        enforcePerPlayerLimit(t.playerId());
        Session s = new Session(randomToken(), t.playerId(), t.playerName(), System.currentTimeMillis(),
            System.currentTimeMillis() + maxLifetimeMinutes * 60_000L, new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()), randomToken());
        sessions.put(s.token(), s);
        return s;
    }

    public Session touch(String token) {
        Session s = sessions.get(token);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        if (s.expired(now, idleMinutes * 60_000L)) {
            sessions.remove(token);
            return null;
        }
        s.lastSeen().set(now);
        return s;
    }

    public void invalidate(String token) {
        sessions.remove(token);
    }

    public void invalidateForPlayer(UUID playerId) {
        sessions.entrySet().removeIf(e -> e.getValue().playerId().equals(playerId));
        // Links that were issued but not opened yet must not outlive the player's visit either.
        tickets.values().removeIf(t -> t.playerId().equals(playerId));
    }

    /**
     * A short public identifier for a session, safe to show to admins (chat, /api/admin/sessions).
     * It is a hash prefix, so it reveals nothing about the secret session token itself.
     */
    public static String publicId(String token) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public java.util.List<Session> sessionsFor(UUID playerId) {
        return sessions.values().stream().filter(s -> s.playerId().equals(playerId)).toList();
    }

    public java.util.List<Session> allSessions() {
        return java.util.List.copyOf(sessions.values());
    }

    public int activeCount() {
        return sessions.size();
    }

    private void enforcePerPlayerLimit(UUID playerId) {
        var mine = sessions.entrySet().stream().filter(e -> e.getValue().playerId().equals(playerId))
            .sorted(Map.Entry.comparingByValue((a, b) -> Long.compare(a.lastSeen().get(), b.lastSeen().get()))).toList();
        int excess = mine.size() - (maxPerPlayer - 1);
        for (int i = 0; i < excess; i++) sessions.remove(mine.get(i).getKey());
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        sessions.values().removeIf(s -> s.expired(now, idleMinutes * 60_000L));
        tickets.values().removeIf(t -> now > t.expiresAt());
    }

    public void close() {
        sweeper.cancel(false);
    }
}
