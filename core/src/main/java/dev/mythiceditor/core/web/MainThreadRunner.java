package dev.mythiceditor.core.web;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Schedules work onto the server's main thread (Bukkit/Paper API must never be touched from HTTP worker threads). */
@FunctionalInterface
public interface MainThreadRunner {
    <T> CompletableFuture<T> run(Supplier<T> task);
}
