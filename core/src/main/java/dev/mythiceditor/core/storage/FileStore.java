package dev.mythiceditor.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

import dev.mythiceditor.core.Kinds;
import dev.mythiceditor.core.audit.ChangeLog;
import dev.mythiceditor.core.config.EditorSettings;
import dev.mythiceditor.core.index.ProjectIndex;
import dev.mythiceditor.core.index.ProjectLayout;
import dev.mythiceditor.core.security.PathGuard;
import dev.mythiceditor.core.yaml.YamlBlockParser;
import dev.mythiceditor.core.yaml.YamlValidator;

/**
 * All writes go through here. An entry (Skill/Mob) is replaced by splicing its text into the freshly re-read
 * file, so every byte outside the entry (comments, unknown keys, other entries, line endings) is preserved.
 * Flow: lock file -> re-read -> conflict check -> splice -> validate -> backup -> temp file -> atomic move -> verify -> reindex.
 */
public final class FileStore {
    public record Entry(String kind, String id, String file, int line, int endLine, String text, String hash, String fileHash, long mtime, List<String> warnings) {
        public Entry withWarnings(List<String> w) {
            return new Entry(kind, id, file, line, endLine, text, hash, fileHash, mtime, w);
        }
    }

    private static final int MAX_ENTRY_CHARS = 512 * 1024;

    private final EditorSettings settings;
    private final ProjectLayout layout;
    private final PathGuard guard;
    private final ProjectIndex index;
    private final BackupManager backups;
    private final ChangeLog changes;
    private final Logger log;
    private final Map<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    public FileStore(EditorSettings settings, ProjectLayout layout, PathGuard guard, ProjectIndex index, BackupManager backups, ChangeLog changes, Logger log) {
        this.settings = settings;
        this.layout = layout;
        this.guard = guard;
        this.index = index;
        this.backups = backups;
        this.changes = changes;
        this.log = log;
    }

    // ---- read ---------------------------------------------------------------------------------------

    public Entry read(String kind, String id) throws StoreException {
        ProjectIndex.BlockInfo info = locate(kind, id);
        Path path = resolveIndexed(kind, info.file());
        Loaded l = load(path);
        YamlBlockParser.Block b = uniqueBlock(l.parsed, id, info.file());
        String text = l.raw.substring(b.start(), b.end());
        if (!ProjectIndex.sha256(l.bytes).equals(index.file(info.file()) == null ? "" : index.file(info.file()).hash())) {
            try {
                index.refreshFile(path, kind);
            } catch (IOException e) {
                log.fine("Index refresh failed: " + e.getMessage());
            }
        }
        return entry(kind, id, info.file(), l, b, text, List.of());
    }

    // ---- create -------------------------------------------------------------------------------------

    public Entry create(String kind, String id, String fileRel, String text, String actor) throws StoreException {
        requireEditable(kind);
        requireId(id);
        if (!index.find(kind, id).isEmpty()) {
            throw new StoreException("exists", 409, capitalize(kind) + " '" + id + "' already exists in " + index.find(kind, id).get(0).file());
        }
        String rel = (fileRel == null || fileRel.isBlank()) ? defaultFile(kind) : fileRel;
        Path path = resolveWritable(kind, rel);
        String body = text == null || text.isBlank() ? template(kind, id) : text;
        body = normalizeInput(body);
        checkSize(body);
        YamlValidator.Result vr = YamlValidator.validateBlock(body, id);
        if (!vr.ok()) throw invalid(vr);

        ReentrantLock lock = lockFor(path);
        lock.lock();
        try {
            byte[] originalBytes = Files.exists(path) ? Files.readAllBytes(path) : null;
            String raw = originalBytes == null ? "" : decode(originalBytes);
            YamlBlockParser.Parsed parsed = YamlBlockParser.parse(raw);
            for (YamlBlockParser.Block b : parsed.blocks()) {
                if (b.id().equals(id)) throw new StoreException("exists", 409, "'" + id + "' already exists in " + rel);
            }
            String eol = parsed.eol();
            StringBuilder sb = new StringBuilder(raw);
            if (!raw.isEmpty()) {
                if (!raw.endsWith("\n")) sb.append(eol);
                if (!endsWithBlankLine(raw)) sb.append(eol);
            }
            sb.append(toEol(body, eol)).append(eol);
            String newRaw = sb.toString();
            List<String> warnings = validateWhole(raw, newRaw, originalBytes != null);
            commit(path, originalBytes, newRaw, kind);
            changes.add(actor, "create", kind, id, rel);
            return read(kind, id).withWarnings(warnings);
        } catch (IOException e) {
            throw io("create", e);
        } finally {
            lock.unlock();
        }
    }

