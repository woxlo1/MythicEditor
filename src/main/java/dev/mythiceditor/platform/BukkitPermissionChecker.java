package dev.mythiceditor.platform;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import dev.mythiceditor.core.web.MainThreadRunner;
import dev.mythiceditor.core.web.PermissionChecker;

/**
 * Checks permissions against the live Bukkit player.
 *
 * <p>The Bukkit API is not thread-safe, but this is called from the HTTP worker threads, so the lookup is hopped
 * onto the main thread and the answer is cached for a couple of seconds (a page load makes many API calls).
 * Offline players and players unknown to the server have NO permissions - not even OPs: a web session of someone
 * who has left the server must not keep working. Anything that goes wrong (timeout, error) answers "no".
 */
public final class BukkitPermissionChecker implements PermissionChecker {
    private record Cached(boolean value, long expiresAt) {}

    private static final long CACHE_MS = 2_000;
    private static final long WAIT_MS = 2_000;

    private final MainThreadRunner mainThread;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public BukkitPermissionChecker(MainThreadRunner mainThread) {
        this.mainThread = mainThread;
    }

    @Override
    public boolean has(UUID playerId, String permission) {
        if (playerId == null || permission == null) return false;
        if (Bukkit.isPrimaryThread()) return lookup(playerId, permission);

        String key = playerId + "|" + permission;
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && c.expiresAt() > now) return c.value();
        try {
            boolean v = mainThread.run(() -> lookup(playerId, permission)).get(WAIT_MS, TimeUnit.MILLISECONDS);
            cache.put(key, new Cached(v, now + CACHE_MS));
            if (cache.size() > 2_000) cache.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
            return v;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    private static boolean lookup(UUID playerId, String permission) {
        Player p = Bukkit.getPlayer(playerId);
        return p != null && p.hasPermission(permission);
    }
}
