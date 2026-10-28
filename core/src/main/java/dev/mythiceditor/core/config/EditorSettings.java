package dev.mythiceditor.core.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Plain-Java view of config.yml. No Bukkit types so the core can run standalone (tests, dev server). */
public final class EditorSettings {
    public String bindAddress = "0.0.0.0";
    public int port = 8765;
    public String publicUrl = "";
    public int maxRequestBytes = 2 * 1024 * 1024;
    public int threads = 8;

    public int sessionTtlMinutes = 60;
    public int sessionMaxLifetimeMinutes = 480;
    public int ticketTtlSeconds = 300;
    public boolean singleUseTicket = true;
    public boolean invalidateOnQuit = true;
    public int maxSessionsPerPlayer = 2;

    public int rateApiPerMinute = 900;
    public int rateWritePerMinute = 60;
    public int rateLoginPerMinute = 20;
    public List<String> allowedOrigins = new ArrayList<>();
    public boolean trustProxyHeaders = false;
    /** Send a Content-Security-Policy header with every response. */
    public boolean csp = true;
    /**
     * Mechanic names (lower case) that run server commands. Only players with mythiceditor.unrestricted may add
     * new lines using them; everyone else with mythiceditor.edit can still edit the rest of the entry.
     */
    public List<String> restrictedMechanics = new ArrayList<>(List.of(
        "command", "cmd", "consolecommand", "playercommand", "runcommand", "executecommand"));

    /**
     * Relay mode: the plugin connects OUT to a Cloudflare Worker (relay-worker/), which forwards browser requests
     * to it. No port has to be opened. relay.url is the Worker's address, relay.secret must equal its RELAY_SECRET.
     */
    public boolean relayEnabled = false;
    public String relayUrl = "";
    public String relaySecret = "";
    /**
     * Name of this server on a Worker that is shared by several servers. Empty = the Worker's default server
     * (secret = the Worker's RELAY_SECRET, plain links). Otherwise secret = what make-secret.js prints for this id,
     * and links look like /s/&lt;id&gt;/editor/&lt;ticket&gt;.
     */
    public String relayServerId = "";
    /**
     * Optional: a Cloudflare API token ("Edit Cloudflare Workers" template). With it the plugin deploys the Worker
     * it carries (resource relay/worker.js) into your account by itself, generates the secret when none is set and
     * keeps url/secret in relay.json next to config.yml. The token is only used at startup and is never stored.
     */
    public String relayApiToken = "";
    /** Only needed when the token can see several Cloudflare accounts. */
    public String relayAccountId = "";
    public String relayWorkerName = "mythiceditor";
    /** Owner's server only: names of friends' servers. Their secrets are printed to the console at startup. */
    public List<String> relayFriends = new ArrayList<>();
    /** relay.json next to config.yml (null when settings were not loaded from a file). */
    public java.nio.file.Path relayStateFile;
    private boolean publicUrlConfigured;

    public String mythicFolder = "";
    /** content type (mobs, skills, items, droptables) -> folder patterns relative to the MythicMobs folder */
    public Map<String, List<String>> paths = new LinkedHashMap<>();
    public Set<String> editable = new LinkedHashSet<>(List.of("mobs", "skills"));
    public boolean reloadOnSave = false;
    public String reloadCommand = "mythicmobs reload";

    public String backupDir = "backups";
    public int backupRetention = 200;
    public String defaultSkillFile = "Skills/MythicEditor.yml";
    public String defaultMobFile = "Mobs/MythicEditor.yml";

    public boolean watch = true;
    public int rescanSeconds = 30;

    public boolean autosave = false;
    public int autosaveDelayMs = 3000;

    public boolean debug = false;

    public EditorSettings() {
        paths.put("mobs", List.of("Mobs", "Packs/*/Mobs"));
        paths.put("skills", List.of("Skills", "Packs/*/Skills"));
        paths.put("items", List.of("Items", "Packs/*/Items"));
        paths.put("droptables", List.of("Droptables", "Packs/*/Droptables"));
    }

