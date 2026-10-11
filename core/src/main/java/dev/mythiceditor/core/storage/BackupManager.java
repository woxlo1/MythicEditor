package dev.mythiceditor.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Writes a timestamped copy of a file before it is modified or deleted: {backupDir}/{yyyyMMdd-HHmmss-SSS}/{relative path}. */
public final class BackupManager {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path root;
    private final Path backupRoot;
    private final int retention;

    public BackupManager(Path realRoot, String backupDirName, int retention) {
        this.root = realRoot;
        Path b = realRoot.resolve(backupDirName == null || backupDirName.isBlank() ? "backups" : backupDirName).normalize();
        if (!b.startsWith(realRoot) || b.equals(realRoot)) {
            throw new IllegalArgumentException("storage.backup-dir must be a sub folder of the MythicMobs folder");
        }
        this.backupRoot = b;
        this.retention = retention;
    }

    public Path backupRoot() {
        return backupRoot;
    }

    /** @return the created backup file */
    public synchronized Path backup(Path original, byte[] content) throws IOException {
        String rel = root.relativize(original.toAbsolutePath().normalize()).toString();
        String stamp = FMT.format(LocalDateTime.now());
        Path dir = backupRoot.resolve(stamp);
        int n = 0;
        while (Files.exists(dir)) dir = backupRoot.resolve(stamp + "-" + (++n));
        Path target = dir.resolve(rel).normalize();
        if (!target.startsWith(backupRoot)) throw new IOException("Refusing to write backup outside of the backup folder");
        Files.createDirectories(target.getParent());
        Files.write(target, content);
        prune();
        return target;
    }

    public List<String> list(int limit) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(backupRoot)) return out;
        try (Stream<Path> s = Files.list(backupRoot)) {
            s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted(Comparator.reverseOrder()).limit(limit).forEach(out::add);
        }
        return out;
    }

    private void prune() {
        try (Stream<Path> s = Files.list(backupRoot)) {
            List<Path> sets = new ArrayList<>(s.filter(Files::isDirectory).sorted().toList());
            int excess = sets.size() - retention;
            for (int i = 0; i < excess; i++) deleteTree(sets.get(i));
        } catch (IOException ignored) {
            // pruning is best effort
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
