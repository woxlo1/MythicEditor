package dev.mythiceditor.core.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.mythiceditor.core.Json;

/**
 * Outbound relay link. Instead of opening a port on the game server, the plugin keeps ONE WebSocket open to the
 * Cloudflare Worker (see relay-worker/). The Worker forwards every browser request through that socket as a JSON
 * frame; this class replays it against the embedded HTTP server on 127.0.0.1 and sends the answer back.
 *
 * <p>Because the replay goes through the normal HTTP server, every check there (session cookie, permissions, CSRF,
 * Origin, rate limits, path guard) applies to relayed requests exactly as it does to direct ones.
 *
 * <pre>
 * Worker -> plugin : {"t":"req","id":..,"method":"GET","path":"/api/x?y=1","headers":{..},"body":"base64"}
 * plugin -> Worker : {"t":"res","id":..,"status":200,"headers":{"name":["v",..]},"body":"base64"}
 * plugin -> Worker : "ping" (text)      Worker -> plugin : "pong" (text)
 * </pre>
 */
public final class RelayClient implements AutoCloseable {
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH");
    /** Request headers that are replayed locally (everything else the Worker sends is ignored). */
    private static final Set<String> REQUEST_HEADERS = Set.of(
        "cookie", "content-type", "accept", "accept-encoding", "if-none-match", "x-csrf-token", "origin",
        "x-forwarded-for", "x-forwarded-proto");
    private static final Set<String> DROP_RESPONSE_HEADERS = Set.of("connection", "keep-alive", "transfer-encoding", "content-length", "upgrade");

    private static final long PING_SECONDS = 25;
    private static final long DEAD_AFTER_MS = 75_000;
    private static final int MAX_RESPONSE_BYTES = 24 * 1024 * 1024;
    private static final int MAX_RELAY_THREADS = 16;

    private final URI wsUri;
    private final String secret;
    private final int localPort;
    private final int maxRequestBytes;
    private final Logger log;
    private final HttpClient wsClient;
    private final HttpClient localClient;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService timer;

    private final Object lock = new Object();
    private volatile boolean closed;
    private Conn current;
    private int failures;
    private boolean everConnected;