    public static EditorSettings load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new EditorSettings();
        }
        LoaderOptions lo = new LoaderOptions();
        lo.setMaxAliasesForCollections(10);
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object o = new Yaml(new SafeConstructor(lo)).load(r);
            EditorSettings s = fromMap(o instanceof Map<?, ?> m ? m : Map.of());
            s.relayStateFile = file.resolveSibling("relay.json");
            s.applyRelayState();
            return s;
        }
    }

    /** Fills relay.url / relay.secret from relay.json (written by the automatic deploy) when config.yml leaves them empty. */
    public void applyRelayState() {
        if (!relayEnabled || relayStateFile == null) return;
        dev.mythiceditor.core.web.RelayState st = dev.mythiceditor.core.web.RelayState.load(relayStateFile);
        if (st != null) {
            if (relayUrl.isBlank()) relayUrl = st.url();
            if (relaySecret.isBlank()) relaySecret = st.secret();
        }
        finishRelay();
    }

    /** Settings that follow from relay mode; call again whenever relayUrl changes. */
    public void finishRelay() {
        if (!relayEnabled) return;
        // Relayed requests arrive from 127.0.0.1 carrying the real client address / https flag in X-Forwarded-*.
        trustProxyHeaders = true;
        // The editor links and the Origin check must use the public (Worker) address.
        if (!publicUrlConfigured && !relayUrl.isBlank()) publicUrl = relayUrl;
        // If relay is enabled and bind-address is unspecified / 0.0.0.0 / ::, force to 127.0.0.1
        if (bindAddress == null || bindAddress.isBlank() || "0.0.0.0".equals(bindAddress) || "::".equals(bindAddress) || "[::]".equals(bindAddress)) {
            bindAddress = "127.0.0.1";
            // Log via system logger is not directly available here; caller/logging will see via boot message - but requirement says "INFO 1"
            System.out.println("[MythicEditor] INFO: relay enabled with bind-address 0.0.0.0/::, forcing bind-address to 127.0.0.1 for security.");
        } else {
            // If explicitly set to something else, respect but warn as per requirement says "、WARNING "
            // "。"
            // The requirement states: "、INFO 1。 、WARNING 。" So if user explicitly specified something else (not the defaults), warn.
            // Our condition above only triggers on defaults/unspecified; for others we should warn if different? Wait: "relay 、bind-address 、0.0.0.0 :: 、127.0.0.1 、INFO 1。 、WARNING 。"
            // So if it's not specified/was default AND is 0.0.0.0 or :: -> force to 127.0.0.1 and INFO. If user explicitly specified something else (e.g. 192.168.1.5) -> respect and WARNING.
            System.out.println("[MythicEditor] WARNING: relay enabled with non-loopback bind-address '" + bindAddress + "'; this may expose the local server. Ensure only trusted proxies can reach it.");
        }
    }

    public static EditorSettings fromMap(Map<?, ?> root) {
        EditorSettings s = new EditorSettings();
        Map<?, ?> web = map(root, "web");
        s.bindAddress = str(web, "bind-address", s.bindAddress);
        s.port = clamp(num(web, "port", s.port), 0, 65535);
        s.publicUrl = str(web, "public-url", s.publicUrl).trim().replaceAll("/+$", "");
        s.maxRequestBytes = clamp(num(web, "max-request-bytes", s.maxRequestBytes), 1024, 64 * 1024 * 1024);
        s.threads = clamp(num(web, "threads", s.threads), 1, 64);

        Map<?, ?> se = map(root, "session");
        s.sessionTtlMinutes = clamp(num(se, "ttl-minutes", s.sessionTtlMinutes), 1, 24 * 60);
        s.sessionMaxLifetimeMinutes = clamp(num(se, "max-lifetime-minutes", s.sessionMaxLifetimeMinutes), 1, 7 * 24 * 60);
        s.ticketTtlSeconds = clamp(num(se, "ticket-ttl-seconds", s.ticketTtlSeconds), 10, 24 * 3600);
        s.singleUseTicket = bool(se, "single-use-ticket", s.singleUseTicket);
        s.invalidateOnQuit = bool(se, "invalidate-on-quit", s.invalidateOnQuit);
        s.maxSessionsPerPlayer = clamp(num(se, "max-sessions-per-player", s.maxSessionsPerPlayer), 1, 20);

        Map<?, ?> sec = map(root, "security");
        Map<?, ?> rl = map(sec, "rate-limit");
        s.rateApiPerMinute = clamp(num(rl, "api-per-minute", s.rateApiPerMinute), 10, 100000);
        s.rateWritePerMinute = clamp(num(rl, "write-per-minute", s.rateWritePerMinute), 1, 100000);
        s.rateLoginPerMinute = clamp(num(rl, "login-per-minute", s.rateLoginPerMinute), 1, 100000);
        s.allowedOrigins = strList(sec.get("allowed-origins"));
        s.trustProxyHeaders = bool(sec, "trust-proxy-headers", false);
        s.csp = bool(sec, "csp", true);
        if (sec.containsKey("restricted-mechanics")) {
            s.restrictedMechanics = new ArrayList<>(strList(sec.get("restricted-mechanics")).stream()
                .map(x -> x.trim().toLowerCase(java.util.Locale.ROOT)).filter(x -> !x.isEmpty()).toList());
        }

        Map<?, ?> relay = map(root, "relay");
        s.relayEnabled = bool(relay, "enabled", false);
        s.relayUrl = str(relay, "url", "").trim().replaceAll("/+$", "");
        s.relaySecret = str(relay, "secret", "").trim();
        s.relayServerId = str(relay, "server-id", "").trim().toLowerCase(java.util.Locale.ROOT);
        s.relayApiToken = str(relay, "api-token", "").trim();
        s.relayAccountId = str(relay, "account-id", "").trim();
        String wn = str(relay, "worker-name", "mythiceditor").trim().toLowerCase(java.util.Locale.ROOT);
        s.relayWorkerName = wn.isEmpty() ? "mythiceditor" : wn;
        s.relayFriends = strList(relay.get("friends")).stream().map(x -> x.trim().toLowerCase(java.util.Locale.ROOT))
            .filter(x -> !x.isEmpty()).distinct().toList();
        s.publicUrlConfigured = !s.publicUrl.isBlank();
        s.finishRelay();

        Map<?, ?> mm = map(root, "mythicmobs");
        s.mythicFolder = str(mm, "folder", "").trim();
        Map<?, ?> paths = map(mm, "paths");
        if (!paths.isEmpty()) {
            s.paths = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : paths.entrySet()) {
                s.paths.put(String.valueOf(e.getKey()).toLowerCase(), strList(e.getValue()));
            }
        }
        if (mm.containsKey("editable")) {
            s.editable = new LinkedHashSet<>(strList(mm.get("editable")).stream().map(String::toLowerCase).toList());
        }
        s.reloadOnSave = bool(mm, "reload-on-save", false);
        s.reloadCommand = str(mm, "reload-command", s.reloadCommand).trim();

        Map<?, ?> st = map(root, "storage");
        s.backupDir = str(st, "backup-dir", s.backupDir).trim();
        s.backupRetention = clamp(num(st, "backup-retention", s.backupRetention), 1, 100000);
        s.defaultSkillFile = str(st, "default-skill-file", s.defaultSkillFile).trim();
        s.defaultMobFile = str(st, "default-mob-file", s.defaultMobFile).trim();

        Map<?, ?> ix = map(root, "index");
        s.watch = bool(ix, "watch", true);
        s.rescanSeconds = clamp(num(ix, "rescan-seconds", s.rescanSeconds), 0, 86400);

        Map<?, ?> ed = map(root, "editor");
        s.autosave = bool(ed, "autosave", false);
        s.autosaveDelayMs = clamp(num(ed, "autosave-delay-ms", s.autosaveDelayMs), 500, 60000);

        s.debug = bool(map(root, "logging"), "debug", false);
        return s;
    }

    /** Why relay.* cannot be used, or null if it is fine. */
    public String relayProblem() {
        if (!relayEnabled) return null;
        if (!relayServerId.isEmpty() && !relayApiToken.isEmpty()) {
            return "relay.api-token deploys the Worker and belongs on the Worker owner's server only; remove relay.server-id or relay.api-token";
        }
        for (String f : relayFriends) {
            if (f.equals("main") || !f.matches("[a-z0-9][a-z0-9-]{1,30}")) {
                return "relay.friends entry \"" + f + "\" must be 2-31 characters (a-z, 0-9, '-') and not \"main\"";
            }
        }
        if (!relayFriends.isEmpty() && !relayServerId.isEmpty()) {
            return "relay.friends belongs on the Worker owner's server (no relay.server-id)";
        }
        if (!relayWorkerName.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            return "relay.worker-name may only contain a-z, 0-9 and '-'";
        }
        // With an api-token the plugin deploys the Worker and fills url/secret itself.
        if (relayApiToken.isEmpty()) {
            if (relayUrl.isBlank()) {
                return "relay.url must start with https:// (the address of your Cloudflare Worker), or set relay.api-token to let the plugin deploy it";
            }
            String lower = relayUrl.toLowerCase(java.util.Locale.ROOT);
            if (lower.startsWith("http://")) {
                // Allow only localhost for testing
                try {
                    java.net.URI u = java.net.URI.create(relayUrl);
                    String h = u.getHost();
                    if (!("localhost".equalsIgnoreCase(h) || "127.0.0.1".equals(h) || "[::1]".equals(h) || "::1".equals(h))) {
                        return "relay.url must use https:// (except http://localhost/127.0.0.1/[::1] for testing)";
                    }
                } catch (Exception e) {
                    return "relay.url must use https:// (except http://localhost/127.0.0.1/[::1] for testing)";
                }
            } else if (!lower.startsWith("https://")) {
                return "relay.url must start with https:// (the address of your Cloudflare Worker), or set relay.api-token to let the plugin deploy it";
            }
            if (relaySecret.length() < 16) return "relay.secret must be set to a long random value (at least 16 characters), or set relay.api-token";
        }
        if (!relayServerId.isEmpty() && (relayServerId.equals("main") || !relayServerId.matches("[a-z0-9][a-z0-9-]{1,30}"))) {
            return "relay.server-id must be 2-31 characters (a-z, 0-9, '-') and must not be \"main\"; leave it empty for the default server";
        }
        return null;
    }

    /** Content types map to singular "kind" ids used by the API: mobs -> mob. */
    public static String kindOf(String contentType) {
        return contentType.endsWith("s") ? contentType.substring(0, contentType.length() - 1) : contentType;
    }

    public boolean isEditable(String kind) {
        for (String e : editable) {
            if (kindOf(e).equals(kind)) return true;
        }
        return false;
    }

    private static Map<?, ?> map(Map<?, ?> m, String k) {
        Object o = m == null ? null : m.get(k);
        return o instanceof Map<?, ?> r ? r : Map.of();
    }

    private static String str(Map<?, ?> m, String k, String d) {
        Object o = m.get(k);
        return o == null ? d : String.valueOf(o);
    }

    private static int num(Map<?, ?> m, String k, int d) {
        Object o = m.get(k);
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException ignored) {
                // fall through to default
            }
        }
        return d;
    }

    private static boolean bool(Map<?, ?> m, String k, boolean d) {
        Object o = m.get(k);
        if (o instanceof Boolean b) return b;
        if (o != null) return Boolean.parseBoolean(String.valueOf(o));
        return d;
    }

    private static List<String> strList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof Iterable<?> it) {
            for (Object e : it) if (e != null) out.add(String.valueOf(e));
        }
        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
