package dev.mythiceditor.core.security;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ContentGuardTest {
    private final ContentGuard guard = new ContentGuard(List.of("command", "cmd", "consolecommand"));
    private static final String CLEAN = "Boss:\n  Skills:\n  - message{msg=\"hi\"} @self ~onSpawn\n  - damage{a=5} @target\n";
    private static final String WITH_CMD = "Boss:\n  Skills:\n  - command{c=\"op steve\"} @self ~onSpawn\n";

    @Test
    void ordinaryMechanicsAreNotFlagged() {
        assertTrue(guard.restrictedLines(CLEAN).isEmpty());
    }

    @Test
    void detectsCommandMechanicAliasAndCase() {
        assertEquals(1, guard.restrictedLines(WITH_CMD).size());
        assertEquals(1, guard.restrictedLines("S:\n  Skills:\n  - cmd{c=\"x\"}\n").size());
        assertEquals(1, guard.restrictedLines("S:\n  Skills:\n  - CoMmAnD{c=\"x\"}\n").size());
    }

    @Test
    void cannotBeHiddenWithYamlTricks() {
        assertEquals(1, guard.restrictedLines("S:\n  Skills:\n  - 'command{c=\"x\"}'\n").size());
        assertEquals(1, guard.restrictedLines("S:\n  Skills:\n  - \"\\u0063ommand{c=\\\"x\\\"}\"\n").size());
        assertEquals(1, guard.restrictedLines("S:\n  Skills:\n    |\n      - command{c=\"x\"}\n      - message{msg=1}\n").size());
        assertEquals(1, guard.restrictedLines("S:\n  Skills: [\"command{c=x}\", \"message{msg=1}\"]\n").size());
    }

    @Test
    void doesNotFlagLookalikes() {
        assertTrue(guard.restrictedLines("S:\n  Display: Command Center\n").isEmpty());
        assertTrue(guard.restrictedLines("S:\n  Skills:\n  - commandfoo{x=1}\n").isEmpty());
    }

    @Test
    void onlyNewRestrictedLinesCount() {
        assertTrue(guard.added(WITH_CMD, WITH_CMD + "  - message{msg=1}\n").isEmpty(), "untouched existing line stays editable");
        assertEquals(1, guard.added(WITH_CMD, WITH_CMD.replace("op steve", "op alex")).size(), "edited command line is new");
        assertEquals(1, guard.added(CLEAN, WITH_CMD).size(), "brand new command line");
        assertEquals(1, guard.added(WITH_CMD, WITH_CMD + "  - command{c=\"op steve\"} @self ~onSpawn\n").size(), "a second copy is new");
        assertTrue(guard.added(WITH_CMD, CLEAN).isEmpty(), "removing is always fine");
    }

    @Test
    void invalidYamlAndDisabledGuardYieldNothing() {
        assertTrue(guard.restrictedLines("a: [unclosed").isEmpty());
        assertTrue(new ContentGuard(List.of()).restrictedLines(WITH_CMD).isEmpty());
    }
}
