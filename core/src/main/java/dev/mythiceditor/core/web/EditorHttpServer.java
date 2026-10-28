package dev.mythiceditor.core.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpServer;

import dev.mythiceditor.core.Kinds;
import dev.mythiceditor.core.service.EditorCore;

/**
 * HTTP transport: request routing, session/CSRF/origin enforcement, rate limiting, static SPA serving.
 * Built on the JDK's com.sun.net.httpserver so the plugin ships with zero extra runtime dependencies.
 */
public final class EditorHttpServer {
    /** Default cookie name for the main/default server. */
    public static final String DEFAULT_COOKIE = "mythiceditor_sid";
    /** Legacy static constant kept for backward compatibility in external code paths. */
    public static final String COOKIE = DEFAULT_COOKIE;
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    public final EditorCore core;
    public final PermissionChecker perms;
    public final MainThreadRunner mainThread;
    public final String pluginVersion = "1.0.0";
    private final Logger log;
    private final String bootMessage;
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private int boundPort;

    public EditorHttpServer(dev.mythiceditor.core.config.EditorSettings settingsUnused, EditorCore core, PermissionChecker perms,
                             MainThreadRunner mainThread, Logger log, String bootMessage) {
        this.core = core;
        this.perms = perms;
        this.mainThread = mainThread;
        this.log = log;
        this.bootMessage = bootMessage;
    }

    /** Session cookie name derived from relay server id (main uses the default name). */
    public String sessionCookieName() {
        var settings = core.settings;
        String sid = settings == null ? "" : (settings.relayServerId == null ? "" : settings.relayServerId.trim());
        if (sid.isEmpty()) return DEFAULT_COOKIE;
        // Only alphanumeric/hyphen as per relay id rules; keep simple and safe
        return DEFAULT_COOKIE + "_" + sid;
    }

