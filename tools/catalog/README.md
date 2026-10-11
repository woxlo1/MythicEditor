# Catalog scraper and audit

This toolchain collects MythicMobs wiki metadata without inventing missing attributes.

## Commands

Run from the repository root:

```bash
python tools/catalog/scrape_wiki.py --kind mechanics --kind conditions --kind targeters
python tools/catalog/build_catalog.py
python tools/catalog/audit_catalog.py
```

Use `--only damage,heal` to refresh selected names, `--refresh` to bypass cached HTML, or `--from-dir <directory>` to parse saved HTML offline. The parser uses only Python's standard library.

## Status semantics

- `parsed_with_parameters`: page parsing found one or more attribute rows.
- `parsed_alias_only`: an alias was found but no attribute rows were parsed; this is not proof that the entry has no parameters.
- `no_parameter_table`: no table was detected; requires manual review.
- `parse_failed`: the HTML contained a table, but the parser could not extract recognized attributes.
- `not_fetched`: the page was missing, returned an HTTP error, or could not be retrieved.
- `untracked`: there is no successful or failed per-entry scrape record.

A refresh merges newly parsed records with existing successful records so a transient outage cannot erase offline data. Every run writes `data/params/<kind>-scrape-status.json`. The audit report distinguishes bundled names, parameter metadata currently present in the bundled catalog, and page-level extraction status. It exits non-zero when the catalog count check or scrape coverage is incomplete.

## Important limits

The wiki is versioned and can contain addon-provided entries. A bundled name is not proof of compatibility with every MythicMobs version. Placeholder names currently come from a curated source list; exhaustive parsing of placeholder argument signatures and full version-by-version schema validation remain outstanding.


## Failure categories and offline safety

The scraper now records transport and parser states separately:

- `http_404`: the official endpoint explicitly returned 404.
- `network_error`: retries failed (DNS, timeout, TLS, or another transport failure).
- `stale_cache`: refresh failed but a prior HTML cache was retained and parsed.
- `cached` / `local_file`: existing cache or an explicitly supplied offline source was used.
- `no_parameter_table`: a page was retrieved but no parameter table was recognized; this is **not** proof that the feature has no parameters.
- `parse_failed`: tables exist but the expected parameter-table layout was not recognized.
- `parsed_with_parameters` / `parsed_alias_only`: extraction state, not a guarantee of version completeness.

Run `python tools/catalog/fetch_parameters.py` with network access to update all three catalogs, then inspect `tools/catalog/data/params/parameter-refresh-report.txt`. A non-zero audit result is intentional while any official item remains unverified. The existing bundled catalog remains usable offline.