    public RelayClient(String relayUrl, String secret, String serverId, int localPort, int maxRequestBytes, Logger log) {
        this.wsUri = toWebSocketUri(relayUrl, serverId);
        this.secret = secret;
        this.localPort = localPort;
        this.maxRequestBytes = maxRequestBytes;
        this.log = log;
        this.wsClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.localClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        this.workers = new ThreadPoolExecutor(0, MAX_RELAY_THREADS, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "MythicEditor-Relay");
            t.setDaemon(true);
            return t;
        });
        this.timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MythicEditor-RelayTimer");
            t.setDaemon(true);
            return t;
        });
    }

    /** https://host/path -> wss://host/_relay/connect (?id=NAME when this is not the Worker's default server) */
    static URI toWebSocketUri(String relayUrl, String serverId) {
        URI base = URI.create(relayUrl.trim());
        String scheme = "https".equalsIgnoreCase(base.getScheme()) ? "wss" : "ws";
        String authority = base.getRawAuthority();
        String query = serverId == null || serverId.isBlank() ? "" : "?id=" + serverId;
        return URI.create(scheme + "://" + authority + "/_relay/connect" + query);
    }

    /** hex(HMAC-SHA256(key = the Worker's RELAY_SECRET, message = "relay:" + serverId)): a friend server's own secret. */
    public static String serverSecret(String masterSecret, String serverId) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(masterSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] d = mac.doFinal(("relay:" + serverId).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void start() {
        timer.scheduleWithFixedDelay(this::keepAlive, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS);
        timer.execute(this::connect);
    }

    public boolean connected() {
        synchronized (lock) {
            return current != null && current.open;
        }
    }

    @Override
    public void close() {
        closed = true;
        Conn c;
        synchronized (lock) {
            c = current;
            current = null;
        }
        if (c != null && c.ws != null) {
            try {
                c.ws.sendClose(WebSocket.NORMAL_CLOSURE, "plugin stopping");
            } catch (RuntimeException ignored) {
                // closing anyway
            }
            c.ws.abort();
        }
        timer.shutdownNow();
        workers.shutdownNow();
    }

    // ---- connection management ---------------------------------------------------------------------------------

    private void connect() {
        if (closed) return;
        Conn c = new Conn();
        try {
            wsClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + secret)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(wsUri, c)
                .whenComplete((ws, err) -> {
                    if (err != null) {
                        connectFailed(describe(err));
                    }
                });
        } catch (RuntimeException e) {
            connectFailed(describe(e));
        }
    }

    private void connectFailed(String reason) {
        if (closed) return;
        failures++;
        if (failures == 1 || failures % 10 == 0) {
            log.warning("Relay: cannot connect to " + wsUri + " (" + reason + "). Retrying in the background.");
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (closed) return;
        long base = Math.min(60, 2L << Math.min(failures, 5)); // 4,8,16,32,60...
        long delay = base + ThreadLocalRandom.current().nextLong(0, 3);
        try {
            timer.schedule(this::connect, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
            // shutting down
        }
    }

    private void keepAlive() {
        Conn c;
        synchronized (lock) {
            c = current;
        }
        if (c == null || !c.open) return;
        if (System.currentTimeMillis() - c.lastSeen > DEAD_AFTER_MS) {
            log.warning("Relay: no answer from the Worker, reconnecting.");
            c.ws.abort();
            return;
        }
        c.send("ping");
    }

    private static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return root.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    // ---- one WebSocket connection ------------------------------------------------------------------------------

    private final class Conn implements WebSocket.Listener {
        volatile WebSocket ws;
        volatile boolean open;
        volatile long lastSeen = System.currentTimeMillis();
        private final StringBuilder partial = new StringBuilder();
        private CompletableFuture<?> sendTail = CompletableFuture.completedFuture(null);

        @Override
        public void onOpen(WebSocket webSocket) {
            ws = webSocket;
            open = true;
            lastSeen = System.currentTimeMillis();
            Conn previous;
            synchronized (lock) {
                previous = current;
                current = this;
                failures = 0;
            }
            if (previous != null && previous.ws != null) previous.ws.abort();
            log.info(everConnected ? "Relay: reconnected to " + wsUri : "Relay: connected to " + wsUri);
            everConnected = true;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            lastSeen = System.currentTimeMillis();
            partial.append(data);
            if (last) {
                String msg = partial.toString();
                partial.setLength(0);
                try {
                    onMessage(this, msg);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Relay: could not handle a message", e);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            disconnected("closed by the Worker (" + statusCode + ")");
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            disconnected(describe(error));
        }

        private void disconnected(String why) {
            open = false;
            boolean wasCurrent;
            synchronized (lock) {
                wasCurrent = current == this;
                if (wasCurrent) current = null;
            }
            if (wasCurrent && !closed) {
                log.warning("Relay: connection lost (" + why + "). Reconnecting.");
                failures = 1;
                scheduleReconnect();
            }
        }

        /** WebSocket#sendText must not overlap, so sends are chained. */
        synchronized void send(String text) {
            WebSocket w = ws;
            if (w == null || !open) return;
            sendTail = sendTail.handle((v, e) -> null).thenCompose(v -> w.sendText(text, true))
                .whenComplete((v, e) -> {
                    if (e != null) w.abort();
                });
        }
    }

    // ---- request replay ----------------------------------------------------------------------------------------

    private void onMessage(Conn c, String msg) {
        if (msg.equals("pong")) return;
        JsonObject o = Json.parseObject(msg);
        if (!"req".equals(Json.str(o, "t"))) return;
        try {
            workers.execute(() -> serve(c, o));
        } catch (RejectedExecutionException e) {
            String id = Json.str(o, "id");
            if (id != null) c.send(errorFrame(id, 503, "The editor is busy, try again."));
        }
    }

    private void serve(Conn c, JsonObject req) {
        String id = Json.str(req, "id");
        if (id == null) return;
        try {
            c.send(replay(id, req));
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Relay: request failed", e);
            c.send(errorFrame(id, 502, "Internal relay error"));
        }
    }

    private String replay(String id, JsonObject req) {
        String method = String.valueOf(Json.str(req, "method")).toUpperCase(Locale.ROOT);
        String path = Json.str(req, "path");
        if (!METHODS.contains(method)) return errorFrame(id, 405, "Method not allowed");
        if (path == null || !path.startsWith("/") || path.startsWith("//")) return errorFrame(id, 400, "Bad path");

        byte[] body;
        try {
            String b64 = Json.str(req, "body");
            body = b64 == null || b64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return errorFrame(id, 400, "Bad body");
        }
        if (body.length > maxRequestBytes) return errorFrame(id, 413, "Request body exceeds the configured limit");

        URI target;
        try {
            target = new URI("http://127.0.0.1:" + localPort + path);
        } catch (java.net.URISyntaxException e) {
            return errorFrame(id, 400, "Bad path");
        }

        HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(25));
        JsonElement hs = req.get("headers");
        if (hs != null && hs.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : hs.getAsJsonObject().entrySet()) {
                String name = e.getKey().toLowerCase(Locale.ROOT);
                if (!REQUEST_HEADERS.contains(name) || !e.getValue().isJsonPrimitive()) continue;
                String value = e.getValue().getAsString();
                if (value.isEmpty() || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) continue;
                b.header(name, value);
            }
        }
        b.method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<byte[]> res;
        try {
            res = localClient.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (java.io.IOException e) {
            return errorFrame(id, 502, "The local editor server did not answer");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return errorFrame(id, 502, "Interrupted");
        }
        if (res.body().length > MAX_RESPONSE_BYTES) return errorFrame(id, 502, "Response too large");

        JsonObject out = new JsonObject();
        out.addProperty("t", "res");
        out.addProperty("id", id);
        out.addProperty("status", res.statusCode());
        JsonObject headers = new JsonObject();
        for (Map.Entry<String, List<String>> e : res.headers().map().entrySet()) {
            String name = e.getKey();
            if (name == null || DROP_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) continue;
            JsonArray values = new JsonArray();
            e.getValue().forEach(values::add);
            headers.add(name, values);
        }
        out.add("headers", headers);
        out.addProperty("body", Base64.getEncoder().encodeToString(res.body()));
        return out.toString();
    }

    private static String errorFrame(String id, int status, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "res");
        o.addProperty("id", id);
        o.addProperty("status", status);
        JsonObject h = new JsonObject();
        JsonArray ct = new JsonArray();
        ct.add("application/json; charset=utf-8");
        h.add("Content-Type", ct);
        JsonArray cc = new JsonArray();
        cc.add("no-store");
        h.add("Cache-Control", cc);
        o.add("headers", h);
        String json = "{\"error\":{\"code\":\"relay_error\",\"message\":" + Json.toJson(message) + "}}";
        o.addProperty("body", Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)));
        return o.toString();
    }
}