    public void start() throws IOException {
        var settings = core.settings;
        Ctx.configureCsp(settings.csp);
        server = HttpServer.create(new InetSocketAddress(settings.bindAddress, settings.port), 0);
        executor = Executors.newFixedThreadPool(Math.max(2, settings.threads), r -> {
            Thread t = new Thread(r, "MythicEditor-HTTP");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        boundPort = server.getAddress().getPort();

        Router router = buildRouter();
        server.createContext("/api/", wrap(router));
        server.createContext("/editor/", exchange -> handleTicket(exchange));
        server.createContext("/", new StaticAssets(devWebDir()));
        server.start();
        log.info(bootMessage + " listening on " + settings.bindAddress + ":" + boundPort);
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    public int boundPort() {
        return boundPort;
    }

    public String publicUrl() {
        if (!core.settings.publicUrl.isBlank()) return core.settings.publicUrl;
        String host = core.settings.bindAddress;
        if (host == null || host.isBlank() || host.equals("0.0.0.0") || host.equals("::")) host = "localhost";
        return "http://" + host + ":" + boundPort;
    }

    private static java.nio.file.Path devWebDir() {
        String p = System.getProperty("mythiceditor.webDir");
        return p == null ? null : java.nio.file.Path.of(p);
    }

    /** GET /editor/{ticket} redeems the ticket, sets the session cookie and redirects into the SPA. */
    private void handleTicket(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        try {
            Ctx.securityHeaders(ex);
            if (!core.loginLimiter.allow(clientIp(ex))) {
                byte[] msg = "Too many attempts. Please wait a minute and try again.".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                ex.getResponseHeaders().set("Retry-After", "60");
                ex.sendResponseHeaders(429, msg.length);
                try (var os = ex.getResponseBody()) {
                    os.write(msg);
                }
                return;
            }
            String path = ex.getRequestURI().getPath();
            String token = path.startsWith("/editor/") ? path.substring("/editor/".length()) : "";
            token = token.split("/", 2)[0];
            SessionManager.Session s = token.isBlank() ? null : core.sessions.redeem(token);
            if (s == null) {
                ex.getResponseHeaders().set("Location", "/?error=expired_link");
                ex.sendResponseHeaders(302, -1);
                ex.getResponseBody().close();
                return;
            }
            String cookie = sessionCookieName() + "=" + s.token() + "; Path=/; SameSite=Strict; Max-Age=" + (core.settings.sessionMaxLifetimeMinutes * 60);
            if (forwardedHttps(ex)) cookie += "; Secure";
            cookie += "; HttpOnly";
            ex.getResponseHeaders().add("Set-Cookie", cookie);
            ex.getResponseHeaders().set("Location", "/");
            ex.sendResponseHeaders(302, -1);
            ex.getResponseBody().close();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Ticket redemption failed", e);
            ex.sendResponseHeaders(500, -1);
            ex.getResponseBody().close();
        }
    }

    private Router buildRouter() {
        Router r = new Router(log);
        Api api = new Api(this);

        r.on("GET", "/api/session", api::sessionInfo);
        r.on("POST", "/api/session/logout", api::logout);
        r.on("GET", "/api/status", api::status);
        r.on("GET", "/api/catalog", api::catalog);

        for (String kind : List.of(Kinds.SKILL, Kinds.MOB, Kinds.ITEM, Kinds.DROPTABLE)) {
            String base = "/api/" + kind + "s";
            r.on("GET", base, ctx -> api.list(ctx, kind));
            r.on("GET", base + "/:id", ctx -> api.get(ctx, kind));
            r.on("POST", base, ctx -> api.create(ctx, kind));
            r.on("PUT", base + "/:id", ctx -> api.update(ctx, kind));
            r.on("DELETE", base + "/:id", ctx -> api.delete(ctx, kind));
            r.on("POST", base + "/:id/duplicate", ctx -> api.duplicate(ctx, kind));
        }

        r.on("GET", "/api/files", api::files);
        r.on("GET", "/api/file", api::fileGet);
        r.on("PUT", "/api/file", api::fileUpdate);
        r.on("GET", "/api/definition", api::definition);
        r.on("GET", "/api/graph", api::graph);
        r.on("GET", "/api/changes", api::changes);
        r.on("GET", "/api/backups", api::backups);
        r.on("POST", "/api/rescan", api::rescan);
        r.on("POST", "/api/reload", api::reload);
        r.on("GET", "/api/admin/sessions", api::adminSessions);
        r.on("POST", "/api/admin/sessions/:token/revoke", api::adminRevoke);

        r.notFound(ctx -> ctx.sendJson(404, Map.of("error", Map.of("code", "not_found", "message", "No such API route"))));
        return r;
    }

    /** Session auth, permission gate, CSRF/origin check and rate limiting around every /api/* route. */
    private com.sun.net.httpserver.HttpHandler wrap(Router router) {
        return exchange -> {
            Ctx ctx;
            try {
                ctx = new Ctx(exchange, Map.of());
            } catch (RuntimeException e) { // e.g. malformed percent-encoding in the query string
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            ctx.trustedProxy = trustedProxyPeer(exchange);
            exchange.setAttribute("mythiceditor.trustedProxy", ctx.trustedProxy);
            try {
                String sid = ctx.cookies().get(sessionCookieName());
                SessionManager.Session session = sid == null ? null : core.sessions.touch(sid);
                if (session == null) {
                    if (!core.loginLimiter.allow(clientIp(exchange))) {
                        ctx.sendJson(429, Map.of("error", Map.of("code", "rate_limited", "message", "Too many requests, slow down")));
                        return;
                    }
                    ctx.sendJson(401, Map.of("error", Map.of("code", "unauthenticated", "message", "Session expired or missing. Run /mythiceditor again.")));
                    return;
                }
                if (!perms.has(session.playerId(), "mythiceditor.use")) {
                    ctx.sendJson(403, Map.of("error", Map.of("code", "forbidden", "message", "Missing permission mythiceditor.use")));
                    return;
                }

                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                boolean write = WRITE_METHODS.contains(method);

                if (write) {
                    if (!checkOrigin(exchange)) {
                        ctx.sendJson(403, Map.of("error", Map.of("code", "bad_origin", "message", "Request origin not allowed")));
                        return;
                    }
                    String csrf = ctx.header("X-CSRF-Token");
                    if (csrf == null || !constantTimeEquals(csrf, session.csrf())) {
                        ctx.sendJson(403, Map.of("error", Map.of("code", "bad_csrf", "message", "Missing or invalid CSRF token")));
                        return;
                    }
                    if (!core.writeLimiter.allow(session.token())) {
                        ctx.sendJson(429, Map.of("error", Map.of("code", "rate_limited", "message", "Too many write requests, slow down")));
                        return;
                    }
                    if (needsEditPermission(path) && !perms.has(session.playerId(), "mythiceditor.edit")) {
                        ctx.sendJson(403, Map.of("error", Map.of("code", "forbidden", "message", "Missing permission mythiceditor.edit")));
                        return;
                    }
                    if (path.equals("/api/reload") && !perms.has(session.playerId(), "mythiceditor.reload")) {
                        ctx.sendJson(403, Map.of("error", Map.of("code", "forbidden", "message", "Missing permission mythiceditor.reload")));
                        return;
                    }
                }
                if (path.startsWith("/api/admin/") && !perms.has(session.playerId(), "mythiceditor.admin")) {
                    ctx.sendJson(403, Map.of("error", Map.of("code", "forbidden", "message", "Missing permission mythiceditor.admin")));
                    return;
                }
                if (!core.apiLimiter.allow(session.token())) {
                    ctx.sendJson(429, Map.of("error", Map.of("code", "rate_limited", "message", "Too many requests, slow down")));
                    return;
                }

                ctx.session = session;
                router.handle(exchange, session);
            } catch (IOException e) {
                log.log(Level.FINE, "IO error handling request", e);
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Unexpected error handling " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath(), e);
                try {
                    ctx.sendJson(500, Map.of("error", Map.of("code", "internal_error", "message", "Internal error")));
                } catch (IOException | RuntimeException ignored) {
                    exchange.close();
                }
            }
        };
    }

    private static boolean needsEditPermission(String path) {
        return path.matches("^/api/(skills|mobs|items|droptables)(/.*)?$") || path.equals("/api/file") || path.equals("/api/rescan");
    }

    /** Writes must carry an Origin header (every browser sends one for POST/PUT/DELETE) that matches this server. */
    private boolean checkOrigin(com.sun.net.httpserver.HttpExchange ex) {
        return originAllowed(ex.getRequestHeaders().getFirst("Origin"), ex.getRequestHeaders().getFirst("Host"),
            core.settings.allowedOrigins, core.settings.publicUrl);
    }

    /** Pure function so it can be unit tested: is {@code origin} the same site as {@code hostHeader} (or explicitly allowed)? */
    static boolean originAllowed(String origin, String hostHeader, List<String> allowed, String publicUrl) {
        if (origin == null || origin.isBlank()) return false;
        String o = origin.trim();
        for (String a : allowed) {
            if (o.equalsIgnoreCase(a.trim())) return true;
        }
        if (publicUrl != null && !publicUrl.isBlank() && o.equalsIgnoreCase(publicUrl.trim())) return true;
        try {
            URI u = URI.create(o);
            if (u.getHost() == null || hostHeader == null) return false;
            String path = u.getRawPath();
            if (path != null && !path.isEmpty()) return false;
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            String originAuthority = u.getHost().toLowerCase(java.util.Locale.ROOT) + (u.getPort() == -1 ? "" : ":" + u.getPort());
            String host = hostHeader.trim().toLowerCase(java.util.Locale.ROOT);
            return stripDefaultPort(host, scheme).equals(stripDefaultPort(originAuthority, scheme));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String stripDefaultPort(String authority, String scheme) {
        if (scheme.equals("https") && authority.endsWith(":443")) return authority.substring(0, authority.length() - 4);
        if (scheme.equals("http") && authority.endsWith(":80")) return authority.substring(0, authority.length() - 3);
        return authority;
    }

    // ---- client address / proxy trust ----------------------------------------------------------------------

    /** The TCP peer is on this machine: a tunnel client or reverse proxy, never a direct remote attacker. */
    private static boolean isLoopbackPeer(com.sun.net.httpserver.HttpExchange ex) {
        InetSocketAddress a = ex.getRemoteAddress();
        return a != null && a.getAddress() != null && a.getAddress().isLoopbackAddress();
    }

    /** X-Forwarded-* headers are believed only if security.trust-proxy-headers is on AND the peer is local. */
    private boolean trustedProxyPeer(com.sun.net.httpserver.HttpExchange ex) {
        return core.settings.trustProxyHeaders && isLoopbackPeer(ex);
    }

    private boolean forwardedHttps(com.sun.net.httpserver.HttpExchange ex) {
        return trustedProxyPeer(ex) && "https".equalsIgnoreCase(ex.getRequestHeaders().getFirst("X-Forwarded-Proto"));
    }

    /**
     * Key used for per-client rate limiting. Behind a trusted proxy this is the address the proxy appended to
     * X-Forwarded-For (the LAST entry; earlier ones are client supplied and can be forged).
     */
    String clientIp(com.sun.net.httpserver.HttpExchange ex) {
        if (trustedProxyPeer(ex)) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] parts = xff.split(",");
                String last = parts[parts.length - 1].trim();
                if (!last.isEmpty() && last.length() <= 64) return last;
            }
        }
        InetSocketAddress a = ex.getRemoteAddress();
        return a == null || a.getAddress() == null ? "unknown" : a.getAddress().getHostAddress();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
