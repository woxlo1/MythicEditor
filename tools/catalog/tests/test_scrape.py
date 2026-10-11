import sys, unittest, tempfile
from unittest.mock import patch
import urllib.error
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import scrape_wiki as s

FIX = Path(__file__).parent / "fixtures"

class ScrapeTest(unittest.TestCase):
    def setUp(self):
        self.r = s.parse_page((FIX / "damage.html").read_text(encoding="utf-8"), "Damage")
        self.by = {p.split("|")[0].split(",")[0]: p for p in self.r["params"]}

    def test_all_rows_found_and_nav_table_ignored(self):
        self.assertEqual(len(self.r["params"]), 19)
        self.assertNotIn("navjunk", " ".join(self.r["params"]))

    def test_aliases_and_defaults(self):
        self.assertTrue(self.by["amount"].startswith("amount,a|"))
        self.assertTrue(self.by["ignoreArmor"].startswith("ignoreArmor,ia,i|boolean|false|"))
        self.assertIn("damagecause,dc,cause|string|entity_attack|", self.by["damagecause"])
        self.assertTrue(self.by["bonusdamage"].startswith("bonusdamage|string||"))

    def test_types(self):
        self.assertIn("|number|1|", self.by["amount"])
        self.assertIn("|boolean|true|", self.by["poweraffectsdamage"])

    def test_main_is_the_non_inheritable_attribute(self):
        self.assertEqual(self.r["main"], ["amount"])

    def test_mechanic_aliases_from_list_skip_checkbox(self):
        self.assertEqual(self.r["aliases"], ["d"])

    def test_spec_delimiter_never_leaks_from_descriptions(self):
        for p in self.r["params"]:
            self.assertEqual(p.count("|"), 3, p)

    def test_page_without_table_returns_none(self):
        self.assertIsNone(s.parse_page("<h1>X</h1><p>no tables</p>", "X"))

    def test_failed_refresh_preserves_last_known_good_records(self):
        previous = {"damage": {"name": "damage", "params": ["amount|number|1|Damage"]},
                    "heal": {"name": "heal", "params": ["amount|number|1|Healing"]}}
        refreshed = {"damage": {"name": "damage", "params": ["amount|number|2|Updated damage"]}}
        merged = s.merge_preserving_success(previous, refreshed)
        self.assertEqual(merged["damage"]["params"], ["amount|number|2|Updated damage"])
        self.assertIn("heal", merged, "a failed page fetch must not erase the offline catalog record")

    def test_empty_parameter_table_is_not_a_successful_parameter_parse(self):
        html = "<h1>Parameterless</h1><table><tr><th>Name</th><th>Description</th></tr></table>"
        self.assertIsNone(s.parse_page(html, "Parameterless"))

    def test_fetch_result_distinguishes_http_404(self):
        with patch.object(s.urllib.request, "urlopen", side_effect=urllib.error.HTTPError("https://x", 404, "missing", {}, None)):
            html, status, detail = s.fetch_result("https://x", Path(tempfile.gettempdir()) / "mythiceditor-no-cache.html", True, retries=1)
        self.assertIsNone(html)
        self.assertEqual(status, "http_404")
        self.assertIn("404", detail)

    def test_fetch_result_distinguishes_network_error(self):
        with patch.object(s.urllib.request, "urlopen", side_effect=OSError("temporary DNS failure")):
            html, status, detail = s.fetch_result("https://x", Path(tempfile.gettempdir()) / "mythiceditor-no-cache.html", True, retries=1)
        self.assertIsNone(html)
        self.assertEqual(status, "network_error")
        self.assertIn("DNS failure", detail)

    def test_fetch_result_uses_stale_cache_when_refresh_fails(self):
        with tempfile.TemporaryDirectory() as d:
            cache = Path(d) / "cached.html"
            cache.write_text("<h1>known good</h1>", encoding="utf-8")
            with patch.object(s.urllib.request, "urlopen", side_effect=OSError("offline")):
                html, status, detail = s.fetch_result("https://x", cache, True, retries=1)
            self.assertEqual(html, "<h1>known good</h1>")
            self.assertEqual(status, "stale_cache")
            self.assertIn("retained cached HTML", detail)

    def test_fetch_result_uses_cache_without_network(self):
        with tempfile.TemporaryDirectory() as d:
            cache = Path(d) / "cached.html"
            cache.write_text("cached", encoding="utf-8")
            with patch.object(s.urllib.request, "urlopen", side_effect=AssertionError("network should not be used")):
                self.assertEqual(s.fetch_result("https://x", cache)[0:2], ("cached", "cached"))

class ConditionLayoutTest(unittest.TestCase):
    """Conditions use one plain attribute table (no Non-Inheritable/Inheritable split) - layout taken from the live wiki."""
    def setUp(self):
        self.r = s.parse_page((FIX / "health.html").read_text(encoding="utf-8"), "health")

    def test_single_table(self):
        self.assertEqual([p.split("|")[0] for p in self.r["params"]], ["health,h,amount,a", "includeabsorption,ia"])

    def test_defaults_and_types(self):
        self.assertTrue(self.r["params"][1].startswith("includeabsorption,ia|boolean|false|"))

    def test_entry_aliases_and_main(self):
        self.assertEqual(self.r["aliases"], ["hp"])
        self.assertEqual(self.r["main"], ["health"])


if __name__ == "__main__":
    unittest.main()
