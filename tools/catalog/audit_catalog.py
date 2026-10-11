#!/usr/bin/env python3
"""Audit the bundled MythicMobs catalog for name and parameter coverage.

This is deliberately strict about the known wiki index sizes, while distinguishing
entries with no entry-specific parameters from those whose parameter metadata has
not yet been collected. Run after refreshing the catalog to see the remaining gaps.
"""
from __future__ import annotations
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "core/src/main/resources/catalog/mythicmobs-5.json"
EXPECTED = {"mechanics": 303, "conditions": 197, "targeters": 93}


def names(entry: dict) -> set[str]:
    return {str(entry.get("name", "")).lower(), *(str(a).lower() for a in entry.get("aliases", []))}


def _source_line_count(path: Path) -> int:
    return sum(1 for line in path.read_text(encoding="utf-8").splitlines()
               if line.strip() and not line.lstrip().startswith("#") and not line.startswith("["))


def main() -> int:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    failed = False
    report_json = ROOT / "tools/catalog/data/params/catalog-audit-report.json"
    report_txt = ROOT / "tools/catalog/data/params/parameter-refresh-report.txt"
    param_dir = ROOT / "tools/catalog/data/params"
    rows = []
    print(f"Catalog: {CATALOG}")
    for section, expected in EXPECTED.items():
        entries = catalog.get(section, [])
        parameterized = [e for e in entries if e.get("params")]
        documented = [e for e in entries if e.get("doc")]
        seen: dict[str, str] = {}
        collisions = []
        for entry in entries:
            for name in names(entry):
                if name in seen and seen[name] != entry.get("name"):
                    collisions.append((name, seen[name], entry.get("name")))
                else:
                    seen[name] = entry.get("name", "<unnamed>")
        status = "OK" if len(entries) == expected else "FAIL"
        if len(entries) != expected:
            failed = True
        print(f"\n{section}: {len(entries)}/{expected} entries [{status}]")
        print(f"  documented: {len(documented)}/{len(entries)}")
        print(f"  with entry-specific parameters: {len(parameterized)}/{len(entries)}")
        attr_key = "mechanicAttributes" if section == "mechanics" else "targeterAttributes"
        print(f"  shared parameter definitions: {len(catalog.get(attr_key, [])) if section != 'conditions' else 0}")
        if collisions:
            failed = True
            print(f"  alias/name collisions: {len(collisions)}")
            for row in collisions[:15]: print("   -", " -> ".join(map(str, row)))

        scraped_file = param_dir / f"{section}.json"
        scraped = json.loads(scraped_file.read_text(encoding="utf-8")) if scraped_file.exists() else {}
        status_file = param_dir / f"{section}-scrape-status.json"
        status_doc = json.loads(status_file.read_text(encoding="utf-8")) if status_file.exists() else {"entries": {}}
        status_entries = status_doc.get("entries", {})
        statuses = {"parsed_with_parameters": 0, "parsed_alias_only": 0, "no_parameter_table": 0,
                    "parse_failed": 0, "not_fetched": 0, "untracked": 0}
        fetch_statuses = {"http_404": 0, "network_error": 0, "source_missing": 0,
                          "stale_cache": 0, "cached": 0, "fetched": 0, "local_file": 0}
        missing_params, unresolved = [], []
        for entry in entries:
            key_options = [entry.get("name", "").lower(), *[a.lower() for a in entry.get("aliases", [])]]
            hit = next((scraped[k] for k in key_options if k in scraped), None)
            status_record = next((status_entries[k] for k in key_options if k in status_entries), {})
            stat = status_record.get("status")
            fetch_stat = status_record.get("fetchStatus", stat)
            if fetch_stat in fetch_statuses:
                fetch_statuses[fetch_stat] += 1
            if hit and hit.get("params"):
                statuses["parsed_with_parameters"] += 1
            elif hit:
                statuses["parsed_alias_only"] += 1
                missing_params.append(entry.get("name", "<unnamed>"))
            elif stat in statuses:
                statuses[stat] += 1
                unresolved.append({"name": entry.get("name", "<unnamed>"), "status": stat})
                missing_params.append(entry.get("name", "<unnamed>"))
            else:
                statuses["untracked"] += 1
                unresolved.append({"name": entry.get("name", "<unnamed>"), "status": "untracked"})
                missing_params.append(entry.get("name", "<unnamed>"))
        print("  parse status: " + (", ".join(f"{k}={v}" for k, v in statuses.items() if v) or "none"))
        print("  fetch status: " + (", ".join(f"{k}={v}" for k, v in fetch_statuses.items() if v) or "none"))
        if missing_params:
            print(f"  parameter info unresolved / no parameters recorded ({len(missing_params)}):")
            for start in range(0, min(len(missing_params), 40), 8):
                print("   ", ", ".join(missing_params[start:start + 8]))
        rows.append({
            "section": section, "expectedCatalogCount": expected, "catalogCount": len(entries),
            "nameRegistered": len(entries), "documentedCount": len(documented),
            "entrySpecificParameterCount": len(parameterized),
            "fullyVerifiedCount": 0,
            "fullyVerifiedNote": "Not assigned until all official pages, version constraints, aliases, and parameter schemas for the section have been audited.",
            "officialIndexObservedCount": None,
            "officialIndexObservationStatus": "not_observed_live_in_this_run",
            "parameterScrapeWithRows": statuses["parsed_with_parameters"],
            "parameterScrapeAliasOnly": statuses["parsed_alias_only"],
            "noParameterTable": statuses["no_parameter_table"], "parseFailed": statuses["parse_failed"],
            "notFetched": statuses["not_fetched"], "untracked": statuses["untracked"],
            "fetchStatusCounts": fetch_statuses,
            "unresolvedEntries": unresolved, "aliasCollisions": [list(x) for x in collisions],
            "sourceIndexLineCount": _source_line_count(ROOT / "tools/catalog/data" / f"{section}.txt")
        })

    # Placeholder catalog has a separate source list, with [scoped]/[plain] markers.
    ph_file = ROOT / "tools/catalog/data/placeholders.txt"
    ph_source_count = _source_line_count(ph_file)
    ph_catalog = catalog.get("placeholders", [])
    ph_report = {"section": "placeholders", "sourceListRows": ph_source_count,
                 "catalogCount": len(ph_catalog), "nameRegistered": len(ph_catalog),
                 "officialIndexObservedCount": None,
                 "officialIndexObservationStatus": "not_observed_live_in_this_run",
                 "documentedCount": sum(1 for e in ph_catalog if e.get("doc")),
                 "parameterScrapeWithRows": None, "fullyVerifiedCount": 0,
                 "unresolvedCount": len(ph_catalog),
                 "note": "Placeholder rows are curated source-list entries; argument-level parsing is not implemented as a per-page scrape, so complete argument coverage is unverified."}
    print(f"\nplaceholders: {len(ph_catalog)} catalog entries; {ph_source_count} source rows (scoped entries expand to caster/target/trigger)")

    # Check required shared definitions independently of per-entry parameters.
    for key in ("mechanicAttributes", "targeterAttributes"):
        if not catalog.get(key):
            print(f"FAIL: shared definition {key} is missing")
            failed = True

    has_unresolved = any(r.get("unresolvedEntries") for r in rows) or bool(ph_report.get("unresolvedCount"))
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "catalogPath": str(CATALOG.relative_to(ROOT)),
        "officialExpectedCounts": EXPECTED,
        "sections": rows + [ph_report],
        "overall": {
            "auditPassed": not failed and not has_unresolved,
            "networkRefreshAttempted": any((param_dir / f"{k}-scrape-status.json").exists() for k in EXPECTED),
            "unresolvedEntryCount": sum(len(r.get("unresolvedEntries", [])) for r in rows) + ph_report["unresolvedCount"],
            "liveOfficialIndexCountsObserved": False
        },
        "limitations": [
            "Catalog list counts are compared with configured reference counts; they are not proof that all official pages were fetched.",
            "A page with no detected parameter table is not considered verified parameterless.",
            "Placeholder argument signatures are not exhaustively parsed by the per-entry parameter scraper."
        ]
    }
    report_json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = ["MythicEditor catalog audit", f"Generated: {report['generatedAt']}", "",
             "Counts below distinguish bundled names from per-page parameter extraction.", ""]
    for r in rows:
        lines += [f"{r['section']}: names {r['catalogCount']}/{r['expectedCatalogCount']} (configured reference; live official count not observed); "
                  f"entries with parameter metadata {r['entrySpecificParameterCount']}/{r['catalogCount']}; "
                  f"scraped with parameter rows {r['parameterScrapeWithRows']}; "
                  f"no table {r['noParameterTable']}; parse failed {r['parseFailed']}; "
                  f"not fetched {r['notFetched']}; untracked {r['untracked']}; fetch states {r['fetchStatusCounts']}"]
        if r["unresolvedEntries"]:
            lines.append("  Unresolved entries (name — status):")
            lines.extend(f"  - {x['name']} — {x['status']}" for x in r["unresolvedEntries"])
        lines.append("")
    lines += [f"placeholders: {len(ph_catalog)} catalog entries; {ph_source_count} source-list rows; live official index not observed.",
              "Placeholder argument/signature coverage is not yet audited per item.", "",
              "No-table and failed-fetch states are unresolved, not evidence of a parameterless feature."]
    report_txt.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\nJSON audit report: {report_json}")
    print(f"Text audit report: {report_txt}")
    print("Audit result:", "FAIL / INCOMPLETE" if failed or any(r["unresolvedEntries"] for r in rows) else "PASS")
    return 1 if failed or any(r["unresolvedEntries"] for r in rows) else 0


if __name__ == "__main__":
    raise SystemExit(main())
