#!/usr/bin/env python3
"""Builds core/src/main/resources/catalog/mythicmobs-5.json.

Inputs
  base-catalog.json   hand-written entries with full parameter definitions (the previous catalog)
  data/*.txt          names / aliases / descriptions extracted from the MythicMobs wiki
                      (Mechanics, Conditions, Targeters, Triggers, Placeholders, AI Goals, AI Targets indexes)

Merge rules: base entries are kept (they carry parameters); wiki names that match a base entry's name/alias
become aliases of it; unmatched wiki entries are added as documented entries without parameter definitions;
base entries the wiki does not list are kept and marked in their description.
Usage: python3 tools/catalog/build_catalog.py
"""
import json, re, sys, copy
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE.parent.parent / "core/src/main/resources/catalog/mythicmobs-5.json"
WIKI = "https://wiki.mythiccraft.io/mythicmobs"
NOT_LISTED = " (Not listed in the official wiki index; kept from the previous catalog.)"

def lines(name):
    return [l.rstrip("\n") for l in (HERE / "data" / name).read_text(encoding="utf-8").splitlines()
            if l.strip() and not l.startswith("#")]

def names_of(e):
    return {e["name"].lower()} | {a.lower() for a in e["aliases"]}

def add_alias(e, a):
    if a and a.lower() != e["name"].lower() and a.lower() not in {x.lower() for x in e["aliases"]}:
        e["aliases"].append(a)

def merge_section(base, docs, alias_map=None, renames=None, prefixes=None, exempt=()):
    """docs: list of dict(name, aliases, desc, cat, doc). Returns merged list."""
    alias_map = alias_map or {}
    existing = copy.deepcopy(base)
    for e in existing:
        e.setdefault("aliases", [])
    for old, new in (renames or {}).items():
        for e in existing:
            if e["name"].lower() == old.lower():
                add_alias(e, e["name"]); e["name"] = new
                e["aliases"] = [a for a in e["aliases"] if a.lower() != new.lower()]
    matched = set()
    added = []
    for d in docs:
        target = alias_map.get(d["name"].lower())
        if target:
            hits = [e for e in existing if e["name"].lower() == target.lower()]
        else:
            da = {a.lower() for a in d["aliases"]}
            hits = [e for e in existing if d["name"].lower() in names_of(e) or e["name"].lower() in da]
        hits = [h for h in hits if h in existing]
        if not hits:
            e = {"name": d["name"], "aliases": list(d["aliases"]), "desc": d["desc"], "params": [], "main": []}
            if d.get("cat"): e["cat"] = d["cat"]
            e["doc"] = d["doc"]
            existing.append(e); matched.add(id(e)); added.append(e)
            continue
        hits.sort(key=lambda h: (h["name"].lower() != d["name"].lower(), not h.get("params")))
        keep = hits[0]
        for other in hits[1:]:  # absorb duplicates (e.g. Mob -> Self)
            add_alias(keep, other["name"])
            for a in other["aliases"]: add_alias(keep, a)
            if not keep.get("params") and other.get("params"):
                keep["params"], keep["main"] = other["params"], other.get("main", [])
            existing.remove(other)
        add_alias(keep, d["name"])
        for a in d["aliases"]: add_alias(keep, a)
        keep["doc"] = d["doc"]
        if not keep.get("cat") and d.get("cat"): keep["cat"] = d["cat"]
        matched.add(id(keep))
    # names beat aliases; first alias owner wins
    all_names = {e["name"].lower() for e in existing}
    seen = {}
    for e in existing:
        clean = []
        for a in e["aliases"]:
            la = a.lower()
            if la in all_names and la != e["name"].lower():
                print(f"  alias dropped (collides with entry name): {e['name']} -> {a}")
                continue
            if la in seen and seen[la] is not e:
                print(f"  alias dropped (already used by {seen[la]['name']}): {e['name']} -> {a}")
                continue
            seen[la] = e; clean.append(a)
        e["aliases"] = clean
    for e in existing:
        if id(e) not in matched and not e["name"].lower().startswith(tuple(exempt)) and NOT_LISTED not in e["desc"]:
            e["desc"] += NOT_LISTED
            print(f"  not in wiki: {e['name']}")
    return existing, added

# ---------------------------------------------------------------- load
base = json.loads((HERE / "base-catalog.json").read_text(encoding="utf-8"))
for k in ("mechanics", "targeters", "conditions"):
    pass

# pre-fixes for entries that contradict the wiki
for e in base["mechanics"]:
    if e["name"] == "projectile": e["aliases"] = [a for a in e["aliases"] if a != "shoot"]   # 'shoot' is its own mechanic
    if e["name"] == "giveitem": e["aliases"] = []