    // ---- update -------------------------------------------------------------------------------------

    /** @param newText full text of the entry; its top-level key may differ from {@code id} to rename it */
    public Entry save(String kind, String id, String newText, String baseHash, boolean force, String actor) throws StoreException {
        requireEditable(kind);
        ProjectIndex.BlockInfo info = locate(kind, id);
        String body = normalizeInput(newText);
        checkSize(body);
        String newId = topLevelKey(body);
        if (newId == null) throw new StoreException("invalid", 422, "The entry must start with its name followed by a colon");
        requireId(newId);
        YamlValidator.Result vr = YamlValidator.validateBlock(body, newId);
        if (!vr.ok()) throw invalid(vr);
        if (!newId.equals(id)) {
            if (!index.find(kind, newId).isEmpty()) throw new StoreException("exists", 409, capitalize(kind) + " '" + newId + "' already exists");
        }

        Path path = resolveIndexed(kind, info.file());
        ReentrantLock lock = lockFor(path);
        lock.lock();
        try {
            Loaded l = load(path);
            YamlBlockParser.Block b = uniqueBlock(l.parsed, id, info.file());
            String current = l.raw.substring(b.start(), b.end());
            String currentHash = ProjectIndex.blockHash(current);
            if (!force && (baseHash == null || !baseHash.equals(currentHash))) {
                throw conflict(kind, id, info.file(), current, currentHash);
            }
            if (!newId.equals(id)) {
                for (YamlBlockParser.Block o : l.parsed.blocks()) {
                    if (o.id().equals(newId)) throw new StoreException("exists", 409, "'" + newId + "' already exists in " + info.file());
                }
            }
            String replacement = toEol(body, l.parsed.eol());
            String newRaw = l.raw.substring(0, b.start()) + replacement + l.raw.substring(b.end());
            List<String> warnings = validateWhole(l.raw, newRaw, true);
            if (newRaw.equals(l.raw)) {
                return entry(kind, id, info.file(), l, b, current, warnings);
            }
            commit(path, l.bytes, newRaw, kind);
            changes.add(actor, newId.equals(id) ? "update" : "rename", kind, newId, info.file());
            return read(kind, newId).withWarnings(warnings);
        } catch (IOException e) {
            throw io("save", e);
        } finally {
            lock.unlock();
        }
    }

    // ---- whole file ---------------------------------------------------------------------------------

    /** The complete text of one YAML file (as opposed to {@link Entry}, which is a single Skill/Mob block). */
    public record FileDoc(String file, String kind, String text, String hash, long mtime, int entries, List<String> warnings) {
        public FileDoc withWarnings(List<String> w) {
            return new FileDoc(file, kind, text, hash, mtime, entries, w);
        }
    }

    private static final int MAX_FILE_CHARS = 4 * 1024 * 1024;

    /** Reads a whole file that belongs to one of the configured content folders. */
    public FileDoc readFile(String rel) throws StoreException {
        String kind = kindOfFile(rel);
        Path path = resolveAnyIndexed(rel);
        if (!Files.isRegularFile(path)) throw new StoreException("not_found", 404, "File '" + rel + "' was not found");
        Loaded l = load(path);
        return fileDoc(rel, kind, path, l, List.of());
    }

    /**
     * Replaces the complete content of an existing file. Same safety net as entry edits (conflict check against the
     * file hash, YAML validation, backup, atomic write, read-back verification, re-index) but nothing is spliced:
     * the editor's text becomes the file. Line endings of the original file are kept.
     */
    public FileDoc saveFile(String rel, String newText, String baseHash, boolean force, String actor) throws StoreException {
        String kind = kindOfFile(rel);
        requireEditable(kind);
        Path path = resolveAnyIndexed(rel);
        String body = normalizeFileInput(newText);
        if (body.length() > MAX_FILE_CHARS) throw new StoreException("invalid", 413, "File is too large");
        ReentrantLock lock = lockFor(path);
        lock.lock();
        try {
            if (!Files.isRegularFile(path)) throw new StoreException("not_found", 404, "File '" + rel + "' was not found");
            Loaded l = load(path);
            String currentHash = ProjectIndex.sha256(l.bytes);
            if (!force && (baseHash == null || !baseHash.equals(currentHash))) {
                throw fileConflict(rel, kind, l.raw, currentHash);
            }
            String eol = l.parsed.eol();
            String newRaw = toEol(body, eol);
            List<String> warnings = validateWhole(l.raw, newRaw, true);
            if (newRaw.equals(l.raw)) return fileDoc(rel, kind, path, l, warnings);
            commit(path, l.bytes, newRaw, kind);
            changes.add(actor, "update-file", kind, null, rel);
            return readFile(rel).withWarnings(warnings);
        } catch (IOException e) {
            throw io("save file", e);
        } finally {
            lock.unlock();
        }
    }

