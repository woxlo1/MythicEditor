package dev.mythiceditor.core.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Deploys the relay Worker (resource relay/worker.js) into the owner's Cloudflare account through the REST API,
 * the same thing {@code wrangler deploy} does: one multipart upload carrying the module, the Durable Object
 * binding + migration and the RELAY_SECRET secret, then the workers.dev route is switched on.
 *
 * <p>Needs an API token with "Workers Scripts: Edit" (the "Edit Cloudflare Workers" template). The token is only
 * held in memory for the duration of the call.
 */
public final class CloudflareDeployer {
    private static final String COMPAT_DATE = "2025-09-01";

    public record Result(String url, String accountId) {}

    private final String base;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CloudflareDeployer(String token) {
        this.token = token;
        // overridable for tests
        this.base = System.getProperty("mythiceditor.cloudflareApi", "https://api.cloudflare.com/client/v4").replaceAll("/+$", "");
    }

    public Result deploy(String accountIdOrBlank, String scriptName, String secret, String workerSource, Consumer<String> progress)
        throws IOException {
        String account = accountIdOrBlank == null || accountIdOrBlank.isBlank() ? findAccount() : accountIdOrBlank.trim();
        progress.accept("Cloudflare account " + account);

        JsonElement sub = call("GET", "/accounts/" + account + "/workers/subdomain", null, null).get("result");
        String subdomain = sub != null && sub.isJsonObject() && sub.getAsJsonObject().has("subdomain")
            && !sub.getAsJsonObject().get("subdomain").isJsonNull() ? sub.getAsJsonObject().get("subdomain").getAsString() : "";
        if (subdomain.isEmpty()) {
            throw new IOException("this account has no workers.dev subdomain yet - open \"Workers & Pages\" once in the Cloudflare dashboard to create it, then restart");
        }

        String path = "/accounts/" + account + "/workers/scripts/" + scriptName;
        try {
            upload(path, scriptName, secret, workerSource, true);
        } catch (IOException first) {
            // A Worker that was deployed before (e.g. with wrangler) already has the Durable Object class; the
            // migration must not be sent again then.
            String m = String.valueOf(first.getMessage()).toLowerCase(Locale.ROOT);
            if (!(m.contains("migration") || m.contains("tag"))) throw first;
            progress.accept("Worker already has its Durable Object class, updating without migration");
            upload(path, scriptName, secret, workerSource, false);
        }
        progress.accept("Worker script uploaded");

        JsonObject on = new JsonObject();
        on.addProperty("enabled", true);
        on.addProperty("previews_enabled", false);
        call("POST", path + "/subdomain", "application/json", on.toString().getBytes(StandardCharsets.UTF_8));

        return new Result("https://" + scriptName + "." + subdomain + ".workers.dev", account);
    }

    private String findAccount() throws IOException {
        JsonElement r = call("GET", "/accounts", null, null).get("result");
        JsonArray arr = r != null && r.isJsonArray() ? r.getAsJsonArray() : new JsonArray();
        if (arr.size() == 1) return arr.get(0).getAsJsonObject().get("id").getAsString();
        if (arr.isEmpty()) throw new IOException("the API token cannot see any Cloudflare account (set relay.account-id)");
        StringBuilder sb = new StringBuilder("the API token can see several accounts, set relay.account-id to one of:");
        for (JsonElement e : arr) {
            JsonObject a = e.getAsJsonObject();
            sb.append(' ').append(a.get("id").getAsString());
            if (a.has("name")) sb.append(" (").append(a.get("name").getAsString()).append(')');
        }
        throw new IOException(sb.toString());
    }

    private void upload(String path, String scriptName, String secret, String source, boolean withMigration) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("main_module", "worker.js");
        meta.addProperty("compatibility_date", COMPAT_DATE);
        JsonArray bindings = new JsonArray();
        JsonObject doBinding = new JsonObject();
        doBinding.addProperty("type", "durable_object_namespace");
        doBinding.addProperty("name", "RELAY");
        doBinding.addProperty("class_name", "Relay");
        bindings.add(doBinding);
        JsonObject sec = new JsonObject();
        sec.addProperty("type", "secret_text");
        sec.addProperty("name", "RELAY_SECRET");
        sec.addProperty("text", secret);
        bindings.add(sec);
        meta.add("bindings", bindings);
        if (withMigration) {
            JsonObject mig = new JsonObject();
            mig.addProperty("new_tag", "v1");
            JsonArray classes = new JsonArray();
            classes.add("Relay");
            mig.add("new_sqlite_classes", classes);
            meta.add("migrations", mig);
        }

        String boundary = "----MythicEditor" + UUID.randomUUID().toString().replace("-", "");
        String body = "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"metadata\"\r\n"
            + "Content-Type: application/json\r\n\r\n" + meta + "\r\n"
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"worker.js\"; filename=\"worker.js\"\r\n"
            + "Content-Type: application/javascript+module\r\n\r\n" + source + "\r\n"
            + "--" + boundary + "--\r\n";
        call("PUT", path, "multipart/form-data; boundary=" + boundary, body.getBytes(StandardCharsets.UTF_8));
    }

    private JsonObject call(String method, String path, String contentType, byte[] body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(Duration.ofSeconds(30))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
        if (contentType != null) b.header("Content-Type", contentType);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        JsonObject o;
        try {
            JsonElement e = JsonParser.parseString(res.body());
            o = e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            o = new JsonObject();
        }
        boolean ok = res.statusCode() / 100 == 2 && (!o.has("success") || o.get("success").getAsBoolean());
        if (!ok) {
            StringBuilder msg = new StringBuilder();
            JsonElement errs = o.get("errors");
            if (errs != null && errs.isJsonArray()) {
                for (JsonElement e : errs.getAsJsonArray()) {
                    if (e.isJsonObject() && e.getAsJsonObject().has("message")) {
                        if (msg.length() > 0) msg.append("; ");
                        msg.append(e.getAsJsonObject().get("message").getAsString());
                    }
                }
            }
            if (msg.length() == 0) msg.append("HTTP ").append(res.statusCode());
            if (res.statusCode() == 401 || res.statusCode() == 403) msg.append(" (check the API token's permissions)");
            throw new IOException(method + " " + path + ": " + msg);
        }
        return o;
    }
}
