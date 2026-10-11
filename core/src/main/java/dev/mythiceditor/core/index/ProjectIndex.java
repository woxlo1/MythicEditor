package dev.mythiceditor.core.index;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

import dev.mythiceditor.core.security.PathGuard;
import dev.mythiceditor.core.yaml.YamlBlockParser;

/**
 * In-memory index of every entry (Skill, Mob, Item, Droptable) below the configured MythicMobs folders.
 * The file system is only touched for files whose size/mtime changed, so completion, search and the graph
 * never re-parse the whole tree per request.
 */
public final class ProjectIndex {
    public record BlockInfo(String kind, String id, String file, int line, int endLine, String hash, RefScanner.Refs refs) {}

    public record FileInfo(String rel, String kind, long size, long mtime, String hash, String error, List<BlockInfo> blocks, List<String> problems) {}

    public record Change(String file, String action) {}

    public record Page(int total, List<BlockInfo> items) {}

    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private final ProjectLayout layout;
    private final PathGuard guard;
    private final RefScanner scanner;
    private final Map<String, FileInfo> files = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong(1);
    private final List<Consumer<List<Change>>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile Derived derived = new Derived(Map.of(), Map.of());
    private volatile long derivedFor = 0;
    private volatile long lastScanMillis = 0;
    private volatile long lastScanDuration = 0;

    private record Derived(Map<String, Map<String, List<BlockInfo>>> byKindId, Map<String, List<BlockInfo>> sortedByKind) {}

    public ProjectIndex(ProjectLayout layout, PathGuard guard, RefScanner scanner) {
        this.layout = layout;
        this.guard = guard;
        this.scanner = scanner;
    }

    public void addListener(Consumer<List<Change>> l) {
        listeners.add(l);
    }

    public long version() {
        return version.get();
    }

    public long lastScanMillis() {
        return lastScanMillis;
    }

    public long lastScanDuration() {
        return lastScanDuration;
    }

    // ---- scanning --------------------------------------------------------------------------------

    /** Incremental scan: only files with changed size or mtime are re-parsed. Returns the detected changes. */
    public synchronized List<Change> scan() {
        long t0 = System.nanoTime();
        List<Change> changes = new ArrayList<>();
        Map<String, Path> onDisk = new LinkedHashMap<>();
        Map<String, String> kindByRel = new HashMap<>();
        for (Map.Entry<String, List<Path>> e : layout.resolveAll().entrySet()) {
            for (Path dir : e.getValue()) {
                try (Stream<Path> walk = Files.walk(dir, 16)) {
                    walk.filter(p -> isYaml(p) && !hidden(dir, p)).forEach(p -> {
                        String rel = guard.relativize(p);
                        if (!onDisk.containsKey(rel)) {
                            onDisk.put(rel, p);
                            kindByRel.put(rel, e.getKey());
                        }
                    });
                } catch (IOException | RuntimeException ex) {
                    // directory vanished or unreadable during scan
                }
            }
        }
        for (Map.Entry<String, Path> e : onDisk.entrySet()) {
            String rel = e.getKey();
            FileInfo old = files.get(rel);
            try {
                BasicFileAttributes a = Files.readAttributes(e.getValue(), BasicFileAttributes.class);
                if (old != null && old.size() == a.size() && old.mtime() == a.lastModifiedTime().toMillis() && old.kind().equals(kindByRel.get(rel))) continue;
                FileInfo fresh = parseFile(e.getValue(), rel, kindByRel.get(rel));
                files.put(rel, fresh);
                if (old == null || !old.hash().equals(fresh.hash())) changes.add(new Change(rel, old == null ? "created" : "modified"));
            } catch (IOException ex) {
                // file disappeared between walk and read; next scan will notice
            }
        }
        for (String rel : new ArrayList<>(files.keySet())) {
            if (!onDisk.containsKey(rel)) {
                files.remove(rel);
                changes.add(new Change(rel, "deleted"));
            }
        }
        lastScanMillis = System.currentTimeMillis();
        lastScanDuration = (System.nanoTime() - t0) / 1_000_000;
        if (!changes.isEmpty()) {
            version.incrementAndGet();
            fire(changes);
        }
        return changes;
    }

    /** Re-parse a single file right now (used after our own writes so the index is never stale). */
    public synchronized FileInfo refreshFile(Path file, String kind) throws IOException {
        String rel = guard.relativize(file);
        FileInfo old = files.get(rel);
        if (!Files.exists(file)) {
            if (old != null) {
                files.remove(rel);
                version.incrementAndGet();
            }
            return null;
        }
        FileInfo fresh = parseFile(file, rel, kind);
        files.put(rel, fresh);
        if (old == null || !old.hash().equals(fresh.hash())) version.incrementAndGet();
        return fresh;
    }

