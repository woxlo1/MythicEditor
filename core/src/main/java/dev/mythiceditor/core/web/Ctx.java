package dev.mythiceditor.core.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import dev.mythiceditor.core.Json;

/** Convenience wrapper around HttpExchange: query/path params, cookies, JSON body, JSON responses. */
public final class Ctx {
    public final HttpExchange ex;
    public final Map<String, String> pathParams;
    private final Map<String, String> query;
    private byte[] bodyBytes;
    public SessionManager.Session session;
    public String remoteKey;
    /** True only when the request really came through our own reverse proxy / tunnel (see EditorHttpServer). */
    public boolean trustedProxy;

    public Ctx(HttpExchange ex, Map<String, String> pathParams) {
        this.ex = ex;
        this.pathParams = pathParams;
        this.query = parseQuery(ex.getRequestURI().getRawQuery());
    }

    public String method() {
        return ex.getRequestMethod();
    }

    public String path(String name) {
        return pathParams.get(name);
    }

    public String query(String name) {
        return query.get(name);
    }

    public String query(String name, String def) {
        String v = query.get(name);
        return v == null ? def : v;
    }

    public int queryInt(String name, int def) {
        String v = query.get(name);
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public String header(String name) {
        return ex.getRequestHeaders().getFirst(name);
    }

    public Map<String, String> cookies() {
        Map<String, String> out = new HashMap<>();
        List<String> raw = ex.getRequestHeaders().get("Cookie");
        if (raw == null) return out;
        for (String line : raw) {
            for (String part : line.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) out.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return out;
    }

    public byte[] bodyBytes(int maxBytes) throws IOException {
        if (bodyBytes != null) return bodyBytes;
        long declared = -1;
        try {
            String lenHeader = header("Content-Length");
            if (lenHeader != null) declared = Long.parseLong(lenHeader.trim());
        } catch (NumberFormatException e) {
            throw new HttpEx(400, "bad_request", "Malformed Content-Length header");
        }
        if (declared > maxBytes) throw new HttpEx(413, "too_large", "Request body exceeds the configured limit");
        InputStream in = ex.getRequestBody();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream((int) Math.min(8192, Math.max(64, declared < 0 ? 8192 : declared)));
        byte[] tmp = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(tmp)) != -1) {
            total += n;
            if (total > maxBytes) throw new HttpEx(413, "too_large", "Request body exceeds the configured limit");
            buf.write(tmp, 0, n);
        }
        bodyBytes = buf.toByteArray();
        return bodyBytes;
    }

    public JsonObject bodyJson(int maxBytes) throws IOException {
        byte[] b = bodyBytes(maxBytes);
        if (b.length == 0) return new JsonObject();
        try {
            return Json.parseObject(new String(b, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new HttpEx(400, "bad_json", "Malformed JSON body");
        }
    }

    public String remoteAddress() {
        InetSocketAddress a = ex.getRemoteAddress();
        return a == null ? "unknown" : a.getAddress().getHostAddress();
    }

    /** Responses smaller than this are not worth compressing. */
    private static final int GZIP_MIN_BYTES = 1024;

    public void sendJson(int status, Object body) throws IOException {
        sendBytes(status, "application/json; charset=utf-8", Json.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    /** Sends a body, gzip-compressed when it is big enough and the browser accepts it (cuts tunnel traffic a lot). */
    public void sendBytes(int status, String contentType, byte[] data) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("Content-Type", contentType);
        securityHeaders(ex);
        byte[] out = data;
        if (data.length >= GZIP_MIN_BYTES && acceptsGzip()) {
            byte[] packed = Gzip.compress(data, 6);
            if (packed.length < data.length) {
                out = packed;
                h.set("Content-Encoding", "gzip");
            }
        }
        h.set("Vary", "Accept-Encoding");
        if (out.length == 0) {
            ex.sendResponseHeaders(status, -1);
            ex.getResponseBody().close();
            return;
        }
        ex.sendResponseHeaders(status, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    public boolean acceptsGzip() {
        String ae = header("Accept-Encoding");
        return ae != null && ae.toLowerCase(java.util.Locale.ROOT).contains("gzip");
    }

    /**
     * Marks the response as revalidatable (ETag + "no-cache" = the browser asks every time, but only downloads the
     * body when it changed) and answers 304 right away if the browser already has exactly this version.
     */
    public boolean notModified(String etag) throws IOException {
        var h = ex.getResponseHeaders();
        h.set("ETag", etag);
        h.set("Cache-Control", "private, no-cache");
        if (etagMatches(header("If-None-Match"), etag)) {
            sendEmpty(304);
            return true;
        }
        return false;
    }

    static boolean etagMatches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || etag == null) return false;
        String want = stripWeak(etag);
        for (String part : ifNoneMatch.split(",")) {
            String p = part.trim();
            if (p.equals("*") || stripWeak(p).equals(want)) return true;
        }
        return false;
    }

    private static String stripWeak(String s) {
        String t = s.trim();
        return t.startsWith("W/") ? t.substring(2) : t;
    }

    public void sendEmpty(int status) throws IOException {
        securityHeaders(ex);
        ex.sendResponseHeaders(status, -1);
        ex.getResponseBody().close();
    }

    public void setCookie(String name, String value, int maxAgeSeconds, boolean httpOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append('=').append(value).append("; Path=/; SameSite=Strict");
        if (maxAgeSeconds >= 0) sb.append("; Max-Age=").append(maxAgeSeconds);
        if (httpOnly) sb.append("; HttpOnly");
        if (isSecure()) sb.append("; Secure");
        ex.getResponseHeaders().add("Set-Cookie", sb.toString());
    }

    public void clearCookie(String name) {
        ex.getResponseHeaders().add("Set-Cookie", name + "=; Path=/; Max-Age=0; SameSite=Strict" + (isSecure() ? "; Secure" : ""));
    }

    /** Whether the browser reached us over HTTPS. The forwarded header is only believed when it came from a trusted proxy. */
    public boolean isSecure() {
        return trustedProxy && "https".equalsIgnoreCase(header("X-Forwarded-Proto"));
    }

    public UUID playerId() {
        return session == null ? null : session.playerId();
    }

    // Scripts/styles/workers only from our own origin. Monaco injects <style> elements, hence 'unsafe-inline' for styles
    // only (never for scripts), and starts its worker from our own bundle (blob: kept as a fallback).
    private static final String CSP_POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
        + "img-src 'self' data:; font-src 'self' data:; worker-src 'self' blob:; connect-src 'self'; object-src 'none'; "
        + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    private static volatile String csp = CSP_POLICY;

    /** Turns the Content-Security-Policy header on or off (security.csp in config.yml). */
    public static void configureCsp(boolean enabled) {
        csp = enabled ? CSP_POLICY : null;
    }

    public static void securityHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        String policy = csp;
        if (policy != null) h.set("Content-Security-Policy", policy);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        if (!h.containsKey("Cache-Control")) h.set("Cache-Control", "no-store");
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(decode(k), decode(v));
        }
        return out;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }
}
