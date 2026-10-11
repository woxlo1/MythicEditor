#!/usr/bin/env python3
"""Extracts every parameter (attribute) of every Mechanic / Condition / Targeter from the MythicCraft wiki.

Why a scraper: each wiki page documents its attributes in tables shaped like

    | Attribute | Aliases | Description | Default |

(split into "Non-Inheritable Attributes" / "Inheritable Attributes" sub-sections on most mechanics) and lists
extra names of the mechanic itself under an "Aliases" heading. That layout is regular, so the ~600 pages are
parsed mechanically instead of being copied by hand.

Input   data/mechanics.txt, data/conditions.txt, data/targeters.txt   (the entry names; see build_catalog.py)
Output  data/params/mechanics.json, conditions.json, targeters.json
        { "<name lower>": { "name": ..., "url": ..., "aliases": [...], "params": ["names|type|default|desc", ...],
                            "main": [...] } }
Cache   .cache/<kind>/<name>.html  (re-runs only fetch what is missing; use --refresh to re-download)

Usage
    python3 tools/catalog/scrape_wiki.py                       # everything (network needed, ~600 requests)
    python3 tools/catalog/scrape_wiki.py --only damage,heal    # a few entries
    python3 tools/catalog/scrape_wiki.py --kind conditions     # one kind
    python3 tools/catalog/scrape_wiki.py --from-dir saved/     # parse already-saved <name>.html files, no network
    python3 tools/catalog/build_catalog.py                     # then merge into the bundled catalog

Only the Python standard library is used.
"""
import argparse, json, re, sys, time, urllib.error, urllib.parse, urllib.request
from datetime import datetime, timezone
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
from pathlib import Path

HERE = Path(__file__).parent
WIKI = "https://wiki.mythiccraft.io/mythicmobs"
DATA = HERE / "data"
OUT = DATA / "params"
CACHE = HERE / ".cache"

KINDS = {  # kind -> (names file, url template, column holding the entry name)
    "mechanics": ("mechanics.txt", WIKI + "/Skills/Mechanics/{name}", 1),
    "conditions": ("conditions.txt", WIKI + "/Skills/Conditions/{name}", 1),
    "targeters": ("targeters.txt", WIKI + "/Skills/Targeters/{name}", 1),
}

NAME_HEADERS = {"attribute", "attributes", "argument", "option", "parameter", "name", "key"}


# ---------------------------------------------------------------------------------------------------------- HTML
class _Page(HTMLParser):
    """Collects headings, tables (rows of cell texts, with the nearest preceding heading) and list items."""

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.heading = ""          # nearest heading text so far
        self.tables = []           # {"heading": str, "rows": [[cell, ...], ...]}
        self.lists = []            # {"heading": str, "items": [str, ...]}
        self._buf = None           # text buffer of the element being collected
        self._tag = None
        self._row = None
        self._table = None
        self._li = None
        self._skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ("script", "style", "nav"):
            self._skip += 1
        if self._skip:
            return
        if tag in ("h1", "h2", "h3", "h4"):
            self._buf, self._tag = [], tag
        elif tag == "table":
            self._table = {"heading": self.heading, "rows": []}
        elif tag == "tr" and self._table is not None:
            self._row = []
        elif tag in ("td", "th") and self._row is not None:
            self._buf, self._tag = [], tag
        elif tag == "li":
            self._li = []
            self._buf, self._tag = self._li, "li"
        elif tag == "br" and self._buf is not None:
            self._buf.append(" ")

    def handle_endtag(self, tag):
        if tag in ("script", "style", "nav"):
            self._skip = max(0, self._skip - 1)
            return
        if self._skip:
            return
        if tag in ("h1", "h2", "h3", "h4") and self._tag == tag:
            self.heading = _clean("".join(self._buf))
            self._buf = self._tag = None
        elif tag in ("td", "th") and self._tag == tag and self._row is not None:
            self._row.append(_clean("".join(self._buf)))
            self._buf = self._tag = None
        elif tag == "tr" and self._row is not None and self._table is not None:
            if self._row:
                self._table["rows"].append(self._row)
            self._row = None
        elif tag == "table" and self._table is not None:
            self.tables.append(self._table)
            self._table = None
        elif tag == "li" and self._tag == "li":
            text = _clean("".join(self._li or []))
            if text:
                if not self.lists or self.lists[-1]["heading"] != self.heading:
                    self.lists.append({"heading": self.heading, "items": []})
                self.lists[-1]["items"].append(text)
            self._buf = self._tag = self._li = None

    def handle_data(self, data):
        if self._skip:
            return
        if self._buf is not None:
            self._buf.append(data)


