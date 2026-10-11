import { h } from "preact";
import { useEffect, useState } from "preact/hooks";
import { Api } from "../api";
import type { ChangeItem, StatusInfo } from "../types";

export function Dashboard(props: { onOpenEntry: (kind: "skill" | "mob", id: string) => void; onNavigate: (view: string) => void }) {
  const [status, setStatus] = useState<StatusInfo | null>(null);
  const [changes, setChanges] = useState<ChangeItem[]>([]);
  const [err, setErr] = useState<string | null>(null);

  async function refresh() {
    try {
      const [s, c] = await Promise.all([Api.status(), Api.changes(0, 20)]);
      setStatus(s);
      setChanges(c.items.slice().reverse());
    } catch (e: any) {
      setErr(e?.message ?? "Failed to load status");
    }
  }

  useEffect(() => {
    refresh();
    const t = setInterval(() => {
      if (!document.hidden) refresh(); // skip while the tab is in the background
    }, 15000);
    return () => clearInterval(t);
  }, []);

  if (err) return <div class="center-screen">{err}</div>;
  if (!status) return <div class="center-screen">Loading…</div>;

  return (
    <div class="dashboard">
      <h1 style={{ marginTop: 0 }}>Dashboard</h1>
      <div class="cards">
        <div class="card"><div class="n">{status.counts.skill ?? 0}</div><div class="l">Skills</div></div>
        <div class="card"><div class="n">{status.counts.mob ?? 0}</div><div class="l">Mobs</div></div>
        <div class="card"><div class="n">{status.counts.item ?? 0}</div><div class="l">Items (read-only)</div></div>
        <div class="card"><div class="n">{status.activeSessions}</div><div class="l">Active web sessions</div></div>
      </div>

      <h2>Server Status</h2>
      <div class="cards">
        <div class="card">
          <div style={{ display: "flex", alignItems: "center" }}>
            <span class={"status-dot " + (status.mythicPresent && status.mythicReady ? "ok" : status.mythicPresent ? "warn" : "bad")} />
            MythicMobs {status.mythicPresent ? (status.mythicReady ? "ready" : "loaded, not ready") : "not found"}
          </div>
          <div class="l">Version {status.mythicVersion || "unknown"}</div>
        </div>
        <div class="card">
          <div>Editor v{status.editorVersion}</div>
          <div class="l">Index v{status.indexVersion} · last scan {status.lastScanDurationMs}ms</div>
        </div>
        <div class="card">
          <div>Autosave</div>
          <div class="l">{status.autosaveDefault ? `On (every ${status.autosaveDelayMs}ms of inactivity)` : "Off by default"}</div>
        </div>
      </div>

      <h2>Recent Changes</h2>
      <ul class="changes-list">
        {changes.length === 0 && <li>No changes yet.</li>}
        {changes.map((c) => (
          <li key={c.seq}>
            <span class="act">{c.action}</span> {c.kind ? `${c.kind} ` : ""}
            {c.id ? (
              <a href="#" onClick={(e) => { e.preventDefault(); if (c.kind === "skill" || c.kind === "mob") props.onOpenEntry(c.kind as any, c.id); }} style={{ color: "inherit" }}>
                {c.id}
              </a>
            ) : c.file}
            {" "}by {c.actor} · {new Date(c.time).toLocaleTimeString()}
          </li>
        ))}
      </ul>
    </div>
  );
}
