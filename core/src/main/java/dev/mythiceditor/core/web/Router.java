package dev.mythiceditor.core.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

/** Minimal path-template router (":id" segments, one wildcard "*path" at the end) for com.sun.net.httpserver. */
public final class Router implements HttpHandler {
    @FunctionalInterface
    public interface Handler {
        void handle(Ctx ctx) throws Exception;
    }

    private record Route(String method, String[] segments, Handler handler) {}

    private final List<Route> routes = new ArrayList<>();
    private final Logger log;
    private Handler notFound = ctx -> ctx.sendJson(
            404,
            Map.of(
                    "error",
                    Map.of(
                            "code",
                            "not_found",
                            "message",
                            "No such route"
                    )
            )
    );

    public Router(Logger log) {
        this.log = log;
    }

    public Router on(String method, String pattern, Handler h) {
        routes.add(new Route(method, pattern.split("/"), h));
        return this;
    }

    public Router notFound(Handler h) {
        this.notFound = h;
        return this;
    }

    /**
     * Standard HttpHandler entry point.
     * Requests coming through EditorHttpServer should use the
     * session-aware overload below.
     */
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        handle(exchange, null);
    }

    /**
     * Handles a request while preserving the authenticated session.
     */
    public void handle(
            HttpExchange exchange,
            SessionManager.Session session
    ) throws IOException {

        String path = exchange.getRequestURI().getPath();
        String[] parts = path.split("/");
        Route match = null;
        Map<String, String> params = new HashMap<>();

        for (Route r : routes) {
            if (!r.method().equals(exchange.getRequestMethod())) continue;

            Map<String, String> p = new HashMap<>();

            if (matches(r.segments(), parts, p)) {
                match = r;
                params = p;
                break;
            }
        }

        Ctx ctx = new Ctx(exchange, params);

        // Preserve the session authenticated by EditorHttpServer.
        ctx.session = session;

        try {
            if (match != null) {
                match.handler().handle(ctx);
            } else {
                boolean pathExists = routes.stream()
                        .anyMatch(r -> matches(r.segments(), parts, new HashMap<>()));

                if (pathExists) {
                    ctx.sendJson(
                            405,
                            Map.of(
                                    "error",
                                    Map.of(
                                            "code",
                                            "method_not_allowed",
                                            "message",
                                            "Method not allowed"
                                    )
                            )
                    );
                } else {
                    notFound.handle(ctx);
                }
            }
        } catch (HttpEx e) {
            safeSend(
                    ctx,
                    e.status,
                    Map.of(
                            "error",
                            mergeExtra(e.code, e.getMessage(), e.extra)
                    )
            );
        } catch (dev.mythiceditor.core.storage.StoreException e) {
            safeSend(
                    ctx,
                    e.status(),
                    Map.of(
                            "error",
                            mergeExtra(e.code(), e.getMessage(), e.extra())
                    )
            );
        } catch (SecurityException e) {
            safeSend(
                    ctx,
                    403,
                    Map.of(
                            "error",
                            Map.of(
                                    "code",
                                    "forbidden",
                                    "message",
                                    e.getMessage()
                            )
                    )
            );
        } catch (Exception e) {
            log.log(Level.WARNING, "Unhandled error on " + path, e);

            safeSend(
                    ctx,
                    500,
                    Map.of(
                            "error",
                            Map.of(
                                    "code",
                                    "internal",
                                    "message",
                                    "Internal server error"
                            )
                    )
            );
        } finally {
            exchange.close();
        }
    }

    private static Map<String, Object> mergeExtra(
            String code,
            String message,
            Map<String, Object> extra
    ) {
        Map<String, Object> m = new HashMap<>();
        m.put("code", code);
        m.put("message", message);

        if (extra != null && !extra.isEmpty()) {
            m.putAll(extra);
        }

        return m;
    }

    private static void safeSend(
            Ctx ctx,
            int status,
            Object body
    ) {
        try {
            ctx.sendJson(status, body);
        } catch (IOException ignored) {
            // client disconnected
        }
    }

    private static boolean matches(
            String[] pattern,
            String[] actual,
            Map<String, String> out
    ) {
        if (pattern.length != actual.length) {
            if (
                    pattern.length > 0
                            && pattern[pattern.length - 1].startsWith("*")
                            && actual.length >= pattern.length - 1
            ) {
                // wildcard tail
            } else {
                return false;
            }
        }

        for (int i = 0; i < pattern.length; i++) {
            String seg = pattern[i];

            if (seg.startsWith("*")) {
                StringBuilder rest = new StringBuilder();

                for (int j = i; j < actual.length; j++) {
                    if (j > i) rest.append('/');
                    rest.append(actual[j]);
                }

                out.put(seg.substring(1), rest.toString());
                return true;
            }

            if (i >= actual.length) return false;

            if (seg.startsWith(":")) {
                out.put(
                        seg.substring(1),
                        java.net.URLDecoder.decode(
                                actual[i],
                                java.nio.charset.StandardCharsets.UTF_8
                        )
                );
            } else if (!seg.equals(actual[i])) {
                return false;
            }
        }

        return true;
    }
}