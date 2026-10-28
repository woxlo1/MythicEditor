package dev.mythiceditor.core.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;

import dev.mythiceditor.core.Json;
import dev.mythiceditor.core.Kinds;
import dev.mythiceditor.core.config.EditorSettings;
import dev.mythiceditor.core.graph.RelationGraph;
import dev.mythiceditor.core.security.ContentGuard;
import dev.mythiceditor.core.index.ProjectIndex;
import dev.mythiceditor.core.storage.FileStore;
import dev.mythiceditor.core.storage.StoreException;

/** Implements every /api/* route. Pure request/response logic; transport concerns live in EditorHttpServer/Ctx. */
final class Api {
    private final EditorHttpServer core;
    private final ContentGuard guard;
    /** Makes ETags unique per editor start: the index version counter restarts at 1 after a reload. */
    private final String epoch = Long.toString(System.currentTimeMillis(), 36);

    Api(EditorHttpServer core) {
        this.core = core;
        this.guard = new ContentGuard(core.core.settings.restrictedMechanics);
    }

    /**
     * Anyone with mythiceditor.edit can write Skills, and a Skill can run server commands. Players without
     * mythiceditor.unrestricted may not ADD lines that use the restricted (command-running) mechanics.
     */
    private void requireNoNewRestrictedContent(Ctx ctx, String oldText, String newText) {
        if (!guard.enabled() || newText == null) return;
        List<String> added = guard.added(oldText, newText);
        if (added.isEmpty()) return;
        if (core.perms.has(ctx.playerId(), "mythiceditor.unrestricted")) return;
        String mech = guard.mechanicOf(added.get(0));
        throw new HttpEx(403, "restricted_content", "This change adds a mechanic that runs server commands ('" + mech
            + "'). That needs the permission mythiceditor.unrestricted. / コマンドを実行するmechanic('" + mech
            + "')を新しく追加するには mythiceditor.unrestricted 権限が必要です。");
    }

    // ---- session --------------------------------------------------------------------------------

    void sessionInfo(Ctx ctx) throws IOException {
        SessionManager.Session s = ctx.session;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("playerId", s.playerId().toString());
        body.put("playerName", s.playerName());
        body.put("csrf", s.csrf());
        body.put("permissions", permsFor(ctx));
        body.put("expiresInSeconds", Math.max(0, (s.maxExpiresAt() - System.currentTimeMillis()) / 1000));
        ctx.sendJson(200, body);
    }

