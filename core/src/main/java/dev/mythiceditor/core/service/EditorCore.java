package dev.mythiceditor.core.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Logger;

import dev.mythiceditor.core.audit.ChangeLog;
import dev.mythiceditor.core.catalog.CatalogService;
import dev.mythiceditor.core.config.EditorSettings;
import dev.mythiceditor.core.graph.RelationGraph;
import dev.mythiceditor.core.index.FileWatcher;
import dev.mythiceditor.core.index.ProjectIndex;
import dev.mythiceditor.core.index.ProjectLayout;
import dev.mythiceditor.core.index.RefScanner;
import dev.mythiceditor.core.mythic.MythicAdapter;
import dev.mythiceditor.core.security.PathGuard;
import dev.mythiceditor.core.storage.BackupManager;
import dev.mythiceditor.core.storage.FileStore;
import dev.mythiceditor.core.web.EditorHttpServer;
import dev.mythiceditor.core.web.MainThreadRunner;
import dev.mythiceditor.core.web.PermissionChecker;
import dev.mythiceditor.core.web.RateLimiter;
import dev.mythiceditor.core.web.CloudflareDeployer;
import dev.mythiceditor.core.web.RelayClient;
import dev.mythiceditor.core.web.RelayState;
import dev.mythiceditor.core.web.SessionManager;

/**
 * Wires every backend component together and owns the process-wide resources (thread pool, HTTP server,
 * file watcher). One instance lives for the lifetime of the plugin; {@link #shutdown()} releases everything.
 */
public final class EditorCore implements AutoCloseable {
    public final EditorSettings settings;
    public final PathGuard pathGuard;
    public final ProjectLayout layout;
    public final CatalogService catalog;
    public final ProjectIndex index;
    public final FileWatcher watcher;
    public final ChangeLog changeLog;
    public final BackupManager backups;
    public final FileStore store;
    public final RelationGraph graph;
    public final SessionManager sessions;
    public final RateLimiter apiLimiter;
    public final RateLimiter writeLimiter;
    public final RateLimiter loginLimiter;
    public final MythicAdapter mythic;
    public final ScheduledExecutorService scheduler;
    private final EditorHttpServer http;
    private final Logger log;
    private volatile RelayClient relay;

    public EditorCore(EditorSettings settings, Path mythicFolder, MythicAdapter mythic, PermissionChecker perms,
                       MainThreadRunner mainThread, Logger log, String bootMessage) throws IOException {
        this.settings = settings;
        this.log = log;
        this.mythic = mythic;

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "MythicEditor-Worker");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newScheduledThreadPool(Math.max(2, settings.threads / 2), tf);

        this.pathGuard = new PathGuard(mythicFolder);
        this.layout = new ProjectLayout(settings, pathGuard.root());
        this.catalog = new CatalogService(resolveCatalogOverride(mythicFolder));
        this.catalog.applyRuntime(mythic.runtimeCatalog());

        RefScanner scanner = new RefScanner(catalog);
        this.index = new ProjectIndex(layout, pathGuard, scanner);
        this.watcher = new FileWatcher(layout, index, settings.watch, settings.rescanSeconds, log);
        this.changeLog = new ChangeLog(500);
        index.addListener(list -> {
            for (ProjectIndex.Change c : list) {
                changeLog.add("filesystem", c.action(), null, null, c.file());
            }
        });

        this.backups = new BackupManager(pathGuard.root(), settings.backupDir, settings.backupRetention);
        this.store = new FileStore(settings, layout, pathGuard, index, backups, changeLog, log);
        this.graph = new RelationGraph(index);

        this.sessions = new SessionManager(scheduler, settings.sessionTtlMinutes, settings.sessionMaxLifetimeMinutes,
            settings.ticketTtlSeconds, settings.singleUseTicket, settings.maxSessionsPerPlayer);
        this.apiLimiter = new RateLimiter(settings.rateApiPerMinute);
        this.writeLimiter = new RateLimiter(settings.rateWritePerMinute);
        this.loginLimiter = new RateLimiter(settings.rateLoginPerMinute);
        scheduler.scheduleWithFixedDelay(() -> {
            apiLimiter.sweep();
            writeLimiter.sweep();
            loginLimiter.sweep();
        }, 5, 5, java.util.concurrent.TimeUnit.MINUTES);

        watcher.rescanNow();

