package dev.mythiceditor.core.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/**
 * Serves the built web/ SPA. Reads from a filesystem directory when {@code devDir} is given (hot dev builds),
 * otherwise from classpath resources under {@code /web} (bundled into the plugin jar at build time).
 * Unknown paths fall back to index.html so the client-side router can handle deep links.
 *
 * <p>Tuned for slow links (tunnels): bundled files are read once and kept in memory together with a gzip copy,
 * every response has an ETag (so reloads are a tiny 304), and the hashed files under /assets/ are cached for a year.
 */
public final class StaticAssets implements HttpHandler {
    private static final Map<String, String> MIME = Map.ofEntries(
        Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "application/javascript; charset=utf-8"),
        Map.entry("mjs", "application/javascript; charset=utf-8"), Map.entry("css", "text/css; charset=utf-8"),
        Map.entry("json", "application/json; charset=utf-8"), Map.entry("svg", "image/svg+xml"),
        Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
        Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"),
        Map.entry("map", "application/json; charset=utf-8"), Map.entry("txt", "text/plain; charset=utf-8"));

    private record Asset(byte[] raw, byte[] gz, String etag, String mime) {}

    private final Path devDir;
    /** Production mode = files never change while we run (bundled in the jar): cache them in memory and in browsers. */
    private final boolean production;
    private final ConcurrentHashMap<String, Asset> cache = new ConcurrentHashMap<>();

    public StaticAssets(Path devDir) {
        this(devDir, devDir == null);
    }

    /** Package-private so tests can serve a directory with production caching rules. */
    StaticAssets(Path dir, boolean production) {
        this.devDir = dir;
        this.production = production;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        String path = ex.getRequestURI().getPath();
        String rel = path.equals("/") ? "index.html" : path.substring(1);
        rel = rel.replace('\\', '/');
        if (rel.contains("..") || rel.contains("\0")) {
            ex.sendResponseHeaders(400, -1);
            ex.close();
            return;
        }
        Asset a = asset(rel);
        if (a == null) {
            if (rel.startsWith("assets/")) { // a stale hashed file: do not answer with index.html disguised as JS
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            rel = "index.html";
            a = asset(rel);
        }
        if (a == null) {
            String msg = "MythicEditor web assets are missing. Run the Gradle build with the web/ frontend (./gradlew build), or set -PskipWeb only for backend-only iterations.";
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            Ctx.securityHeaders(ex);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(503, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
            return;
        }

        Ctx.securityHeaders(ex);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", a.mime());
        h.set("ETag", a.etag());
        h.set("Vary", "Accept-Encoding");
        // Files under /assets/ have a content hash in their name, so they never change: cache them for a year.
        h.set("Cache-Control", rel.startsWith("assets/") && production ? "public, max-age=31536000, immutable" : "no-cache");

        if (Ctx.etagMatches(ex.getRequestHeaders().getFirst("If-None-Match"), a.etag())) {
            ex.sendResponseHeaders(304, -1);
            ex.close();
            return;
        }
        byte[] body = a.raw();
        String ae = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (a.gz() != null && ae != null && ae.toLowerCase(Locale.ROOT).contains("gzip")) {
            body = a.gz();
            h.set("Content-Encoding", "gzip");
        }
        ex.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
        try (OutputStream os = ex.getResponseBody()) {
            if (!ex.getRequestMethod().equals("HEAD")) os.write(body);
        }
    }

    private Asset asset(String rel) throws IOException {
        if (!production) return build(rel); // dev builds change under our feet: never cache
        Asset cached = cache.get(rel);
        if (cached != null) return cached;
        Asset built = build(rel);
        if (built != null) cache.put(rel, built);
        return built;
    }

    private Asset build(String rel) throws IOException {
        byte[] data = load(rel);
        if (data == null) return null;
        String mime = mimeFor(rel);
        byte[] gz = null;
        if (data.length >= 512 && compressible(mime)) {
            byte[] packed = Gzip.compress(data, 9);
            if (packed.length < data.length) gz = packed;
        }
        return new Asset(data, gz, "W/\"" + hash(data) + "\"", mime);
    }

    private byte[] load(String rel) throws IOException {
        if (devDir != null) {
            Path p = devDir.resolve(rel).normalize();
            if (!p.startsWith(devDir) || !Files.isRegularFile(p)) return null;
            return Files.readAllBytes(p);
        }
        URL url = StaticAssets.class.getResource("/web/" + rel);
        if (url == null) return null;
        try (InputStream in = url.openStream()) {
            return in.readAllBytes();
        }
    }

    private static boolean compressible(String mime) {
        return mime.startsWith("text/") || mime.startsWith("application/javascript") || mime.startsWith("application/json")
            || mime.startsWith("image/svg");
    }

    private static String hash(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mimeFor(String rel) {
        int dot = rel.lastIndexOf('.');
        String ext = dot < 0 ? "" : rel.substring(dot + 1).toLowerCase(Locale.ROOT);
        return MIME.getOrDefault(ext, "application/octet-stream");
    }
}