def _clean(s):
    s = s.replace("\\_", "_").replace("\\", "").replace("\u00a0", " ")
    return re.sub(r"\s+", " ", s).strip()


# ------------------------------------------------------------------------------------------------- interpretation
def _split_names(cell):
    cell = cell.replace("`", "")
    return [n for n in re.split(r"[,/\s]+", cell) if n and n not in ("-", "—", "none", "N/A", "n/a")]


def infer_type(names, default, desc):
    """Best-effort catalog type. Rich types (enum/skill/...) are normally supplied by base-catalog.json."""
    n = {x.lower() for x in names}
    d = (default or "").strip().lower()
    t = (desc or "").lower()
    if d in ("true", "false"):
        return "boolean"
    if n & {"onstart", "ontick", "onend", "onhit", "onhitskill", "oncollide", "onland", "onbounce", "onskill",
            "oninterval", "onmiss", "onattack", "ondamaged", "ondeath", "skill", "metaskill", "s", "ms"} \
            and ("skill" in t or "metaskill" in t):
        return "skill"
    if n & {"material", "block", "blocktype", "item", "m", "b"} and ("material" in t or "block" in t or "item" in t):
        return "material" if "material" in t or "block" in t else "string"
    if n & {"particle", "p"} and "particle" in t:
        return "particle"
    if n & {"sound", "s"} and "sound" in t:
        return "sound"
    if n & {"color", "c"} and "color" in t:
        return "color"
    if re.fullmatch(r"-?\d+", d):
        return "integer" if re.search(r"\bticks?\b|\bnumber of\b|\bcount\b|\binterval\b", t) else "number"
    if re.fullmatch(r"-?\d*\.\d+", d):
        return "number"
    if re.search(r"\bwhether\b|\btrue or false\b|\bshould (the|it)\b", t):
        return "boolean"
    return "string"


def _spec(names, typ, default, desc):
    desc = desc.replace("|", "/")
    if len(desc) > 400:
        desc = desc[:397].rstrip() + "..."
    return f"{','.join(names)}|{typ}|{default.replace('|', '/')}|{desc}"


def parse_page(html, display_name):
    """-> dict(aliases=[...], params=[spec,...], main=[...]) or None when the page has no attribute table."""
    p = _Page()
    p.feed(html)
    params, seen_names, main = [], set(), []
    for tb in p.tables:
        rows = tb["rows"]
        if len(rows) < 2:
            continue
        header = [h.lower().strip() for h in rows[0]]
        if not header or header[0] not in NAME_HEADERS:
            continue
        col = {h: i for i, h in enumerate(header)}
        i_alias = next((col[k] for k in ("aliases", "alias", "shorthand") if k in col), None)
        i_desc = next((col[k] for k in ("description", "desc", "info") if k in col), None)
        i_def = next((col[k] for k in ("default", "defaults", "default value") if k in col), None)
        i_type = next((col[k] for k in ("type", "values", "value") if k in col), None)
        non_inheritable = "non-inheritable" in tb["heading"].lower() or "non inheritable" in tb["heading"].lower()
        for r in rows[1:]:
            if len(r) < 2:
                continue
            primary = _split_names(r[0])
            if not primary:
                continue
            names = primary[:1]
            if i_alias is not None and i_alias < len(r):
                names += _split_names(r[i_alias])
            names += primary[1:]
            uniq = []
            for n in names:
                if n.lower() not in {u.lower() for u in uniq}:
                    uniq.append(n)
            if uniq[0].lower() in seen_names:
                continue
            seen_names.add(uniq[0].lower())
            desc = r[i_desc] if i_desc is not None and i_desc < len(r) else ""
            default = r[i_def] if i_def is not None and i_def < len(r) else ""
            default = "" if default.strip() in ("-", "—", "none", "None", "N/A") else default.replace("`", "")
            hinted = r[i_type].lower().strip() if i_type is not None and i_type < len(r) else ""
            typ = {"boolean": "boolean", "bool": "boolean", "integer": "integer", "int": "integer",
                   "number": "number", "float": "number", "double": "number"}.get(hinted) \
                or infer_type(uniq, default, desc)
            params.append(_spec(uniq, typ, default, desc))
            if non_inheritable and len(main) < 2:
                main.append(uniq[0].lower())
    if not main and params:
        main = [params[0].split("|", 1)[0].split(",")[0].lower()]

    aliases = []
    for lst in p.lists:
        if lst["heading"].lower() in ("aliases", "alias"):
            for item in lst["items"]:
                item = re.sub(r"^\[[ xX]?\]\s*", "", item)       # task-list markers "[x] d"
                for a in _split_names(item):
                    if a.lower() != display_name.lower() and a not in aliases:
                        aliases.append(a)
    if not params and not aliases:
        return None
    return {"aliases": aliases, "params": params, "main": main}


