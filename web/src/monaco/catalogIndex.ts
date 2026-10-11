import type { Catalog, CatalogEntry, CatalogParam } from "../types";

/** Fast case-insensitive lookup over the catalog (mechanic/targeter/condition name -> aliases -> entry). */
export class CatalogIndex {
  catalog: Catalog;
  private mechByName = new Map<string, CatalogEntry>();
  private targByName = new Map<string, CatalogEntry>();
  private condByName = new Map<string, CatalogEntry>();
  private triggerNames: string[] = [];
  private skillKeyNames: string[] = [];
  private mobKeyNames: string[] = [];
  private mobOptionNames: string[] = [];
  private aiGoalNames: string[] = [];
  private aiTargetNames: string[] = [];

  constructor(catalog: Catalog) {
    this.catalog = catalog;
    for (const m of catalog.mechanics) this.index(this.mechByName, m);
    for (const t of catalog.targeters) this.index(this.targByName, t);
    for (const c of catalog.conditions) this.index(this.condByName, c);
    this.triggerNames = catalog.triggers.map((t) => t.name);
    this.skillKeyNames = catalog.skillKeys.map((k) => k.name);
    this.mobKeyNames = catalog.mobKeys.map((k) => k.name);
    this.mobOptionNames = catalog.mobOptions.map((k) => k.name);
    this.aiGoalNames = catalog.aiGoals.map((k) => k.name);
    this.aiTargetNames = catalog.aiTargets.map((k) => k.name);
  }

  private index(map: Map<string, CatalogEntry>, e: CatalogEntry) {
    map.set(e.name.toLowerCase(), e);
    for (const a of e.aliases) map.set(a.toLowerCase(), e);
  }

  mechanic(name: string) { return this.mechByName.get(name.toLowerCase()); }
  targeter(name: string) { return this.targByName.get(name.toLowerCase()); }
  condition(name: string) { return this.condByName.get(name.toLowerCase()); }

  get mechanics() { return this.catalog.mechanics; }
  get targeters() { return this.catalog.targeters; }
  get conditions() { return this.catalog.conditions; }
  get triggers() { return this.catalog.triggers; }
  get skillKeys() { return this.skillKeyNames; }
  get mobKeys() { return this.mobKeyNames; }
  get mobOptions() { return this.mobOptionNames; }
  get aiGoals() { return this.aiGoalNames; }
  get aiTargets() { return this.aiTargetNames; }

  enumValues(ref: string): string[] {
    return this.catalog.enums[ref.toLowerCase()] ?? this.catalog.enums[ref] ?? [];
  }

  paramValues(p: CatalogParam): string[] {
    if (p.type !== "enum") return [];
    if (p.values && p.values.length) return p.values;
    if (p.enumRef) return this.enumValues(p.enumRef);
    return [];
  }

  isKnownMechanic(name: string) { return this.mechByName.has(name.toLowerCase()); }
  isKnownTargeter(name: string) { return this.targByName.has(name.toLowerCase()); }
  isKnownCondition(name: string) { return this.condByName.has(name.toLowerCase()); }
  isKnownTrigger(name: string) { return this.triggerNames.some((t) => t.toLowerCase() === name.toLowerCase()); }
}

/** Live index of every Skill/Mob name that exists on the server, kept fresh by App via /api/changes polling. */
export class NameIndex {
  skills: string[] = [];
  mobs: string[] = [];
  items: string[] = [];
  setSkills(names: string[]) { this.skills = names; }
  setMobs(names: string[]) { this.mobs = names; }
  setItems(names: string[]) { this.items = names; }
}
