import type {
  Catalog, ChangeItem, EntryFull, EntrySummary, FileFull, FileSummary, GraphData, Kind, SessionInfo, StatusInfo
} from "./types";
import { ApiError } from "./types";

let csrfToken = "";
let onUnauthorized: (() => void) | null = null;

export function setCsrfToken(token: string) {
  csrfToken = token;
}

export function onUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (method !== "GET") headers["X-CSRF-Token"] = csrfToken;

  const res = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body: body === undefined ? undefined : JSON.stringify(body)
  });

  if (res.status === 401) {
    onUnauthorized?.();
    const errBody = await safeJson(res);
    throw new ApiError(401, errBody?.error ?? { code: "unauthenticated", message: "Session expired" });
  }
  if (res.status === 304) return undefined as unknown as T;
  if (!res.ok) {
    const errBody = await safeJson(res);
    throw new ApiError(res.status, errBody?.error ?? { code: "error", message: res.statusText });
  }
  if (res.status === 204 || res.headers.get("Content-Length") === "0") return undefined as unknown as T;
  return (await res.json()) as T;
}

async function safeJson(res: Response): Promise<any> {
  try {
    return await res.json();
  } catch {
    return null;
  }
}

export const Api = {
  session: () => request<SessionInfo>("GET", "/api/session"),
  logout: () => request<{ ok: boolean }>("POST", "/api/session/logout"),
  status: () => request<StatusInfo>("GET", "/api/status"),

  catalog: () => request<Catalog>("GET", "/api/catalog"),

  list: (kind: Kind, q: string, file = "", limit = 200, offset = 0) =>
    request<{ total: number; items: EntrySummary[] }>(
      "GET",
      `/api/${kind}s?q=${encodeURIComponent(q)}&file=${encodeURIComponent(file)}&limit=${limit}&offset=${offset}`
    ),
  get: (kind: Kind, id: string) => request<EntryFull>("GET", `/api/${kind}s/${encodeURIComponent(id)}`),
  create: (kind: Kind, id: string, file: string | null, text: string) =>
    request<EntryFull>("POST", `/api/${kind}s`, { id, file, text }),
  update: (kind: Kind, id: string, text: string, baseHash: string, force = false) =>
    request<EntryFull>("PUT", `/api/${kind}s/${encodeURIComponent(id)}`, { text, baseHash, force }),
  remove: (kind: Kind, id: string, baseHash: string, force = false) =>
    request<{ ok: boolean }>(
      "DELETE",
      `/api/${kind}s/${encodeURIComponent(id)}?baseHash=${encodeURIComponent(baseHash)}&force=${force}`
    ),
  duplicate: (kind: Kind, id: string, newId: string, file: string | null) =>
    request<EntryFull>("POST", `/api/${kind}s/${encodeURIComponent(id)}/duplicate`, { newId, file }),

  files: (kind?: Kind) => request<{ items: FileSummary[] }>("GET", `/api/files${kind ? `?kind=${kind}` : ""}`),
  /** The complete text of one file (every entry, comment and blank line in it). */
  file: (path: string) => request<FileFull>("GET", `/api/file?path=${encodeURIComponent(path)}`),
  saveFile: (path: string, text: string, baseHash: string, force = false) =>
    request<FileFull>("PUT", "/api/file", { path, text, baseHash, force }),
  definition: (kind: string, id: string) =>
    request<{ found: boolean; file?: string; line?: number; endLine?: number; ambiguous?: boolean }>(
      "GET", `/api/definition?kind=${encodeURIComponent(kind)}&id=${encodeURIComponent(id)}`
    ),
  graph: (kind?: string, id?: string, depth = 2) =>
    request<GraphData>("GET", `/api/graph${kind && id ? `?kind=${kind}&id=${encodeURIComponent(id)}&depth=${depth}` : ""}`),
  changes: (since: number, limit = 100) => request<{ lastSeq: number; indexVersion: number; items: ChangeItem[] }>(
    "GET", `/api/changes?since=${since}&limit=${limit}`
  ),
  backups: () => request<{ items: string[] }>("GET", "/api/backups"),
  rescan: () => request<{ changed: number; indexVersion: number }>("POST", "/api/rescan"),
  reload: () => request<{ ok: boolean }>("POST", "/api/reload"),

  adminSessions: () => request<{ items: { playerId: string; playerName: string; createdAt: number; lastSeen: number; token: string }[] }>(
    "GET", "/api/admin/sessions"
  ),
  adminRevoke: (maskedToken: string) => request<{ revoked: number }>("POST", `/api/admin/sessions/${maskedToken}/revoke`)
};