        this.http = new EditorHttpServer(settings, this, perms, mainThread, log, bootMessage);
    }

    private static Path resolveCatalogOverride(Path mythicFolder) {
        Path p = mythicFolder.resolve("../MythicEditor/catalog-override.json").normalize();
        return Files.isRegularFile(p) ? p : null;
    }

    public void start() throws IOException {
        String problem = settings.relayProblem();
        if (problem != null) throw new IOException("Invalid relay settings: " + problem);
        watcher.start();
        http.start();
        if (settings.relayEnabled && !settings.relayApiToken.isEmpty()) provisionRelay();
        if (settings.relayEnabled && (settings.relayUrl.isBlank() || settings.relaySecret.length() < 16)) {
            log.severe("Relay is enabled but has no url/secret (the automatic deploy failed, see above). The editor is only reachable locally.");
        } else if (settings.relayEnabled) {
            relay = new RelayClient(settings.relayUrl, settings.relaySecret, settings.relayServerId, http.boundPort(), settings.maxRequestBytes, log);
            relay.start();
            log.info("Relay mode: the editor is reached through " + settings.relayUrl);
            for (String friend : settings.relayFriends) {
                log.info("Relay: settings for friend server \"" + friend + "\" - put this in THEIR config.yml:\n"
                    + "relay:\n  enabled: true\n  url: \"" + settings.relayUrl + "\"\n  secret: \""
                    + RelayClient.serverSecret(settings.relaySecret, friend) + "\"\n  server-id: \"" + friend + "\"");
            }
        }
    }

    /**
     * Deploys (or updates) the Worker this plugin carries into the owner's Cloudflare account. Runs at startup and
     * only does work when there is no saved state yet or the embedded Worker changed (plugin update). Never throws:
     * a failure is logged and the previous url/secret, if any, keep being used.
     */
    private void provisionRelay() {
        try {
            String source;
            try (java.io.InputStream in = EditorCore.class.getResourceAsStream("/relay/worker.js")) {
                if (in == null) throw new IOException("this plugin jar does not contain relay/worker.js");
                source = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            String hash = sha256(source);
            RelayState st = RelayState.load(settings.relayStateFile);
            String secret = !settings.relaySecret.isBlank() ? settings.relaySecret
                : st != null ? st.secret() : randomSecret();
            boolean upToDate = st != null && hash.equals(st.workerHash()) && settings.relayWorkerName.equals(st.workerName())
                && secret.equals(st.secret());
            String url = !settings.relayUrl.isBlank() ? settings.relayUrl : st != null ? st.url() : "";
            if (!upToDate || url.isBlank()) {
                log.info("Relay: deploying the MythicEditor Worker to your Cloudflare account ...");
                CloudflareDeployer.Result r = new CloudflareDeployer(settings.relayApiToken)
                    .deploy(settings.relayAccountId, settings.relayWorkerName, secret, source, m -> log.info("Relay: " + m));
                if (url.isBlank()) url = r.url();
                st = new RelayState(url, secret, hash, settings.relayWorkerName);
                if (settings.relayStateFile != null) st.save(settings.relayStateFile);
                log.info("Relay: Worker is ready at " + url + ". You can remove relay.api-token from config.yml now"
                    + " (the generated secret is kept in relay.json).");
            }
            settings.relayUrl = url;
            settings.relaySecret = secret;
            settings.finishRelay();
        } catch (IOException | RuntimeException e) {
            log.severe("Relay: automatic Worker deploy failed: " + e.getMessage());
            // fall back to whatever url/secret exist already (config.yml or an earlier relay.json)
            settings.applyRelayState();
        }
    }

    private static String sha256(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String randomSecret() {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        return sb.toString();
    }

    /** True when relay mode is on and the plugin currently has its link to the Worker open. */
    public boolean relayConnected() {
        RelayClient r = relay;
        return r != null && r.connected();
    }

    public String issueEditorUrl(java.util.UUID playerId, String playerName) {
        SessionManager.Ticket t = sessions.issueTicket(playerId, playerName);
        String prefix = settings.relayEnabled && !settings.relayServerId.isEmpty() ? "/s/" + settings.relayServerId : "";
        return http.publicUrl() + prefix + "/editor/" + t.token();
    }

    public void refreshMythicRuntimeCatalog() {
        catalog.applyRuntime(mythic.runtimeCatalog());
    }

    public int port() {
        return http.boundPort();
    }

    public String publicUrl() {
        return http.publicUrl();
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        RelayClient r = relay;
        if (r != null) r.close();
        try {
            http.stop();
        } catch (RuntimeException e) {
            log.warning("Error stopping HTTP server: " + e.getMessage());
        }
        watcher.close();
        sessions.close();
        scheduler.shutdownNow();
    }
}
