# MythicEditor

A self-hosted web IDE for [MythicMobs](https://www.mythicmobs.net/) Skills and Mobs, for Paper 1.21.8.
Run `/mythiceditor` in-game, click the link, and edit your Skills and Mobs in a full browser-based
editor with MythicMobs-aware autocomplete, hover docs, go-to-definition, diagnostics, and a visual
structured editor — backed by a plain-text YAML store that never touches anything outside your
MythicMobs folder and never destroys data it doesn't understand.

This is an original implementation. It does not contain or derive from Magic Plugin's or MythicScribe's
source code; both were used only as references for *what a good MythicMobs editing experience should
offer* (see "Design references" below).

## Architecture

```
MythicEditor/
├── core/                     Plain-Java module, zero Bukkit dependency, unit tested standalone
│   └── src/main/java/dev/mythiceditor/core/
│       ├── config/           EditorSettings (config.yml, as plain Java — testable without Bukkit)
│       ├── security/         PathGuard — the single choke point turning strings into file paths
│       ├── yaml/             YamlBlockParser (offset-preserving) + YamlValidator (SnakeYAML SafeConstructor)
│       ├── index/            ProjectIndex (incremental scan+cache), FileWatcher, RefScanner, ProjectLayout
│       ├── storage/          FileStore (splice/validate/backup/atomic-write), BackupManager
│       ├── catalog/          CatalogService — bundled MythicMobs reference + runtime-detected additions
│       ├── graph/            RelationGraph (Skill→Skill, Mob→Skill, Skill→Mechanic)
│       ├── mythic/           MythicAdapter interface (+ no-op impl for tests/tools)
│       ├── web/              Embedded HTTP server, sessions, CSRF/rate-limit middleware, REST API
│       ├── audit/            ChangeLog (recent-changes feed)
│       └── service/          EditorCore — wires every component together
│
├── src/main/java/dev/mythiceditor/   The buildable plugin module (depends on :core)
│   ├── MythicEditorPlugin.java        JavaPlugin entry point
│   ├── command/                       /mythiceditor command + tab completion
│   └── platform/                      Bukkit/Paper glue: permissions, main-thread scheduling,
│                                       reflection-based MythicMobs adapter, quit listener
├── src/main/resources/
│   ├── plugin.yml / config.yml
│
└── web/                       Vite + TypeScript + Preact + Monaco Editor SPA
    └── src/
        ├── monaco/            Language registration, context detection, completion/hover/color/
        │                      formatter/diagnostics providers — the "MythicScribe-class IDE" layer
        ├── lib/entryDoc.ts    Line-splice document model shared by the Visual and Code editors
        ├── views/             Dashboard, Skill/Mob list, Editor (Visual/Code/Preview), relation Graph
        └── api.ts             Typed REST client (CSRF-aware)
```

**Why two Gradle modules?** `core` has no Bukkit dependency, so `./gradlew :core:test` runs anywhere —
including CI without a Paper/MythicMobs jar available. The root project is the actual plugin: it depends
on `:core`, adds the Bukkit/Paper/Adventure-specific glue, bundles the built web app as jar resources, and
produces `build/libs/MythicEditor-1.0.0.jar`.

**Why reflection for MythicMobs?** `ReflectiveMythicAdapter` only uses reflection for the *optional*
runtime-catalog enrichment (best-effort, degrades to nothing on any failure). Everything load-bearing
(name, version, data folder, reload) uses the stable, documented `org.bukkit.plugin.Plugin` API or the
public `/mythicmobs reload` console command — not MythicMobs internals — so the plugin keeps working even
if MythicMobs' internal package layout changes between 5.x releases.

## Building

```bash
./gradlew build
```

Requires network access to Maven Central and `repo.papermc.io` (to resolve `paper-api`) and to npm's
registry (for the web front-end's dependencies) — normal internet access is enough, nothing exotic.
Produces `build/libs/MythicEditor-1.0.0.jar`.

Useful flags:
- `-PskipWeb` — skip the `npm install`/`npm run build` steps for fast Java-only iteration (the jar will
  serve a "web assets are missing" page instead of the SPA).
- `./gradlew :core:test` — run just the pure-Java unit test suite (45 tests: YAML block parsing/
  validation, path-traversal defenses, the file store's splice/conflict/backup/atomic-write behavior,
  incremental indexing, the catalog).

Install `build/libs/MythicEditor-1.0.0.jar` alongside `MythicMobs.jar` in `plugins/`.

### A note on this delivery's build verification

This project was written and verified inside a sandboxed environment whose outbound network access is
restricted to a small allow-list (npm, PyPI, crates.io, GitHub) — it cannot reach Maven Central or
`repo.papermc.io`. As a result:

- `core` was built, tested, and packaged **for real, through Gradle** (`./gradlew :core:test` → 45/45
  passing) using locally-available Gson/SnakeYAML/JUnit jars.
- The web front-end was built **for real, through Gradle** (`./gradlew webBuild`, which runs `npm install`
  && `tsc --noEmit` && `vite build`) and produces a working `web/dist/`.
- The root module (Bukkit/Paper/Adventure-dependent code: `MythicEditorPlugin`, the command, the platform
  glue) was verified by compiling its full source tree with zero errors and zero warnings against a
  hand-written stub reproducing the exact subset of the `org.bukkit`, `org.bukkit.plugin.java`,
  `org.bukkit.event.*`, and `net.kyori.adventure.text.*` API surface it calls — but it could not be
  compiled against the *real* `paper-api` jar in this sandbox, because that requires network access this
  environment doesn't have. `./gradlew build` will resolve and compile normally on any machine with
  ordinary internet access.

If you hit anything unexpected when building for real, it's most likely in that last, unverified slice —
please open an issue with the compiler output.

## Relay mode (no open port) - Cloudflare Worker

Easiest: set `relay.enabled: true` and `relay.api-token` (a Cloudflare "Edit Cloudflare Workers" token) - the plugin carries the Worker (`relay/worker.js` inside the jar), deploys it into your account on startup, generates the secret and keeps url/secret in `relay.json`; remove the token afterwards. Manual alternative: set `relay.enabled: true`, `relay.url` and `relay.secret` in `config.yml` and the plugin connects OUT to a Cloudflare
Worker (source and step-by-step setup in `relay-worker/`). Browsers talk to the Worker, the Worker forwards each request
through the WebSocket, and the plugin answers it using the normal embedded server, so every session / permission / CSRF /
Origin / rate-limit check still applies. `/mythiceditor` then prints links on the Worker's address. Turning relay on also
turns on `security.trust-proxy-headers` automatically, and `web.public-url` defaults to `relay.url`.
Several servers can share one Worker via `relay.server-id` (see `relay-worker/README.md`). Implementation: `core/.../web/RelayClient.java`.

## Configuration (`plugins/MythicEditor/config.yml`)

Every path the editor will ever read or write is declared here — nothing is hard-coded in Java. Key
settings: `web.port` / `web.public-url` (what `/mythiceditor` prints), `mythicmobs.folder` (auto-detected
from the installed MythicMobs plugin if left blank), `mythicmobs.paths` (glob-lite patterns like
`Packs/*/Skills` — this list *is* the whitelist `PathGuard` enforces), `storage.backup-dir` /
`backup-retention`, `session.*` (ticket/session lifetimes), `security.rate-limit.*`, `editor.autosave`
(off by default).

## Commands & permissions

| Command | Permission | Effect |
|---|---|---|
| `/mythiceditor` or `/mythiceditor open` | `mythiceditor.use` | Prints a one-time, expiring, clickable editor link |
| `/mythiceditor reload` | `mythiceditor.admin` | Re-reads `config.yml` and restarts the editor if it changed (active web sessions end); otherwise only re-scans the MythicMobs folders |
| `/mythiceditor sessions` | `mythiceditor.admin` | Lists active web sessions |
| `/mythiceditor revoke <player>` | `mythiceditor.admin` | Ends a player's web sessions |
| *(creating/editing/deleting entries)* | `mythiceditor.edit` | Enforced both client-side (UI hides controls) and server-side (every write request) |
| *(triggering a MythicMobs reload from the web UI)* | `mythiceditor.reload` | |
| *(adding `command`-style mechanics to a Skill/Mob)* | `mythiceditor.unrestricted` | Equivalent to console access, see "Security notes" |

All of them default to `op`.

## Performance over slow links (tunnels)

- Static files and API answers larger than 1 KB are gzip-compressed; the bundled web files are kept in memory.
- Files under `/assets/` are cached by the browser for a year (their names contain a content hash); `index.html`,
  the catalog, the Skill/Mob lists and the graph are revalidated with an ETag, so an unchanged answer costs a
  few hundred bytes (HTTP 304).
- The code editor (Monaco, ~2.5 MB) is a separate download that starts in the background after the page has
  loaded; the dashboard, lists and graph do not wait for it. Background tabs stop polling.

## Security notes

- **Who can do what.** The web page itself is public, but every API call needs a login: a one-time link from
  `/mythiceditor` (256-bit random, valid 5 minutes, opens once) becomes a session cookie. Permissions are checked
  on the server for every request, on the server's main thread, and a player who is offline has none.
  **Do not forward the `/mythiceditor` link** - whoever opens it first gets that player's access.
- **`mythiceditor.edit` is a powerful permission.** Skills can run server commands (the `command` mechanic). Without
  `mythiceditor.unrestricted` a player cannot ADD lines that use the mechanics listed in
  `security.restricted-mechanics`. This is a deny-list for common cases, **not a sandbox** (plugins can add other
  mechanics, which you can add to the list). Only give `edit` to people you would trust with console access.
- **Use HTTPS when others connect over the internet.** Plain HTTP exposes the link and the session cookie. Put the
  editor behind nginx / Caddy / a tunnel that terminates TLS, bind to `127.0.0.1`, set `web.public-url` and turn on
  `security.trust-proxy-headers` (honoured only for connections coming from the same machine).
- Writes require a matching `Origin`, a CSRF token and are rate limited; opening links and unauthenticated requests
  are rate limited per client address. Responses carry a Content-Security-Policy (`security.csp`).
- Only `.yml` files inside the configured MythicMobs content folders can be read or written (symlinks included),
  and every change is backed up first.
- `mythicFolder` (the server's absolute path) is only reported to `mythiceditor.admin`.

## What's implemented

**Storage & safety** — path-traversal-proof `PathGuard` (rejects `..`, absolute paths, symlink escapes,
non-YAML extensions); splice-based edits so untouched comments/keys/entries are byte-identical after a
save; SnakeYAML `SafeConstructor`-based validation before anything is written; a timestamped backup before
every change; atomic temp-file→fsync→rename→read-back-verify writes; external-change detection with a
"File changed externally" dialog (Reload / Compare / Overwrite) rather than silent overwrites; unknown
YAML content (comments, keys MythicEditor doesn't model) is preserved on every save.

**IDE features (web, Monaco-based)** — Monarch syntax highlighting for the Skill DSL; context-aware
completion (different suggestions inside `Skills:` vs `Options:` vs a mechanic's `{...}` vs after `@`/`~`/
`<`) sourced from a bundled MythicMobs 5.x reference catalog *merged* with names reflectively detected from
the running MythicMobs; snippet insertion with native Monaco Tab-stop navigation; live suggestions for
already-created Skill/Mob names; hover documentation; a documentation side panel; F12/Ctrl+Click go-to-
definition (jumps to the entry, which is how cross-file navigation is modeled here); client-side
diagnostics (unknown mechanic/targeter/trigger/condition, unresolved Skill/Mob references, type-mismatched
parameter values) plus server-side structural validation on save; a conservative reindent-only formatter
that never reorders keys or drops comments; a `#RRGGBB` color picker; Ctrl+S / Ctrl+Space / Ctrl+Z /
Ctrl+Shift+Z / Ctrl+F all work through Monaco's standard keybindings.

**Visual Editor** — structured forms for a Mob's `Type`/`Display`/`Health`/`Damage`/`Armor`/`Faction`/
`Template`, `Options` toggles, `Equipment`/`Drops`/`AIGoalSelectors`/`AITargetSelectors` list rows, and a
card-based editor for `Skills:` lines (mechanic + parameters + targeter + trigger, add/remove/reorder).
The Visual Editor and Code Editor are two views over the *same* raw-text model (`EntryDoc`, a client-side
line-splice engine mirroring the server's own approach) — there is no separate parsed copy to fall out of
sync.

**Everything else from the spec** — Dashboard (counts, MythicMobs status, recent changes), fast fuzzy
search, Skill/Mob create/edit/duplicate/delete, a force-directed relationship graph (Mob→Skill, Skill→
Skill, Skill→Mechanic; click a node to open it), dark IDE theme, save-state indicator with an unload guard,
session tickets bound to the issuing player's UUID with sliding idle timeout + hard lifetime + per-player
session cap, CSRF (double-submit header checked against the session) + Origin checks on every write, JSON
size limits, and separate per-minute rate limits for reads vs. writes.

## Known scope cuts

- The Visual Editor's list sections (`Equipment`, `Drops`, AI selectors, `Conditions`) edit each row as one
  raw text field rather than fully decomposing item/condition syntax into sub-fields — the Code tab is one
  click away for anything more elaborate, and nothing is ever hidden or silently dropped.
- Go-to-definition navigates *to the entry* (selecting it in the app) rather than to a line inside a giant
  shared buffer, since storage is modeled per-entry; the file:line is shown in the header once you're there.
- `Items`/`Droptables` are indexed (and count toward search/completion/the graph) but are read-only in this
  version, per `mythicmobs.editable` in `config.yml` — flip it once you're happy with Skills/Mobs.

## Design references

Magic Plugin's source (provided as reference material) was read only to understand *what a Web Editor for
a Bukkit content plugin typically needs to solve* — ticket/session issuance from a chat command, a
Backend/Frontend split, conflict handling. No Magic code, classes, UI, CSS, or assets were copied; every
class here is a new implementation with its own design (e.g. `FileStore`'s splice-based diffing and
`ProjectIndex`'s incremental rescan have no equivalent in Magic). MythicScribe was used only as a
description of *which IDE features matter* for MythicMobs specifically (completion, hover, go-to-
definition, a formatter, created-content completion) — this project's Monaco-based implementation
(`core/` catalog format, `context.ts`'s scanner, the completion/diagnostics providers) is independent code
written against MythicMobs' public skill-line syntax, not against MythicScribe's source.
