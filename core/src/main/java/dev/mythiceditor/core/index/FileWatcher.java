package dev.mythiceditor.core.index;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Watches the content folders and asks the index to rescan (debounced). The periodic rescan is the safety
 * net for platforms/file systems where watch events are unreliable (network shares, some containers).
 */
public final class FileWatcher implements AutoCloseable {
    private final ProjectLayout layout;
    private final ProjectIndex index;
    private final Logger log;
    private final boolean watch;
    private final int rescanSeconds;
    private final ScheduledExecutorService exec;
    private final Object lock = new Object();
    private final Set<Path> registered = new HashSet<>();
    private volatile WatchService service;
    private volatile boolean closed;
    private ScheduledFuture<?> pending;
    private Thread thread;

    public FileWatcher(ProjectLayout layout, ProjectIndex index, boolean watch, int rescanSeconds, Logger log) {
        this.layout = layout;
        this.index = index;
        this.watch = watch;
        this.rescanSeconds = rescanSeconds;
        this.log = log;
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MythicEditor-Index");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        exec.execute(this::rescan);
        if (rescanSeconds > 0) {
            exec.scheduleWithFixedDelay(this::rescan, rescanSeconds, rescanSeconds, TimeUnit.SECONDS);
        }
        if (watch) {
            try {
                service = FileSystems.getDefault().newWatchService();
            } catch (IOException e) {
                log.warning("File watching unavailable, falling back to periodic rescans: " + e.getMessage());
                return;
            }
            thread = new Thread(this::loop, "MythicEditor-Watch");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Synchronous rescan (used at startup by callers that need a ready index). */
    public List<ProjectIndex.Change> rescanNow() {
        List<ProjectIndex.Change> c = index.scan();
        registerAll();
        return c;
    }

    private void rescan() {
        if (closed) return;
        try {
            index.scan();
            registerAll();
        } catch (RuntimeException e) {
            log.warning("Index rescan failed: " + e.getMessage());
        }
    }

    private void registerAll() {
        WatchService ws = service;
        if (ws == null) return;
        synchronized (lock) {
            register(ws, layout.root());
            for (List<Path> dirs : layout.resolveAll().values()) {
                for (Path d : dirs) {
                    try (Stream<Path> walk = Files.walk(d, 16)) {
                        walk.filter(Files::isDirectory).forEach(p -> register(ws, p));
                    } catch (IOException | RuntimeException ignored) {
                        // directory removed while walking
                    }
                }
            }
            // packs directory (new packs appear here)
            Path packs = layout.root().resolve("Packs");
            if (Files.isDirectory(packs)) register(ws, packs);
        }
    }

    private void register(WatchService ws, Path dir) {
        if (registered.contains(dir)) return;
        try {
            dir.register(ws, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            registered.add(dir);
        } catch (IOException | RuntimeException ignored) {
            // cannot watch this directory; periodic rescan still covers it
        }
    }

    private void loop() {
        WatchService ws = service;
        while (!closed && ws != null) {
            try {
                WatchKey key = ws.take();
                boolean relevant = false;
                for (var ev : key.pollEvents()) {
                    Object ctx = ev.context();
                    if (ctx instanceof Path p) {
                        String n = p.getFileName().toString().toLowerCase();
                        if (n.endsWith(".yml") || n.endsWith(".yaml") || !n.contains(".")) relevant = true;
                    } else {
                        relevant = true;
                    }
                }
                if (!key.reset()) {
                    synchronized (lock) {
                        registered.remove((Path) key.watchable());
                    }
                }
                if (relevant) scheduleRescan();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
        }
    }

    private synchronized void scheduleRescan() {
        if (closed) return;
        if (pending != null && !pending.isDone()) return; // already scheduled; debounce window collects further events
        pending = exec.schedule(this::rescan, 300, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        closed = true;
        WatchService ws = service;
        if (ws != null) {
            try {
                ws.close();
            } catch (IOException ignored) {
                // shutting down
            }
        }
        exec.shutdownNow();
        if (thread != null) thread.interrupt();
    }
}