for e in base["targeters"]:
    if e["name"] == "Vehicle": e["aliases"] = [a for a in e["aliases"] if a != "Mount"]       # 'Mount' is its own targeter

mech_docs = []
for l in lines("mechanics.txt"):
    cat, name, desc = l.split("|", 2)
    mech_docs.append(dict(name=name, aliases=[], desc=desc, cat=cat, doc=f"{WIKI}/Skills/Mechanics/{name}"))
cond_docs = []
for l in lines("conditions.txt"):
    typ, name, desc = l.split("|", 2)
    cond_docs.append(dict(name=name, aliases=[], desc=desc, cat=typ, doc=f"{WIKI}/Skills/Conditions/{name}"))
targ_docs = []
for l in lines("targeters.txt"):
    grp, name, sh, desc = l.split("|", 3)
    targ_docs.append(dict(name=name, aliases=[s for s in sh.split(",") if s], desc=desc, cat=grp, doc=f"{WIKI}/Skills/Targeters/{name}"))
trig_docs = []
for l in lines("triggers.txt"):
    name, desc = l.split("|", 1)
    trig_docs.append(dict(name=name, aliases=[], desc=desc, cat=None, doc=f"{WIKI}/Skills/Triggers/{name}"))

print("mechanics:"); mech, mech_new = merge_section(base["mechanics"], mech_docs, alias_map={
    "particle": "effect:particles", "particlering": "effect:particlering", "particleline": "effect:particleline",
    "particlesphere": "effect:particlesphere", "particletornado": "effect:particletornado",
    "fakelightning": "effect:lightning", "fakeexplosion": "effect:explosion", "percentdamage": "damagepercent",
}, exempt=("effect:",))
print("conditions:"); cond, cond_new = merge_section(base["conditions"], cond_docs, alias_map={"isflying": "flying"})
print("targeters:"); targ, targ_new = merge_section(base["targeters"], targ_docs, renames={
    "RandomLocationNearCaster": "RandomLocationsNearCaster", "RandomLocationNearTarget": "RandomLocationsNearTargets"})
print("triggers:"); trig, trig_new = merge_section(base["triggers"], trig_docs)
# existing triggers have no 'params'; drop empty params/main from trigger entries
for t in trig: t.pop("params", None); t.pop("main", None)


# ---------------------------------------------------------------- scraped parameters (scrape_wiki.py)
RICH_PLAIN = ("string", "string!")

def spec_names(spec):
    return [n.strip() for n in spec.split("|", 1)[0].split(",") if n.strip()]

def merge_params(entry, scraped):
    """Wiki parameter tables are authoritative for *which* parameters exist and their names/aliases/defaults.
    The hand-written base entry keeps authority over richer typing (enum:/skill/mob/required...), because a
    scraper can only guess those. Base-only parameters (not on the wiki page) are kept, never dropped."""
    base = list(entry.get("params") or [])
    base_by_name = {}
    for b in base:
        for n in spec_names(b):
            base_by_name[n.lower()] = b
    out, used = [], set()
    for w in scraped["params"]:
        wn = spec_names(w)
        b = next((base_by_name[n.lower()] for n in wn if n.lower() in base_by_name), None)
        wparts = w.split("|", 3)
        if b is None:
            out.append(w); continue
        used.add(id(b))
        bparts = b.split("|", 3)
        names, _seen = [], set()                                    # wiki order first, then base-only aliases (case-insensitive)
        for n in wn + spec_names(b):
            if n.lower() not in _seen:
                _seen.add(n.lower()); names.append(n)
        typ = bparts[1] if bparts[1] not in RICH_PLAIN or bparts[1].endswith("!") else wparts[1]
        if bparts[1] in RICH_PLAIN and bparts[1] != "string!": typ = wparts[1]
        default = wparts[2] if wparts[2] else bparts[2]
        desc = wparts[3] if wparts[3] else bparts[3]
        out.append(f"{','.join(names)}|{typ}|{default}|{desc}")
    for b in base:
        if id(b) not in used and not any(set(map(str.lower, spec_names(b))) & set(map(str.lower, spec_names(o))) for o in out):
            out.append(b)
    entry["params"] = out
    if not entry.get("main"):
        entry["main"] = [m for m in scraped.get("main", []) if any(m.lower() == n.lower() for o in out for n in spec_names(o))]
    for a in scraped.get("aliases", []):
        add_alias(entry, a)

def apply_scraped(entries, kind):
    f = HERE / "data" / "params" / f"{kind}.json"
    if not f.exists():
        return 0
    scraped = json.loads(f.read_text(encoding="utf-8"))
    n = 0
    for e in entries:
        hit = next((scraped[k.lower()] for k in [e["name"], *e["aliases"]] if k.lower() in scraped), None)
        if hit:
            merge_params(e, hit); n += 1
    print(f"  parameters merged from data/params/{kind}.json: {n} entries, {sum(len(v['params']) for v in scraped.values())} parameters")
    return n