# ------------------------------------------------------------------------------------------------------- driver
def merge_preserving_success(existing, newly_parsed):
    """Merge a refresh into the last known-good data without deleting records on fetch failures."""
    merged = dict(existing)
    merged.update(newly_parsed)
    return dict(sorted(merged.items()))


def read_names(kind):
    fname, tmpl, col = KINDS[kind]
    out = []
    for line in (DATA / fname).read_text(encoding="utf-8").splitlines():
        if line.strip() and not line.startswith("#"):
            out.append(line.split("|")[col])
    return out


def fetch_result(url, cache_file, refresh=False, retries=3):
    """Fetch a page and preserve the failure category instead of conflating 404/network/parser errors.

    Returns (html, status, detail). A missing page is never silently interpreted as a
    parameterless feature. A cached last-known-good page remains available on transient errors.
    """
    if cache_file.exists() and not refresh:
        return cache_file.read_text(encoding="utf-8"), "cached", "Used previously downloaded HTML cache."
    req = urllib.request.Request(url, headers={"User-Agent": "MythicEditor-catalog-builder/1.0 (+offline docs index)"})
    last = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                html = r.read().decode("utf-8", "replace")
            cache_file.parent.mkdir(parents=True, exist_ok=True)
            cache_file.write_text(html, encoding="utf-8")
            return html, "fetched", f"HTTP {getattr(r, 'status', 200)}"
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None, "http_404", f"HTTP 404 for {url}"
            last = f"HTTP {e.code}: {e.reason}"
        except Exception as e:  # DNS failure, timeout, TLS error, etc.
            last = f"{type(e).__name__}: {e}"
        if attempt + 1 < retries:
            time.sleep(1.5 * (attempt + 1))
    if cache_file.exists():
        return cache_file.read_text(encoding="utf-8"), "stale_cache", f"Refresh failed ({last}); retained cached HTML."
    return None, "network_error", f"Failed after {retries} attempts: {last}"


