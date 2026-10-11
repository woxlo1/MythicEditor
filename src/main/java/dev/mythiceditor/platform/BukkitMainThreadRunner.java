package dev.mythiceditor.platform;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import dev.mythiceditor.core.web.MainThreadRunner;

/** Runs a task on Bukkit's main thread and completes the future with its result (or exception). */
public final class BukkitMainThreadRunner implements MainThreadRunner {
    private final Plugin plugin;

    public BukkitMainThreadRunner(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public <T> CompletableFuture<T> run(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (Bukkit.isPrimaryThread()) {
            complete(future, task);
            return future;
        }
        Bukkit.getScheduler().runTask(plugin, () -> complete(future, task));
        return future;
    }

    private static <T> void complete(CompletableFuture<T> future, Supplier<T> task) {
        try {
            future.complete(task.get());
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
    }
}