    private Map<String, Boolean> permsFor(Ctx ctx) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        m.put("use", true);
        m.put("edit", core.perms.has(ctx.playerId(), "mythiceditor.edit"));
        m.put("reload", core.perms.has(ctx.playerId(), "mythiceditor.reload"));
        m.put("admin", core.perms.has(ctx.playerId(), "mythiceditor.admin"));
        return m;
    }

    void logout(Ctx ctx) throws IOException {
        String cookieName = core.sessionCookieName();
        String sid = ctx.cookies().get(cookieName);
        if (sid != null) core.core.sessions.invalidate(sid);
        ctx.clearCookie(cookieName);
        ctx.sendJson(200, Map.of("ok", true));
    }

    // ---- status / catalog -------------------------------------------------------------------------

    void status(Ctx ctx) throws IOException {
        EditorSettings s = core.core.settings;
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String k : List.of(Kinds.SKILL, Kinds.MOB, Kinds.ITEM, Kinds.DROPTABLE)) counts.put(k, core.core.index.count(k));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("editorVersion", core.pluginVersion);
        body.put("mythicPresent", core.core.mythic.isPresent());
        body.put("mythicReady", core.core.mythic.isReady());
        body.put("mythicVersion", core.core.mythic.version() == null ? "" : core.core.mythic.version());
        // The absolute server path (often contains the OS user name) is only for admins.
        body.put("mythicFolder", core.perms.has(ctx.playerId(), "mythiceditor.admin") ? core.core.pathGuard.root().toString() : "");
        body.put("editableKinds", s.editable);
        body.put("counts", counts);
        body.put("indexVersion", core.core.index.version());
        body.put("lastScanMillis", core.core.index.lastScanMillis());
        body.put("lastScanDurationMs", core.core.index.lastScanDuration());
        body.put("activeSessions", core.core.sessions.activeCount());
        body.put("autosaveDefault", s.autosave);
        body.put("autosaveDelayMs", s.autosaveDelayMs);
        ctx.sendJson(200, body);
    }

    void catalog(Ctx ctx) throws IOException {
        if (ctx.notModified(core.core.catalog.etag())) return;
        ctx.sendBytes(200, "application/json; charset=utf-8", core.core.catalog.json().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Validator for answers that depend only on the project index (lists, graph): changes whenever the index does. */
    private String indexEtag(Ctx ctx, String scope) {
        String q = ctx.ex.getRequestURI().getRawQuery();
        int h = (scope + "?" + (q == null ? "" : q)).hashCode();
        return "W/\"" + epoch + "-" + core.core.index.version() + "-" + Integer.toHexString(h) + "\"";
    }

    // ---- entries (skills / mobs / items / droptables) ---------------------------------------------

    void list(Ctx ctx, String kind) throws IOException {
        String q = ctx.query("q", "");
        String file = ctx.query("file", "");
        // the web UI asks for up to 5000 names at start-up; a lower cap silently hid entries from completion/diagnostics
        int limit = clamp(ctx.queryInt("limit", 100), 1, 5000);
        int offset = Math.max(0, ctx.queryInt("offset", 0));
        if (ctx.notModified(indexEtag(ctx, "list:" + kind))) return;
        ProjectIndex.Page page = core.core.index.search(kind, q, file, limit, offset);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProjectIndex.BlockInfo b : page.items()) items.add(summarize(b));
        ctx.sendJson(200, Map.of("total", page.total(), "items", items));
    }

    void get(Ctx ctx, String kind) throws IOException, StoreException {
        String id = requireId(ctx);
        try {
            FileStore.Entry e = core.core.store.read(kind, id);
            ctx.sendJson(200, entryJson(e));
        } catch (StoreException ex) {
            throw ex;
        }
    }

    void create(Ctx ctx, String kind) throws IOException, StoreException {
        JsonObject b = ctx.bodyJson(core.core.settings.maxRequestBytes);
        String id = Json.str(b, "id");
        String file = Json.str(b, "file");
        String text = Json.str(b, "text");
        requireNoNewRestrictedContent(ctx, "", text);
        try {
            FileStore.Entry e = core.core.store.create(kind, id, file, text, actor(ctx));
            ctx.sendJson(201, entryJson(e));
        } catch (StoreException ex) {
            throw ex;
        }
    }

    void update(Ctx ctx, String kind) throws IOException, StoreException {
        String id = requireId(ctx);
        JsonObject b = ctx.bodyJson(core.core.settings.maxRequestBytes);
        String text = Json.str(b, "text");
        String baseHash = Json.str(b, "baseHash");
        boolean force = Json.bool(b, "force");
        if (guard.enabled() && text != null && !guard.added("", text).isEmpty()) {
            // only now is the (more expensive) comparison with the stored version needed
            requireNoNewRestrictedContent(ctx, core.core.store.read(kind, id).text(), text);
        }
        FileStore.Entry e = core.core.store.save(kind, id, text, baseHash, force, actor(ctx));
        ctx.sendJson(200, entryJson(e));
    }

    void delete(Ctx ctx, String kind) throws IOException, StoreException {
        String id = requireId(ctx);
        String baseHash = ctx.query("baseHash");
        boolean force = "true".equals(ctx.query("force"));
        core.core.store.delete(kind, id, baseHash, force, actor(ctx));
        ctx.sendJson(200, Map.of("ok", true));
    }

    void duplicate(Ctx ctx, String kind) throws IOException, StoreException {
        String id = requireId(ctx);
        JsonObject b = ctx.bodyJson(core.core.settings.maxRequestBytes);
        String newId = Json.str(b, "newId");
        String file = Json.str(b, "file");
        FileStore.Entry e = core.core.store.duplicate(kind, id, newId, file, actor(ctx));
        ctx.sendJson(201, entryJson(e));
    }

    // ---- files / definition / graph / changes / backups --------------------------------------------

    void files(Ctx ctx) throws IOException {
        String kind = ctx.query("kind");
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectIndex.FileInfo f : core.core.index.files(kind)) {
            out.add(Map.of("file", f.rel(), "kind", f.kind(), "entries", f.blocks().size(),
                "error", f.error() == null ? "" : f.error(), "problems", f.problems()));
        }
        ctx.sendJson(200, Map.of("items", out));
    }

    // ---- whole files ---------------------------------------------------------------------------------

    void fileGet(Ctx ctx) throws IOException, StoreException {
        FileStore.FileDoc f = core.core.store.readFile(requireQuery(ctx, "path"));
        ctx.sendJson(200, fileJson(f));
    }

    void fileUpdate(Ctx ctx) throws IOException, StoreException {
        JsonObject b = ctx.bodyJson(core.core.settings.maxRequestBytes);
        String path = Json.str(b, "path");
        if (path == null || path.isBlank()) throw new HttpEx(400, "bad_request", "Missing 'path'");
        String text = Json.str(b, "text");
        String baseHash = Json.str(b, "baseHash");
        boolean force = Json.bool(b, "force");
        if (guard.enabled() && text != null && !guard.added("", text).isEmpty()) {
            requireNoNewRestrictedContent(ctx, core.core.store.readFile(path).text(), text);
        }
        FileStore.FileDoc f = core.core.store.saveFile(path, text, baseHash, force, actor(ctx));
        ctx.sendJson(200, fileJson(f));
    }

    private static Map<String, Object> fileJson(FileStore.FileDoc f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", f.file());
        m.put("kind", f.kind());
        m.put("text", f.text());
        m.put("hash", f.hash());
        m.put("mtime", f.mtime());
        m.put("entries", f.entries());
        m.put("warnings", f.warnings());
        return m;
    }

    void definition(Ctx ctx) throws IOException {
        String kind = requireQuery(ctx, "kind");
        String id = requireQuery(ctx, "id");
        List<ProjectIndex.BlockInfo> found = core.core.index.find(kind, id);
        if (found.isEmpty()) {
            ctx.sendJson(404, Map.of("found", false));
            return;
        }
        ProjectIndex.BlockInfo b = found.get(0);
        ctx.sendJson(200, Map.of("found", true, "file", b.file(), "line", b.line(), "endLine", b.endLine(),
            "ambiguous", found.size() > 1));
    }

    void graph(Ctx ctx) throws IOException {
        String kind = ctx.query("kind");
        String id = ctx.query("id");
        int depth = clamp(ctx.queryInt("depth", 2), 1, 6);
        int max = clamp(ctx.queryInt("max", 300), 10, 1000);
        if (ctx.notModified(indexEtag(ctx, "graph"))) return;
        RelationGraph.Graph g = (kind == null || id == null) ? core.core.graph.full(max) : core.core.graph.around(kind, id, depth, max);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (RelationGraph.Node n : g.nodes()) nodes.add(Map.of("id", n.id(), "kind", n.kind(), "file", n.file() == null ? "" : n.file(), "unresolved", n.unresolved()));
        List<Map<String, Object>> edges = new ArrayList<>();
        for (RelationGraph.Edge e : g.edges()) edges.add(Map.of("from", e.from(), "to", e.to(), "kind", e.kind()));
        ctx.sendJson(200, Map.of("nodes", nodes, "edges", edges));
    }

    void changes(Ctx ctx) throws IOException {
        long since;
        try {
            since = Long.parseLong(ctx.query("since", "0"));
        } catch (NumberFormatException e) {
            throw new HttpEx(400, "bad_request", "'since' must be a number");
        }
        int limit = clamp(ctx.queryInt("limit", 100), 1, 500);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var c : core.core.changeLog.since(since, limit)) {
            out.add(Map.of("seq", c.seq(), "time", c.time(), "actor", c.actor(), "action", c.action(),
                "kind", c.kind() == null ? "" : c.kind(), "id", c.id() == null ? "" : c.id(), "file", c.file() == null ? "" : c.file()));
        }
        ctx.sendJson(200, Map.of("lastSeq", core.core.changeLog.lastSeq(), "indexVersion", core.core.index.version(), "items", out));
    }

    void backups(Ctx ctx) throws IOException {
        ctx.sendJson(200, Map.of("items", core.core.backups.list(100)));
    }

    void rescan(Ctx ctx) throws IOException {
        var c = core.core.watcher.rescanNow();
        ctx.sendJson(200, Map.of("changed", c.size(), "indexVersion", core.core.index.version()));
    }

    // ---- reload / admin ----------------------------------------------------------------------------

    void reload(Ctx ctx) throws IOException {
        if (!core.core.mythic.isPresent()) throw new HttpEx(409, "unavailable", "MythicMobs is not loaded");
        core.mainThread.run(() -> {
            core.core.mythic.reload();
            return null;
        }).join();
        core.core.watcher.rescanNow();
        core.core.refreshMythicRuntimeCatalog();
        ctx.sendJson(200, Map.of("ok", true));
    }

    void adminSessions(Ctx ctx) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionManager.Session s : core.core.sessions.allSessions()) {
            out.add(Map.of("playerId", s.playerId().toString(), "playerName", s.playerName(),
                "createdAt", s.createdAt(), "lastSeen", s.lastSeen().get(), "token", SessionManager.publicId(s.token())));
        }
        ctx.sendJson(200, Map.of("items", out));
    }

    void adminRevoke(Ctx ctx) throws IOException {
        String masked = ctx.path("token");
        int revoked = 0;
        for (SessionManager.Session s : core.core.sessions.allSessions()) {
            if (SessionManager.publicId(s.token()).equals(masked)) {
                core.core.sessions.invalidate(s.token());
                revoked++;
            }
        }
        ctx.sendJson(200, Map.of("revoked", revoked));
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static Map<String, Object> summarize(ProjectIndex.BlockInfo b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", b.kind());
        m.put("id", b.id());
        m.put("file", b.file());
        m.put("line", b.line());
        if (b.kind().equals(Kinds.MOB)) {
            m.put("type", b.refs().typeValue() == null ? "" : b.refs().typeValue());
            m.put("display", b.refs().display() == null ? "" : b.refs().display());
        }
        m.put("skillRefs", b.refs().skills().size());
        m.put("mobRefs", b.refs().mobs().size());
        return m;
    }

    private static Map<String, Object> entryJson(FileStore.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", e.kind());
        m.put("id", e.id());
        m.put("file", e.file());
        m.put("line", e.line());
        m.put("endLine", e.endLine());
        m.put("text", e.text());
        m.put("hash", e.hash());
        m.put("fileHash", e.fileHash());
        m.put("mtime", e.mtime());
        m.put("warnings", e.warnings());
        return m;
    }

    private static String requireId(Ctx ctx) {
        String id = ctx.path("id");
        if (id == null || id.isBlank()) throw new HttpEx(400, "bad_request", "Missing id");
        return id;
    }

    private static String requireQuery(Ctx ctx, String name) {
        String v = ctx.query(name);
        if (v == null || v.isBlank()) throw new HttpEx(400, "bad_request", "Missing '" + name + "' query parameter");
        return v;
    }

    private static String actor(Ctx ctx) {
        return ctx.session == null ? "unknown" : ctx.session.playerName();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
