#!/usr/bin/env python3
"""Fetch the full official MythicMobs skill parameter catalog.

Run from the repository root with Python 3.10+ and network access:
    python tools/catalog/fetch_parameters.py

The script intentionally does not invent parameters when a page cannot be read.
It fetches per-entry pages, merges the scraped metadata, builds the bundled JSON,
and writes an audit report listing every unresolved page/parameter gap.
"""
from __future__ import annotations
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "tools" / "catalog"


def run(args: list[str]) -> int:
    print("\n$", " ".join(args), flush=True)
    result = subprocess.run(args, cwd=ROOT)
    return result.returncode


def main() -> int:
    py = sys.executable
    # The scraper uses the standard library, caches downloaded HTML and retries
    # transient failures. Running all three sections is required for completeness.
    if run([py, str(TOOLS / "scrape_wiki.py"), "--workers", "4", "--delay", "0.2"]):
        print("Scraping reported an error; continuing to build a partial catalog for audit.")
    if run([py, str(TOOLS / "build_catalog.py")]):
        return 1
    code = run([py, str(TOOLS / "audit_catalog.py")])
    report = ROOT / "tools" / "catalog" / "data" / "params" / "parameter-refresh-report.txt"
    print(f"\nAudit report: {report}")
    if code:
        print("INCOMPLETE: some official pages or per-entry parameter tables are still missing. "
              "Inspect the report, fix the page/parser issue, then rerun this command.")
    else:
        print("Catalog name counts and aliases passed audit. Review parameter coverage in the report; "
              "an entry with no table may be parameterless or may need manual review.")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