    private FileDoc fileDoc(String rel, String kind, Path path, Loaded l, List<String> warnings) {
        long mtime = 0;
        try {
            mtime = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | RuntimeException ignored) {
            // informational only
        }
        return new FileDoc(rel, kind, l.raw.replace("\r\n", "\n"), ProjectIndex.sha256(l.bytes), mtime, l.parsed.blocks().size(), warnings);
    }

    private String kindOfFile(String rel) throws StoreException {
        ProjectIndex.FileInfo f = index.file(rel);
        if (f == null) throw new StoreException("not_found", 404, "File '" + rel + "' is not part of the indexed MythicMobs content");
        return f.kind();
    }

    private Path resolveAnyIndexed(String rel) throws StoreException {
        List<Path> dirs = new java.util.ArrayList<>();
        for (List<Path> d : layout.resolveAll().values()) dirs.addAll(d);
        try {
            return guard.resolveYamlFile(rel, dirs);
        } catch (SecurityException e) {
            throw new StoreException("forbidden", 403, e.getMessage());
        }
    }

    private static String normalizeFileInput(String text) throws StoreException {
        if (text == null) throw new StoreException("invalid", 422, "Missing text");
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.indexOf('\0') >= 0) throw new StoreException("invalid", 422, "Text contains NUL characters");
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        // exactly one trailing newline, like every well-formed text file (an empty file stays empty)
        int end = t.length();
        while (end > 0 && (t.charAt(end - 1) == '\n' || t.charAt(end - 1) == ' ' || t.charAt(end - 1) == '\t')) end--;
        return end == 0 ? "" : t.substring(0, end) + "\n";
    }

    private StoreException fileConflict(String rel, String kind, String serverText, String serverHash) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("text", serverText.replace("\r\n", "\n"));
        server.put("hash", serverHash);
        server.put("file", rel);
        return new StoreException("conflict", 409, "File changed externally. '" + rel + "' was modified on the server after you opened it.",
            null, Map.of("server", server));
    }

    // ---- delete -------------------------------------------------------------------------------------

    public void delete(String kind, String id, String baseHash, boolean force, String actor) throws StoreException {
        requireEditable(kind);
        ProjectIndex.BlockInfo info = locate(kind, id);
        Path path = resolveIndexed(kind, info.file());
        ReentrantLock lock = lockFor(path);
        lock.lock();
        try {
            Loaded l = load(path);
            YamlBlockParser.Block b = uniqueBlock(l.parsed, id, info.file());
            String current = l.raw.substring(b.start(), b.end());
            String currentHash = ProjectIndex.blockHash(current);
            if (!force && (baseHash == null || !baseHash.equals(currentHash))) throw conflict(kind, id, info.file(), current, currentHash);
            int end = b.end();
            if (end < l.raw.length() && l.raw.charAt(end) == '\r') end++;
            if (end < l.raw.length() && l.raw.charAt(end) == '\n') end++;
            String newRaw = l.raw.substring(0, b.start()) + l.raw.substring(end);
            List<String> ignored = validateWhole(l.raw, newRaw, true);
            commit(path, l.bytes, newRaw, kind);
            changes.add(actor, "delete", kind, id, info.file());
            if (!ignored.isEmpty()) log.fine("Delete warnings: " + ignored);
        } catch (IOException e) {
            throw io("delete", e);
        } finally {
            lock.unlock();
        }
    }

    public Entry duplicate(String kind, String id, String newId, String fileRel, String actor) throws StoreException {
        Entry src = read(kind, id);
        requireId(newId);
        String text = src.text();
        int nl = text.indexOf('\n');
        String rest = nl < 0 ? "" : text.substring(nl);
        String header = text.substring(0, nl < 0 ? text.length() : nl);
        int colon = header.indexOf(':');
        String afterColon = colon < 0 ? ":" : header.substring(colon);
        return create(kind, newId, fileRel == null || fileRel.isBlank() ? src.file() : fileRel, newId + afterColon + rest, actor);
    }

    // ---- internals ----------------------------------------------------------------------------------

    private record Loaded(byte[] bytes, String raw, YamlBlockParser.Parsed parsed) {}

    private Loaded load(Path path) throws StoreException {
        try {
            byte[] bytes = Files.readAllBytes(path);
            String raw = decode(bytes);
            return new Loaded(bytes, raw, YamlBlockParser.parse(raw));
        } catch (IOException e) {
            throw io("read", e);
        }
    }

    private static String decode(byte[] bytes) throws StoreException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new StoreException("invalid", 422, "The file is not valid UTF-8; refusing to touch it");
        }
    }

    private ProjectIndex.BlockInfo locate(String kind, String id) throws StoreException {
        List<ProjectIndex.BlockInfo> found = index.find(kind, id);
        if (found.isEmpty()) throw new StoreException("not_found", 404, capitalize(kind) + " '" + id + "' was not found");
        if (found.size() > 1) {
            throw new StoreException("ambiguous", 409, "'" + id + "' is defined more than once (" + found.get(0).file() + ", " + found.get(1).file() + "); resolve the duplicate first");
        }
        return found.get(0);
    }

    private YamlBlockParser.Block uniqueBlock(YamlBlockParser.Parsed p, String id, String file) throws StoreException {
        YamlBlockParser.Block hit = null;
        for (YamlBlockParser.Block b : p.blocks()) {
            if (b.id().equals(id)) {
                if (hit != null) throw new StoreException("ambiguous", 409, "'" + id + "' appears more than once in " + file);
                hit = b;
            }
        }
        if (hit == null) {
            throw new StoreException("conflict", 409, "'" + id + "' no longer exists in " + file + " (changed outside of the editor)", null, Map.of("gone", true));
        }
        return hit;
    }

    private Path resolveIndexed(String kind, String rel) throws StoreException {
        try {
            return guard.resolveYamlFile(rel, layout.dirsFor(kind));
        } catch (SecurityException e) {
            throw new StoreException("forbidden", 403, e.getMessage());
        }
    }

    private Path resolveWritable(String kind, String rel) throws StoreException {
        try {
            return guard.resolveYamlFile(rel, layout.writableDirsFor(kind));
        } catch (SecurityException e) {
            throw new StoreException("forbidden", 403, e.getMessage());
        }
    }

    private void requireEditable(String kind) throws StoreException {
        if (!settings.isEditable(kind)) throw new StoreException("readonly", 403, "Editing " + kind + " entries is disabled in config.yml");
    }

    private static void requireId(String id) throws StoreException {
        if (!Kinds.validId(id)) {
            throw new StoreException("invalid", 422, "Invalid name '" + (id == null ? "" : id.length() > 40 ? id.substring(0, 40) + "…" : id)
                + "'. Use letters, digits, '_', '-' or '.' (max 64 characters).");
        }
    }

    private static void checkSize(String text) throws StoreException {
        if (text.length() > MAX_ENTRY_CHARS) throw new StoreException("invalid", 413, "Entry is too large");
    }

    private StoreException invalid(YamlValidator.Result vr) {
        String where = vr.line() > 0 ? " (line " + vr.line() + (vr.column() > 0 ? ", column " + vr.column() : "") + ")" : "";
        return new StoreException("invalid_yaml", 422, "Invalid YAML: " + vr.message() + where, null,
            Map.of("line", vr.line(), "column", vr.column()));
    }

    private StoreException conflict(String kind, String id, String file, String serverText, String serverHash) {
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("text", serverText);
        server.put("hash", serverHash);
        server.put("file", file);
        return new StoreException("conflict", 409, "File changed externally. The " + kind + " '" + id + "' was modified on the server after you opened it.",
            null, Map.of("server", server));
    }

    private StoreException io(String op, IOException e) {
        log.warning("Failed to " + op + " entry: " + e);
        return new StoreException("io", 500, "The file could not be written (" + e.getClass().getSimpleName() + "). See the server log.", e, Map.of());
    }

    private List<String> validateWhole(String before, String after, boolean existed) throws StoreException {
        YamlValidator.Result now = YamlValidator.validateFile(after);
        if (now.ok()) return List.of();
        if (existed && YamlValidator.validateFile(before).ok()) {
            throw new StoreException("invalid_yaml", 422, "Refusing to save: the resulting file would not be valid YAML (" + now.message() + ")");
        }
        return List.of("The file already contained YAML errors outside this entry: " + now.message()
            + (now.line() > 0 ? " (line " + now.line() + ")" : ""));
    }

    private String defaultFile(String kind) {
        return kind.equals(Kinds.MOB) ? settings.defaultMobFile : settings.defaultSkillFile;
    }

    private static String template(String kind, String id) {
        if (kind.equals(Kinds.MOB)) {
            return id + ":\n  Type: ZOMBIE\n  Display: '&a" + id + "'\n  Health: 20\n  Damage: 2\n  Skills:\n  - message{m=\"Hello from " + id + "\"} @Target ~onAttack";
        }
        return id + ":\n  Skills:\n  - message{m=\"Hello from " + id + "\"} @Self";
    }

    private static String topLevelKey(String body) {
        YamlBlockParser.Parsed p = YamlBlockParser.parse(body);
        return p.blocks().size() == 1 && p.problems().isEmpty() ? p.blocks().get(0).id() : null;
    }

    private static String normalizeInput(String text) throws StoreException {
        if (text == null) throw new StoreException("invalid", 422, "Missing text");
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.indexOf('\0') >= 0) throw new StoreException("invalid", 422, "Text contains NUL characters");
        if (t.startsWith("\uFEFF")) t = t.substring(1);
        int end = t.length();
        while (end > 0 && (t.charAt(end - 1) == '\n' || t.charAt(end - 1) == ' ' || t.charAt(end - 1) == '\t')) end--;
        return t.substring(0, end);
    }

    private static String toEol(String text, String eol) {
        return eol.equals("\n") ? text : text.replace("\n", eol);
    }

    private static boolean endsWithBlankLine(String raw) {
        int i = raw.length() - 1;
        if (i < 0) return true;
        if (raw.charAt(i) == '\n') i--;
        if (i >= 0 && raw.charAt(i) == '\r') i--;
        // last line content is blank if we hit another newline (or start) before a non-space char
        while (i >= 0 && (raw.charAt(i) == ' ' || raw.charAt(i) == '\t')) i--;
        return i < 0 || raw.charAt(i) == '\n';
    }

    private Entry entry(String kind, String id, String rel, Loaded l, YamlBlockParser.Block b, String text, List<String> warnings) {
        long mtime = 0;
        try {
            mtime = Files.getLastModifiedTime(guard.root().resolve(rel)).toMillis();
        } catch (IOException | RuntimeException ignored) {
            // informational only
        }
        return new Entry(kind, id, rel, b.startLine() + 1, b.endLine() + 1, text.replace("\r\n", "\n"), ProjectIndex.blockHash(text),
            ProjectIndex.sha256(l.bytes), mtime, warnings);
    }

    private ReentrantLock lockFor(Path p) {
        return locks.computeIfAbsent(p.toAbsolutePath().normalize(), k -> new ReentrantLock());
    }

    /** backup -> temp file in the same directory -> fsync -> atomic move -> read-back verification -> reindex */
    private void commit(Path path, byte[] originalBytes, String newRaw, String kind) throws IOException, StoreException {
        byte[] out = newRaw.getBytes(StandardCharsets.UTF_8);
        if (originalBytes != null) {
            try {
                backups.backup(path, originalBytes);
            } catch (IOException e) {
                throw new StoreException("io", 500, "Could not create a backup; nothing was changed (" + e.getMessage() + ")", e, Map.of());
            }
        }
        Files.createDirectories(path.getParent());
        Path tmp = Files.createTempFile(path.getParent(), "." + path.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, out, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            copyPermissions(path, tmp);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
        byte[] back = Files.readAllBytes(path);
        if (!java.util.Arrays.equals(back, out)) {
            throw new StoreException("io", 500, "Verification after writing failed; restore from the backup folder if needed");
        }
        index.refreshFile(path, kind);
    }

    private static void copyPermissions(Path from, Path to) {
        try {
            if (Files.exists(from)) {
                Set<PosixFilePermission> perms = Files.getPosixFilePermissions(from);
                Files.setPosixFilePermissions(to, perms);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX file system
        }
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
