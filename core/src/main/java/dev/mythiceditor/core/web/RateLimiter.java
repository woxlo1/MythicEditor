package dev.mythiceditor.core.web;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed-window per-key rate limiter (key = session token or remote IP). */
public final class RateLimiter {
    private record Window(AtomicLong windowStart, AtomicLong count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final long windowMillis;
    private final int limit;

    public RateLimiter(int limitPerMinute) {
        this.limit = limitPerMinute;
        this.windowMillis = 60_000L;
    }

    public boolean allow(String key) {
        long now = System.currentTimeMillis();
        // Keys can come from the network (client IPs): never let the map grow without bound.
        if (windows.size() > 50_000 && !windows.containsKey(key)) {
            sweep();
            if (windows.size() > 50_000) return false;
        }
        Window w = windows.computeIfAbsent(key, k -> new Window(new AtomicLong(now), new AtomicLong(0)));
        synchronized (w) {
            if (now - w.windowStart().get() >= windowMillis) {
                w.windowStart().set(now);
                w.count().set(0);
            }
            return w.count().incrementAndGet() <= limit;
        }
    }

    /** Periodic cleanup to stop the map growing forever with stale IPs. */
    public void sweep() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().windowStart().get() > windowMillis * 5);
    }
}