def fetch(url, cache_file, refresh, retries=3):
    """Backward-compatible HTML-only wrapper for small scripts and tests."""
    html, _status, _detail = fetch_result(url, cache_file, refresh, retries)
    return html


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--kind", choices=list(KINDS), action="append", help="limit to a kind (repeatable)")
    ap.add_argument("--only", help="comma separated entry names (case-insensitive)")
    ap.add_argument("--from-dir", help="directory with saved <name>.html files (no network)")
    ap.add_argument("--refresh", action="store_true", help="re-download even if cached")
    ap.add_argument("--delay", type=float, default=0.15, help="seconds between requests per worker")
    ap.add_argument("--workers", type=int, default=4)
    a = ap.parse_args()
    only = {x.strip().lower() for x in a.only.split(",")} if a.only else None
    OUT.mkdir(parents=True, exist_ok=True)

    for kind in a.kind or list(KINDS):
        _, tmpl, _ = KINDS[kind]
        names = [n for n in read_names(kind) if not only or n.lower() in only]
        result, missing, empty, parse_failed = {}, [], [], []
        status_path = OUT / f"{kind}-scrape-status.json"
        previous_status = json.loads(status_path.read_text(encoding="utf-8")) if status_path.exists() else {}
        item_status = dict(previous_status.get("entries", {}))

        def work(name):
            time.sleep(a.delay)
            if a.from_dir:
                f = Path(a.from_dir) / f"{name}.html"
                html = f.read_text(encoding="utf-8") if f.exists() else None
                return name, html, ("local_file" if html is not None else "source_missing"), (f"Loaded {f}" if html is not None else f"No local source file: {f}")
            return (name, *fetch_result(tmpl.format(name=urllib.parse.quote(name)), CACHE / kind / f"{name}.html", a.refresh))

        with ThreadPoolExecutor(max_workers=1 if a.from_dir else a.workers) as ex:
            for name, html, fetch_status, fetch_detail in ex.map(work, names):
                url = tmpl.format(name=urllib.parse.quote(name))
                if html is None:
                    missing.append(name)
                    item_status[name.lower()] = {
                        "name": name, "url": url, "status": fetch_status,
                        "checkedAt": datetime.now(timezone.utc).isoformat(),
                        "detail": fetch_detail + " No parameterless conclusion is possible."
                    }
                    continue
                parsed = parse_page(html, name)
                if parsed is None:
                    page = _Page()
                    page.feed(html)
                    state = "no_parameter_table" if not page.tables else "parse_failed"
                    (empty if state == "no_parameter_table" else parse_failed).append(name)
                    item_status[name.lower()] = {
                        "name": name, "url": url, "status": state, "fetchStatus": fetch_status,
                        "checkedAt": datetime.now(timezone.utc).isoformat(),
                        "detail": fetch_detail + " No attribute table was detected; manual review is required before calling this parameterless."
                    }
                    continue
                result[name.lower()] = {"name": name, "url": url, **parsed}
                item_status[name.lower()] = {
                    "name": name, "url": url, "status": "parsed_with_parameters" if parsed.get("params") else "parsed_alias_only",
                    "checkedAt": datetime.now(timezone.utc).isoformat(),
                    "parameterCount": len(parsed.get("params", [])),
                    "detail": "Parser extracted data; this does not by itself establish version completeness."
                }

        # Preserve previously successful records when a refresh fails. A network outage must never
        # silently erase the last known-good offline catalog. New successful records always win.
        target = OUT / f"{kind}.json"
        if target.exists():
            previous = json.loads(target.read_text(encoding="utf-8"))
            result = merge_preserving_success(previous, result)
        target.write_text(json.dumps(dict(sorted(result.items())), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        status_doc = {
            "kind": kind,
            "source": "https://wiki.mythiccraft.io/mythicmobs/Skills/" + kind.title(),
            "schemaVersion": 2,
            "runMode": "local-cache" if a.from_dir else ("refresh" if a.refresh else "cache-first"),
            "checkedAt": datetime.now(timezone.utc).isoformat(),
            "requestedEntries": len(names),
            "entries": item_status,
            "summary": {
                "parsedNow": len([v for v in item_status.values() if v.get("status") in ("parsed_with_parameters", "parsed_alias_only") and v.get("checkedAt", "")[:10] == datetime.now(timezone.utc).date().isoformat()]),
                "notFetchedThisRun": len(missing), "http404ThisRun": sum(1 for v in item_status.values() if v.get("status") == "http_404" and v.get("checkedAt", "")[:10] == datetime.now(timezone.utc).date().isoformat()),
                "networkErrorThisRun": sum(1 for v in item_status.values() if v.get("status") == "network_error" and v.get("checkedAt", "")[:10] == datetime.now(timezone.utc).date().isoformat()),
                "staleCacheUsedThisRun": sum(1 for v in item_status.values() if v.get("status") == "stale_cache" and v.get("checkedAt", "")[:10] == datetime.now(timezone.utc).date().isoformat()),
                "noParameterTableThisRun": len(empty), "parseFailedThisRun": len(parse_failed)
            }
        }
        status_path.write_text(json.dumps(status_doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        total = sum(len(v["params"]) for v in result.values())
        print(f"{kind}: {len(result)} pages with data, {total} parameters -> {target.relative_to(HERE.parent.parent)}")
        if empty:
            print(f"  no attribute table (likely parameterless or a different layout): {len(empty)}")
            print("   ", ", ".join(empty[:60]) + (" ..." if len(empty) > 60 else ""))
        if missing:
            print(f"  not fetched / 404 / network failure: {len(missing)}")
            print("   ", ", ".join(missing[:60]) + (" ..." if len(missing) > 60 else ""))
        if parse_failed:
            print(f"  parser failures (HTML had tables but no parameter rows were recognized): {len(parse_failed)}")
            print("   ", ", ".join(parse_failed[:60]) + (" ..." if len(parse_failed) > 60 else ""))
        print(f"  per-entry status: {status_path.relative_to(HERE.parent.parent)}")


if __name__ == "__main__":
    main()
