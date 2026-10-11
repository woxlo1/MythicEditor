package dev.mythiceditor.core.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** Real HTTP round trips: compression, validators and cache headers (what makes the editor usable through a tunnel). */
class TransferEfficiencyTest {
    @TempDir
    Path dir;
    HttpServer server;
    HttpClient client = HttpClient.newHttpClient();
    String base;
    static final String BIG_JS = "console.log('hello world');\n".repeat(400);
    static final String BIG_JSON = "{\"items\":[" + "{\"id\":\"SomeSkillName\",\"file\":\"Skills/File.yml\"},".repeat(120) + "{}]}";

    @BeforeEach
    void start() throws Exception {
        Files.createDirectories(dir.resolve("assets"));
        Files.writeString(dir.resolve("index.html"), "<!doctype html><html><body>" + "x".repeat(900) + "</body></html>");
        Files.writeString(dir.resolve("assets/app-abc123.js"), BIG_JS);
        Files.write(dir.resolve("assets/logo-abc123.png"), new byte[2000]);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/data", ex -> {
            Ctx ctx = new Ctx(ex, Map.of());
            if (ctx.notModified("W/\"v1\"")) return;
            ctx.sendBytes(200, "application/json; charset=utf-8", BIG_JSON.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/api/small", ex -> new Ctx(ex, Map.of()).sendBytes(200, "application/json", "{\"ok\":true}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/", new StaticAssets(dir, true));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<byte[]> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return client.send(b.GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String gunzip(byte[] b) throws IOException {
        return new String(new GZIPInputStream(new ByteArrayInputStream(b)).readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void staticFilesAreGzippedWhenAccepted() throws Exception {
        HttpResponse<byte[]> r = get("/assets/app-abc123.js", "Accept-Encoding", "gzip");
        assertEquals(200, r.statusCode());
        assertEquals("gzip", r.headers().firstValue("Content-Encoding").orElse(""));
        assertTrue(r.body().length < BIG_JS.length() / 5, "should be much smaller than " + BIG_JS.length() + " but is " + r.body().length);
        assertEquals(BIG_JS, gunzip(r.body()));
        assertTrue(r.headers().firstValue("Vary").orElse("").contains("Accept-Encoding"));
    }

    @Test
    void staticFilesStayPlainWithoutAcceptEncoding() throws Exception {
        HttpResponse<byte[]> r = get("/assets/app-abc123.js");
        assertTrue(r.headers().firstValue("Content-Encoding").isEmpty());
        assertEquals(BIG_JS, new String(r.body(), StandardCharsets.UTF_8));
    }

    @Test
    void hashedAssetsAreCachedForAYearButTheEntryPageIsRevalidated() throws Exception {
        assertEquals("public, max-age=31536000, immutable", get("/assets/app-abc123.js").headers().firstValue("Cache-Control").orElse(""));
        assertEquals("no-cache", get("/").headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void revalidationIsAnEmpty304() throws Exception {
        HttpResponse<byte[]> first = get("/assets/app-abc123.js");
        String etag = first.headers().firstValue("ETag").orElseThrow();
        HttpResponse<byte[]> again = get("/assets/app-abc123.js", "If-None-Match", etag);
        assertEquals(304, again.statusCode());
        assertEquals(0, again.body().length);
        HttpResponse<byte[]> gz = get("/assets/app-abc123.js", "Accept-Encoding", "gzip", "If-None-Match", etag);
        assertEquals(304, gz.statusCode(), "same validator for the gzip variant");
        assertEquals(200, get("/assets/app-abc123.js", "If-None-Match", "W/\"other\"").statusCode());
    }

    @Test
    void missingHashedAssetIs404NotIndexHtml() throws Exception {
        assertEquals(404, get("/assets/gone-ffffff.js").statusCode());
        HttpResponse<byte[]> deepLink = get("/skills/Foo");
        assertEquals(200, deepLink.statusCode(), "client-side routes still get the app");
        assertTrue(new String(deepLink.body(), StandardCharsets.UTF_8).contains("<html>"));
    }

    @Test
    void imagesAreNotRecompressed() throws Exception {
        HttpResponse<byte[]> r = get("/assets/logo-abc123.png", "Accept-Encoding", "gzip");
        assertTrue(r.headers().firstValue("Content-Encoding").isEmpty());
        assertEquals(2000, r.body().length);
    }

    @Test
    void jsonIsCompressedAndRevalidatable() throws Exception {
        HttpResponse<byte[]> r = get("/api/data", "Accept-Encoding", "gzip");
        assertEquals("gzip", r.headers().firstValue("Content-Encoding").orElse(""));
        assertTrue(r.body().length < BIG_JSON.length() / 4);
        assertEquals(BIG_JSON, gunzip(r.body()));
        assertEquals("private, no-cache", r.headers().firstValue("Cache-Control").orElse(""), "handler's cache policy must survive");
        assertEquals(304, get("/api/data", "If-None-Match", "W/\"v1\"").statusCode());
        assertEquals(200, get("/api/data", "If-None-Match", "W/\"v0\"").statusCode());
    }

    @Test
    void tinyResponsesAreNotCompressedAndStayUncacheable() throws Exception {
        HttpResponse<byte[]> r = get("/api/small", "Accept-Encoding", "gzip");
        assertTrue(r.headers().firstValue("Content-Encoding").isEmpty());
        assertEquals("{\"ok\":true}", new String(r.body(), StandardCharsets.UTF_8));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
    }

    @Test
    void securityHeadersAreStillSent() throws Exception {
        HttpResponse<byte[]> r = get("/");
        assertTrue(r.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
    }
}
