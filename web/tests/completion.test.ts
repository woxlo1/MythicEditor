import test from "node:test";
import assert from "node:assert/strict";
import { analyzeSkillLine, findCallSite, isCommentPosition, stripInlineComment, parseContext } from "../src/monaco/context";
import { CatalogIndex } from "../src/monaco/catalogIndex";
import type { Catalog, CatalogEntry, CatalogParam } from "../src/types";

const P = (names: string[], type = "string", def = ""): CatalogParam => ({ names, type, required: false, default: def, desc: "" });

test("typing the start of a mechanic name offers name completion with the typed text as replace range", () => {
  assert.deepEqual(analyzeSkillLine("  - dama"), { area: "mechanicName", partial: "dama" });
  assert.deepEqual(analyzeSkillLine("  - "), { area: "mechanicName", partial: "" });
  // prefixed names contain ':' — the whole token must be replaced, not just the last word
  assert.deepEqual(analyzeSkillLine("  - effect:part"), { area: "mechanicName", partial: "effect:part" });
});

test("parameter names: already used keys are reported, partial is the key being typed", () => {
  const c = analyzeSkillLine("  - damage{amount=10;ia");
  assert.equal(c.area, "mechanicParamName");
  if (c.area !== "mechanicParamName") return;
  assert.equal(c.mechanic, "damage");
  assert.deepEqual(c.used, ["amount"]);
  assert.equal(c.partial, "ia");
  assert.equal(c.owner, "mechanic");
});

test("parameter values", () => {
  const c = analyzeSkillLine("  - damage{amount=10;ignoreArmor=t");
  assert.equal(c.area, "mechanicParamValue");
  if (c.area !== "mechanicParamValue") return;
  assert.equal(c.param, "ignorearmor");
  assert.equal(c.partial, "t");
  assert.deepEqual(c.used, ["amount"]);
});

test("prefixed mechanic (effect:particles) resolves to the part after the colon; skill:X resolves to skill", () => {
  assert.equal(findCallSite("effect:particles{p=", "mechanic")?.name, "particles");
  assert.equal(findCallSite("skill:MyMeta{", "mechanic")?.name, "skill");
});

test("targeter and inline-condition argument blocks get their own owner", () => {
  const t = findCallSite("damage{a=1} @PIR{r=5;", "mechanic");
  assert.equal(t?.owner, "targeter");
  assert.equal(t?.name, "PIR");
  assert.deepEqual(t?.used, ["r"]);
  const c = findCallSite("damage{a=1} @Self ?health{a=", "mechanic");
  assert.equal(c?.owner, "condition");
  assert.equal(c?.key, "a");
});

test("a closed block is not a call site; ';' inside quotes does not split arguments", () => {
  assert.equal(findCallSite("damage{a=1} @Self", "mechanic"), null);
  const s = findCallSite('message{m="a;b=c";', "mechanic");
  assert.deepEqual(s?.used, ["m"]);
  assert.equal(s?.key, null);
  const open = findCallSite('message{m="hello wor', "mechanic");
  assert.equal(open?.inString, true);
});

test("an apostrophe in an unquoted value does not swallow the rest of the line", () => {
  const s = findCallSite("message{m=Don't;", "mechanic");
  assert.equal(s?.key, null);
  assert.deepEqual(s?.used, ["m"]);
});

test("targeters, triggers, inline conditions and placeholders", () => {
  assert.deepEqual(analyzeSkillLine("  - damage{a=1} @PI"), { area: "targeter", partial: "PI" });
  assert.deepEqual(analyzeSkillLine("  - damage{a=1} @Self ~onTi"), { area: "trigger", hasColon: false, partial: "onTi" });
  assert.deepEqual(analyzeSkillLine("  - damage{a=1} @Self ~onTimer:2"), { area: "trigger", hasColon: true, partial: "onTimer" });
  assert.deepEqual(analyzeSkillLine("  - damage{a=1} @Self ?hea"), { area: "inlineCondition", partial: "hea" });
  assert.deepEqual(analyzeSkillLine("  - message{m=<caster.h"), { area: "placeholder", partial: "caster.h" });
});

test("a comparison '=<' in a condition is not mistaken for a placeholder", () => {
  const c = analyzeSkillLine("  - damage{a=1} ?health{a=<");
  assert.equal(c.area, "mechanicParamValue");
});