print("scraped parameters:")
for _kind, _entries in (("mechanics", mech), ("conditions", cond), ("targeters", targ)):
    apply_scraped(_entries, _kind)

# ---------------------------------------------------------------- named lists (AI, placeholders)
def named_merge(base_list, docs, doc_base, exempt=()):
    out = copy.deepcopy(base_list); idx = {e["name"].lower(): e for e in out}; hit = set()
    for name, desc, extra in docs:
        e = idx.get(name.lower())
        if e is None:
            e = {"name": name, "desc": desc, **extra}; out.append(e); idx[name.lower()] = e
        e["doc"] = doc_base.format(name=name) if doc_base else None
        if not e["doc"]: e.pop("doc")
        hit.add(name.lower())
    for e in out:
        if e["name"].lower() not in hit and not e["name"].lower().startswith(tuple(exempt)) and NOT_LISTED not in e["desc"]:
            e["desc"] += NOT_LISTED; print(f"  not in wiki: {e['name']}")
    return out

goals, targets = [], []
cur = None
for l in lines("ai.txt"):
    if l == "[goals]": cur = goals; continue
    if l == "[targets]": cur = targets; continue
    name, desc = l.split("|", 1); cur.append((name, desc, {}))
print("aiGoals:"); ai_goals = named_merge(base["aiGoals"], goals, f"{WIKI}/Mobs/ai/goals/{{name}}", exempt=("clear",))
print("aiTargets:"); ai_targets = named_merge(base["aiTargets"], targets, f"{WIKI}/Mobs/ai/targets/{{name}}", exempt=("clear",))

SCOPES = ["caster", "target", "trigger"]
ph_docs, mode = [], None
for l in lines("placeholders.txt"):
    if l in ("[scoped]", "[plain]"): mode = l; continue
    name, typ, desc = l.split("|", 2)
    if mode == "[scoped]":
        for s in SCOPES: ph_docs.append((f"{s}.{name}", desc, {"type": typ} if typ else {}))
    else:
        ph_docs.append((name, desc, {"type": typ} if typ else {}))
print("placeholders:")
base_ph = [p for p in base["placeholders"] if p["name"] != "caster.var." and p["name"] not in ("target.var.", "skill.var.", "global.var.", "world.var.")]
placeholders = named_merge(base_ph, ph_docs, f"{WIKI}/Skills/Placeholders", exempt=("owner.", "parent."))

# ---------------------------------------------------------------- attributes & small tables
attr, cur = {}, None
for l in lines("attributes.txt"):
    m = re.fullmatch(r"\[(.+)\]", l)
    if m: cur = m.group(1); attr[cur] = []; continue
    attr[cur].append(l)
enums = dict(base["enums"])
for k in list(attr):
    if k.startswith("enum:"):
        enums[k[5:]] = [v for v in attr.pop(k)[0].split(",")]
def pairs(key):
    return [dict(name=a.split("|", 1)[0], desc=a.split("|", 1)[1]) for a in attr[key]]

out = {
    "_comment": base["_comment"] + " Updated from the MythicCraft wiki indexes (see _sources): entries carrying a 'doc' URL are documented there; entries without parameter definitions only have name/aliases/description.",
    "mythicmobsVersion": base["mythicmobsVersion"],
    "_sources": [f"{WIKI}/Skills/Mechanics", f"{WIKI}/Skills/conditions", f"{WIKI}/Skills/Targeters",
                 f"{WIKI}/Skills/Triggers", f"{WIKI}/Skills/Placeholders", f"{WIKI}/ai-goals", f"{WIKI}/ai-targets"],
    "mechanics": mech, "targeters": targ, "triggers": trig, "conditions": cond,
    "skillKeys": base["skillKeys"], "mobKeys": base["mobKeys"], "mobOptions": base["mobOptions"],
    "aiGoals": ai_goals, "aiTargets": ai_targets,
    "mechanicAttributes": attr["mechanicAttributes"], "targeterAttributes": attr["targeterAttributes"],
    "conditionActions": pairs("conditionActions"), "colorCodes": pairs("colorCodes"),
    "enums": enums, "placeholders": placeholders,
}
OUT.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(f"\nwrote {OUT}")
for k in ("mechanics", "targeters", "triggers", "conditions", "aiGoals", "aiTargets", "placeholders"):
    print(f"  {k}: {len(out[k])}")
print(f"  new documented mechanics={len(mech_new)} conditions={len(cond_new)} targeters={len(targ_new)} triggers={len(trig_new)}")
