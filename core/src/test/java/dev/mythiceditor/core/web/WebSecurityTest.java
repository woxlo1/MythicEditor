package dev.mythiceditor.core.web;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.junit.jupiter.api.Test;

import dev.mythiceditor.core.config.EditorSettings;

class WebSecurityTest {
    private static final List<String> NONE = List.of();

    @Test
    void originMustMatchTheHost() {
        assertTrue(EditorHttpServer.originAllowed("https://x.lhr.life", "x.lhr.life", NONE, ""));
        assertTrue(EditorHttpServer.originAllowed("http://localhost:8765", "localhost:8765", NONE, ""));
        assertTrue(EditorHttpServer.originAllowed("https://x.lhr.life:443", "x.lhr.life", NONE, ""));
        assertTrue(EditorHttpServer.originAllowed("https://X.LHR.life", "x.lhr.life", NONE, ""));
    }

    @Test
    void originRejectsEverythingElse() {
        assertFalse(EditorHttpServer.originAllowed(null, "x.lhr.life", NONE, ""), "writes must carry an Origin");
        assertFalse(EditorHttpServer.originAllowed(" ", "x.lhr.life", NONE, ""));
        assertFalse(EditorHttpServer.originAllowed("https://evil.example", "x.lhr.life", NONE, ""));
        assertFalse(EditorHttpServer.originAllowed("http://localhost:9999", "localhost:8765", NONE, ""), "other port");
        assertFalse(EditorHttpServer.originAllowed("null", "x.lhr.life", NONE, ""));
        assertFalse(EditorHttpServer.originAllowed("https://x.lhr.life/evil", "x.lhr.life", NONE, ""));
        assertFalse(EditorHttpServer.originAllowed("https://x.lhr.life", null, NONE, ""));
        assertFalse(EditorHttpServer.originAllowed("https://x.lhr.life.evil.example", "x.lhr.life", NONE, ""));
    }

    @Test
    void explicitlyConfiguredOriginsAreAccepted() {
        assertTrue(EditorHttpServer.originAllowed("https://pub.example", "internal:8765", NONE, "https://pub.example"));
        assertTrue(EditorHttpServer.originAllowed("https://ok.example", "internal:8765", List.of("https://ok.example"), ""));
    }

    @Test
    void unopenedTicketsDieWithTheirPlayerVisit() {
        ScheduledExecutorService exec = Executors.newScheduledThreadPool(1);
        try {
            SessionManager sm = new SessionManager(exec, 60, 480, 300, true, 2);
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            var ta = sm.issueTicket(alice, "alice");
            var tb = sm.issueTicket(bob, "bob");
            sm.invalidateForPlayer(alice);
            assertNull(sm.redeem(ta.token()));
            assertNotNull(sm.redeem(tb.token()));
            assertNull(sm.redeem(tb.token()), "single use");
            sm.close();
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void publicSessionIdHidesTheToken() {
        String id = SessionManager.publicId("secret-token-123");
        assertEquals(12, id.length());
        assertEquals(id, SessionManager.publicId("secret-token-123"));
        assertNotEquals(id, SessionManager.publicId("secret-token-124"));
        assertFalse("secret-token-123".startsWith(id));
    }

    @Test
    void rateLimiterCountsPerKey() {
        RateLimiter rl = new RateLimiter(3);
        assertTrue(rl.allow("ip"));
        assertTrue(rl.allow("ip"));
        assertTrue(rl.allow("ip"));
        assertFalse(rl.allow("ip"));
        assertTrue(rl.allow("other"));
    }

    @Test
    void securitySettingsParse() {
        EditorSettings d = EditorSettings.fromMap(Map.of());
        assertTrue(d.csp);
        assertTrue(d.restrictedMechanics.contains("command"));
        EditorSettings s = EditorSettings.fromMap(Map.of("security",
            Map.of("csp", false, "restricted-mechanics", List.of(" Command ", "Foo", ""))));
        assertFalse(s.csp);
        assertEquals(List.of("command", "foo"), s.restrictedMechanics);
        assertTrue(EditorSettings.fromMap(Map.of("security", Map.of("restricted-mechanics", List.of()))).restrictedMechanics.isEmpty());
    }
}