test("conditions lists use the condition owner", () => {
  const text = "Skill:\n  Conditions:\n  - health{a=";
  const c = parseContext(text, text.length, "skill");
  assert.equal(c.area, "mechanicParamValue");
  if (c.area === "mechanicParamValue") assert.equal(c.owner, "condition");
  const t2 = "Skill:\n  Conditions:\n  - hea";
  assert.deepEqual(parseContext(t2, t2.length, "skill"), { area: "conditionsListMechanic", partial: "hea" });
});

function catalog(mech: CatalogEntry[], shared: CatalogParam[]): Catalog {
  return {
    mythicmobsVersion: "x", runtimeDetected: false, mechanics: mech, targeters: [], conditions: [], triggers: [],
    skillKeys: [], mobKeys: [], mobOptions: [], aiGoals: [], aiTargets: [], placeholders: [], enums: {},
    mechanicAttributes: shared, targeterAttributes: []
  };
}

test("paramsFor: own parameters first, shared attributes appended, name collisions resolved in favour of the entry", () => {
  const damage: CatalogEntry = { name: "damage", aliases: ["d"], desc: "", source: "bundled", params: [P(["amount", "a"]), P(["power"], "number", "2")], main: ["amount"] };
  const ci = new CatalogIndex(catalog([damage], [P(["cooldown", "cd"]), P(["power"], "number", "1"), P(["chance"])]));
  const names = ci.paramsFor("mechanic", ci.mechanic("d")).map((p) => p.names[0]);
  assert.deepEqual(names, ["amount", "power", "cooldown", "chance"]);
  assert.equal(ci.paramsFor("mechanic", ci.mechanic("damage")).find((p) => p.names[0] === "power")?.default, "2");
});

test("paramsFor: an entry without documented parameters still gets the shared attributes", () => {
  const ci = new CatalogIndex(catalog([], [P(["cooldown"]), P(["delay"])]));
  assert.deepEqual(ci.paramsFor("mechanic", undefined).map((p) => p.names[0]), ["cooldown", "delay"]);
  assert.deepEqual(ci.paramsFor("condition", undefined), []);
});

test("condition actions: the word after a complete condition, and a skill name after cast-style actions", () => {
  const head = "Skill:\n  Conditions:\n";
  const at = (line: string) => parseContext(head + line, (head + line).length, "skill");
  assert.deepEqual(at("  - health{h=>10} po"), { area: "conditionAction", partial: "po" });
  assert.deepEqual(at("  - day "), { area: "conditionAction", partial: "" });
  assert.deepEqual(at("  - sunny orElseCast Oth"), { area: "conditionActionSkill", partial: "Oth" });
  assert.deepEqual(at("  - sunny cast "), { area: "conditionActionSkill", partial: "" });
});


test("comment detection suppresses suggestions only outside quoted values", () => {
  assert.equal(isCommentPosition("  - damage # explain damage", 18), true);
  assert.equal(isCommentPosition('  - message{m="text # not comment"}', 25), false);
  assert.equal(isCommentPosition("  - damage{color=#AABBCC}", 23), false);
  assert.equal(stripInlineComment('  - message{m="text # kept"} # removed'), '  - message{m="text # kept"}');
  assert.equal(stripInlineComment("  - damage{amount=5} # damage"), "  - damage{amount=5}");
});

test("whole-file editing: completion context is found inside the 2nd and 3rd entry of a multi-entry file", () => {
  const file = [
    "# my skills",
    "First:",
    "  Skills:",
    "  - damage{amount=1} @Target",
    "",
    "# second one",
    "Second:",
    "  Conditions:",
    "  - hea",
    "",
    "Third:",
    "  Skills:",
    "  - damage{amount=1;ia"
  ].join("\n");
  // inside Second -> Conditions (the nearest indent-2 key belongs to the entry above the cursor, not to First)
  const secondEnd = file.indexOf("  - hea") + "  - hea".length;
  assert.deepEqual(parseContext(file, secondEnd, "skill"), { area: "conditionsListMechanic", partial: "hea" });
  // inside Third -> Skills, parameter name being typed
  const c = parseContext(file, file.length, "skill");
  assert.equal(c.area, "mechanicParamName");
  // an entry-name line and a comment line at indent 0 offer nothing
  assert.deepEqual(parseContext(file, file.indexOf("Second:") + 3, "skill"), { area: "none" });
  assert.deepEqual(parseContext(file, "# my sk".length, "skill"), { area: "none" });
});
