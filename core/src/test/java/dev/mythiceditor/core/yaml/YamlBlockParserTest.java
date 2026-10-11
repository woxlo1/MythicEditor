package dev.mythiceditor.core.yaml;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class YamlBlockParserTest {
    @Test
    void splitsMultipleTopLevelEntries() {
        String yaml = """
            # a comment
            FireBall:
              Skills:
              - damage{amount=10} @Target

            Heal:
              Skills:
              - heal{amount=5} @Self
            """;
        YamlBlockParser.Parsed p = YamlBlockParser.parse(yaml);
        assertEquals(2, p.blocks().size());
        assertEquals("FireBall", p.blocks().get(0).id());
        assertEquals("Heal", p.blocks().get(1).id());
        assertTrue(p.problems().isEmpty());
    }

    @Test
    void preservesExactTextOfOneBlock() {
        String yaml = "A:\n  X: 1\nB:\n  Y: 2\n";
        YamlBlockParser.Parsed p = YamlBlockParser.parse(yaml);
        YamlBlockParser.Block b = p.blocks().get(1);
        assertEquals("B:\n  Y: 2", yaml.substring(b.start(), b.end()));
    }

    @Test
    void handlesQuotedKeys() {
        String yaml = "\"My Skill\":\n  Skills: []\n";
        YamlBlockParser.Parsed p = YamlBlockParser.parse(yaml);
        assertEquals(1, p.blocks().size());
        assertEquals("My Skill", p.blocks().get(0).id());
    }

    @Test
    void detectsCrlf() {
        String yaml = "A:\r\n  X: 1\r\n";
        YamlBlockParser.Parsed p = YamlBlockParser.parse(yaml);
        assertEquals("\r\n", p.eol());
    }

    @Test
    void ignoresCommentsAndBlankLinesBetweenEntries() {
        String yaml = "A:\n  X: 1\n\n# separator\n\nB:\n  Y: 2\n";
        YamlBlockParser.Parsed p = YamlBlockParser.parse(yaml);
        assertEquals(2, p.blocks().size());
    }

    @Test
    void flagsUnexpectedTopLevelContent() {
        String yaml = "A:\n  X: 1\n- oops\nB:\n  Y: 2\n";
        YamlBlockParser.Parsed p = YamlBlockParser.parse(yaml);
        assertFalse(p.problems().isEmpty());
    }
}