    private FileInfo parseFile(Path p, String rel, String kind) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
        if (a.size() > MAX_FILE_BYTES) {
            return new FileInfo(rel, kind, a.size(), a.lastModifiedTime().toMillis(), "", "File too large (over 8 MB)", List.of(), List.of());
        }
        byte[] bytes = Files.readAllBytes(p);
        String hash = sha256(bytes);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new FileInfo(rel, kind, a.size(), a.lastModifiedTime().toMillis(), hash, "File is not valid UTF-8", List.of(), List.of());
        }
        YamlBlockParser.Parsed parsed = YamlBlockParser.parse(text);
        List<BlockInfo> blocks = new ArrayList<>(parsed.blocks().size());
        for (YamlBlockParser.Block b : parsed.blocks()) {
            String blockText = text.substring(b.start(), b.end());
            blocks.add(new BlockInfo(kind, b.id(), rel, b.startLine() + 1, b.endLine() + 1, blockHash(blockText), scanner.scan(blockText)));
        }
        return new FileInfo(rel, kind, a.size(), a.lastModifiedTime().toMillis(), hash, null, blocks, parsed.problems());
    }

    private static boolean isYaml(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return (n.endsWith(".yml") || n.endsWith(".yaml")) && Files.isRegularFile(p);
    }

    private static boolean hidden(Path base, Path p) {
        for (Path part : base.relativize(p)) if (part.toString().startsWith(".")) return true;
        return false;
    }

    private void fire(List<Change> changes) {
        for (Consumer<List<Change>> l : listeners) {
            try {
                l.accept(changes);
            } catch (RuntimeException ignored) {
                // listener bugs must never break indexing
            }
        }
    }

    // ---- queries -----------------------------------------------------------------------------------

    private Derived derived() {
        long v = version.get();
        Derived d = derived;
        if (derivedFor == v && d != null) return d;
        synchronized (this) {
            v = version.get();
            if (derivedFor == v) return derived;
            Map<String, Map<String, List<BlockInfo>>> byKindId = new HashMap<>();
            Map<String, List<BlockInfo>> sorted = new HashMap<>();
            for (FileInfo f : files.values()) {
                for (BlockInfo b : f.blocks()) {
                    byKindId.computeIfAbsent(b.kind(), k -> new HashMap<>()).computeIfAbsent(b.id(), k -> new ArrayList<>()).add(b);
                    sorted.computeIfAbsent(b.kind(), k -> new ArrayList<>()).add(b);
                }
            }
            Comparator<BlockInfo> cmp = Comparator.comparing((BlockInfo b) -> b.id().toLowerCase(Locale.ROOT)).thenComparing(BlockInfo::file);
            for (List<BlockInfo> l : sorted.values()) l.sort(cmp);
            derived = new Derived(byKindId, sorted);
            derivedFor = v;
            return derived;
        }
    }

    public List<BlockInfo> find(String kind, String id) {
        return derived().byKindId().getOrDefault(kind, Map.of()).getOrDefault(id, List.of());
    }

    public List<BlockInfo> all(String kind) {
        return derived().sortedByKind().getOrDefault(kind, List.of());
    }

    public int count(String kind) {
        return all(kind).size();
    }

    public Page search(String kind, String query, String fileFilter, int limit, int offset) {
        List<BlockInfo> base = all(kind);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<BlockInfo> matched;
        if (q.isEmpty() && (fileFilter == null || fileFilter.isEmpty())) {
            matched = base;
        } else {
            List<Scored> scored = new ArrayList<>();
            for (BlockInfo b : base) {
                if (fileFilter != null && !fileFilter.isEmpty() && !b.file().equals(fileFilter)) continue;
                int s = q.isEmpty() ? 1 : score(q, b);
                if (s > 0) scored.add(new Scored(b, s));
            }
            scored.sort(Comparator.comparingInt((Scored s) -> -s.score).thenComparing(s -> s.b.id().toLowerCase(Locale.ROOT)));
            matched = new ArrayList<>(scored.size());
            for (Scored s : scored) matched.add(s.b);
        }
        int from = Math.min(Math.max(0, offset), matched.size());
        int to = Math.min(matched.size(), from + Math.max(1, limit));
        return new Page(matched.size(), matched.subList(from, to));
    }

    private record Scored(BlockInfo b, int score) {}

    private static int score(String q, BlockInfo b) {
        String id = b.id().toLowerCase(Locale.ROOT);
        if (id.equals(q)) return 1000;
        if (id.startsWith(q)) return 900 - Math.min(id.length() - q.length(), 100);
        int idx = id.indexOf(q);
        if (idx >= 0) return 700 - Math.min(idx, 100);
        if (b.file().toLowerCase(Locale.ROOT).contains(q)) return 300;
        // subsequence (fuzzy)
        int qi = 0;
        for (int i = 0; i < id.length() && qi < q.length(); i++) if (id.charAt(i) == q.charAt(qi)) qi++;
        return qi == q.length() ? 400 - Math.min(id.length(), 100) : 0;
    }

    public List<FileInfo> files(String kind) {
        List<FileInfo> out = new ArrayList<>();
        for (FileInfo f : files.values()) if (kind == null || f.kind().equals(kind)) out.add(f);
        out.sort(Comparator.comparing(FileInfo::rel));
        return out;
    }

    public FileInfo file(String rel) {
        return files.get(rel);
    }

    // ---- hashing helpers --------------------------------------------------------------------------

    public static String blockHash(String blockText) {
        return sha256(YamlBlockParser.canonical(blockText).getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
