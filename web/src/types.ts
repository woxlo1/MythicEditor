// Shared API/catalog types mirrored from the Java backend (dev.mythiceditor.core.*).

export interface Permissions {
  use: boolean;
  edit: boolean;
  reload: boolean;
  admin: boolean;
}

export interface SessionInfo {
  playerId: string;
  playerName: string;
  csrf: string;
  permissions: Permissions;
  expiresInSeconds: number;
}

export interface StatusInfo {
  editorVersion: string;
  mythicPresent: boolean;
  mythicReady: boolean;
  mythicVersion: string;
  mythicFolder: string;
  editableKinds: string[];
  counts: Record<string, number>;
  indexVersion: number;
  lastScanMillis: number;
  lastScanDurationMs: number;
  activeSessions: number;
  autosaveDefault: boolean;
  autosaveDelayMs: number;
}

export type Kind = "skill" | "mob" | "item" | "droptable";

export interface EntrySummary {
  kind: Kind;
  id: string;
  file: string;
  line: number;
  type?: string;
  display?: string;
  skillRefs: number;
  mobRefs: number;
}

export interface EntryFull {
  kind: Kind;
  id: string;
  file: string;
  line: number;
  endLine: number;
  text: string;
  hash: string;
  fileHash: string;
  mtime: number;
  warnings: string[];
}

export interface ConflictInfo {
  code: "conflict";
  message: string;
  server: { text: string; hash: string; file: string };
}

export interface ApiErrorBody {
  code: string;
  message: string;
  [key: string]: unknown;
}

export class ApiError extends Error {
  status: number;
  body: ApiErrorBody;
  constructor(status: number, body: ApiErrorBody) {
    super(body.message ?? `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

// ---- catalog (mirrors CatalogService's normalized JSON) --------------------------------------------

export interface CatalogParam {
  names: string[];
  type: string; // number | integer | boolean | string | time | skill | skilllist | mob | item | color | material | particle | sound | entitytype | potion | enum | ...
  values?: string[];
  enumRef?: string;
  soft?: boolean;
  required: boolean;
  default: string;
  desc: string;
}

export interface CatalogEntry {
  name: string;
  aliases: string[];
  desc: string;
  cat?: string;
  arg?: string;
  source: "bundled" | "runtime";
  params?: CatalogParam[];
  main?: string[];
}

export interface CatalogNamed {
  name: string;
  type?: string;
  desc: string;
}

export interface Catalog {
  mythicmobsVersion: string;
  runtimeDetected: boolean;
  mechanics: CatalogEntry[];
  targeters: CatalogEntry[];
  conditions: CatalogEntry[];
  triggers: CatalogEntry[];
  skillKeys: CatalogNamed[];
  mobKeys: CatalogNamed[];
  mobOptions: CatalogNamed[];
  aiGoals: CatalogNamed[];
  aiTargets: CatalogNamed[];
  placeholders: CatalogNamed[];
  /** attributes accepted by every mechanic (cooldown, delay, repeat, chance, ...) */
  mechanicAttributes?: CatalogParam[];
  /** attributes accepted by every targeter (targetconditions, fallback, ...) */
  targeterAttributes?: CatalogParam[];
  conditionActions?: CatalogNamed[];
  colorCodes?: CatalogNamed[];
  enums: Record<string, string[]>;
}

export interface GraphNode {
  id: string;
  kind: string;
  file: string;
  unresolved: boolean;
}
export interface GraphEdge {
  from: string;
  to: string;
  kind: string;
}
export interface GraphData {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface ChangeItem {
  seq: number;
  time: number;
  actor: string;
  action: string;
  kind: string;
  id: string;
  file: string;
}
