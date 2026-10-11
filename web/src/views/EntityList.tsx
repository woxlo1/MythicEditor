import { h } from "preact";
import { useEffect, useState } from "preact/hooks";
import { Api } from "../api";
import type { EntrySummary, Kind } from "../types";
import { pushToast } from "../lib/toast";

export function EntityList(props: {
  kind: Kind;
  canEdit: boolean;
  onOpen: (id: string) => void;
  refreshToken: number;
}) {
  const [q, setQ] = useState("");
  const [items, setItems] = useState<EntrySummary[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [showCreate, setShowCreate] = useState(false);

  async function load() {
    setLoading(true);
    try {
      const res = await Api.list(props.kind, q, "", 300, 0);
      setItems(res.items);
      setTotal(res.total);
    } catch (e: any) {
      pushToast("error", e?.message ?? "Failed to load list");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    const t = setTimeout(load, 150);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, props.kind, props.refreshToken]);

  return (
    <div class="list-panel">
      <div class="list-toolbar">
        <input
          type="search"
          placeholder={`${props.kind === "skill" ? "Skill" : "Mob"}を検索…`}
          value={q}
          onInput={(e) => setQ((e.target as HTMLInputElement).value)}
        />
        {props.canEdit && (
          <button class="primary" onClick={() => setShowCreate(true)}>
            + New {props.kind === "skill" ? "Skill" : "Mob"}
          </button>
        )}
      </div>
      <div class="entry-table">
        <table class="entries">
          <thead>
            <tr>
              <th>Name</th>
              <th>File</th>
              {props.kind === "mob" && <th>Type</th>}
              <th>Refs</th>
            </tr>
          </thead>
          <tbody>
            {items.map((it) => (
              <tr key={it.id} onClick={() => props.onOpen(it.id)}>
                <td style={{ fontFamily: "var(--mono)" }}>{it.id}</td>
                <td style={{ color: "var(--text-2)" }}>{it.file}:{it.line}</td>
                {props.kind === "mob" && <td>{it.type}</td>}
                <td>
                  {it.skillRefs > 0 && <span class="tag">skills {it.skillRefs}</span>}{" "}
                  {it.mobRefs > 0 && <span class="tag">mobs {it.mobRefs}</span>}
                </td>
              </tr>
            ))}
            {!loading && items.length === 0 && (
              <tr><td colSpan={4} style={{ color: "var(--text-2)", padding: 20 }}>No entries found.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <div style={{ padding: "6px 10px", color: "var(--text-2)", fontSize: 12 }}>{total} total</div>
      {showCreate && (
        <CreateModal
          kind={props.kind}
          onClose={() => setShowCreate(false)}
          onCreated={(id) => {
            setShowCreate(false);
            load();
            props.onOpen(id);
          }}
        />
      )}
    </div>
  );
}

function CreateModal(props: { kind: Kind; onClose: () => void; onCreated: (id: string) => void }) {
  const [id, setId] = useState("");
  const [file, setFile] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: Event) {
    e.preventDefault();
    if (!/^[A-Za-z0-9_][A-Za-z0-9_.\-]{0,63}$/.test(id)) {
      setErr("Use letters, digits, '_', '-' or '.' (max 64 characters), no spaces.");
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const created = await Api.create(props.kind, id, file.trim() || null, null as any);
      props.onCreated(created.id);
    } catch (e: any) {
      setErr(e?.body?.message ?? e?.message ?? "Failed to create");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div class="modal-backdrop" onClick={(e) => e.target === e.currentTarget && props.onClose()}>
      <div class="modal">
        <h2>New {props.kind === "skill" ? "Skill" : "Mob"}</h2>
        <form onSubmit={submit}>
          <div class="row">
            <label>Name</label>
            <input type="text" autofocus value={id} onInput={(e) => setId((e.target as HTMLInputElement).value)} placeholder={props.kind === "skill" ? "FireBall" : "ExampleBoss"} />
          </div>
          <div class="row">
            <label>File (optional — defaults to {props.kind === "skill" ? "Skills/MythicEditor.yml" : "Mobs/MythicEditor.yml"})</label>
            <input type="text" value={file} onInput={(e) => setFile((e.target as HTMLInputElement).value)} placeholder={props.kind === "skill" ? "Skills/Fire.yml" : "Mobs/Bosses.yml"} />
          </div>
          {err && <div style={{ color: "var(--red)", fontSize: 12 }}>{err}</div>}
          <div class="actions">
            <button type="button" class="ghost" onClick={props.onClose}>Cancel</button>
            <button type="submit" class="primary" disabled={busy || !id}>{busy ? "Creating…" : "Create"}</button>
          </div>
        </form>
      </div>
    </div>
  );
}
