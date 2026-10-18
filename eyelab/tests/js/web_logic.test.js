// Unit tests for the page's pure logic: the app's Type keyboard, Quick chat, My phrases and the Spell scanner.
// Run directly with `node tests/js/web_logic.test.js`, or through pytest (tests/test_web_logic.py).
"use strict";

const assert = require("assert");
const fs = require("fs");
const path = require("path");

const STATIC = path.join(__dirname, "..", "..", "server", "static");
const ASSETS = path.join(__dirname, "..", "..", "..", "app", "src", "main", "assets");
const { Dictionary, TypeSession, groupCode, contextWords } = require(path.join(STATIC, "keyboard.js"));
const { QuickChat, PhraseBook, Speller, MY_PHRASES_EMPTY } = require(path.join(STATIC, "features.js"));

const dict = new Dictionary(
  fs.readFileSync(path.join(ASSETS, "EnglishWords.txt"), "utf8").split(/\r?\n/).filter(Boolean),
  fs.readFileSync(path.join(ASSETS, "EnglishBigrams.txt"), "utf8").split(/\r?\n/));

const tests = [];
const test = (name, fn) => tests.push([name, fn]);

// ---------- Type (the app's ambiguous keyboard) ----------
test("letters map to the app's four groups", () => {
  assert.strictEqual(groupCode("water"), "30202");
  assert.strictEqual(groupCode("Hello!"), "10112");
});

test("typing water offers what the app's screenshot shows", () => {
  const t = new TypeSession(dict);
  ["right", "left-up", "left", "left-up", "left"].forEach((g) => t.handle(g));
  assert.strictEqual(t.text(), "U-Z A-F N-T A-F N-T");
  const ui = t.ui();
  assert.deepStrictEqual([ui["left-up"][1], ui["right-up"][1], ui.left[1]], ["water", "veteran", "vendor"]);
});

test("blink switches layers and a corner picks the word", () => {
  const t = new TypeSession(dict);
  ["right", "left-up"].forEach((g) => t.handle(g));
  assert.strictEqual(t.handle("closed"), null);
  assert.strictEqual(t.layer, "words");
  assert.strictEqual(t.handle("left-up"), "sentence");
  assert.deepStrictEqual(t.words, ["we"]);
  assert.strictEqual(t.layer, "words");          // as in the app, you stay on the word layer
  assert.strictEqual(t.predictions.length, 6);
});

test("up deletes a letter group, then a whole word", () => {
  const t = new TypeSession(dict);
  ["right", "left-up"].forEach((g) => t.handle(g));
  t.handle("closed"); t.handle("left-up"); t.handle("closed");   // word chosen, back to letters
  t.handle("left");
  assert.strictEqual(t.text(), "we N-T");
  t.handle("up");
  assert.strictEqual(t.text(), "we");
  t.handle("up");
  assert.strictEqual(t.text(), "");
});

test("the caregiver's question ranks answer words first", () => {
  assert.ok(contextWords("What would you like to drink?").includes("water"));
  const t = new TypeSession(dict);
  t.setQuestion("what do you want to eat");
  t.handle("left");                                   // N-T
  assert.deepStrictEqual(t.ui()["left-up"][1], "rice");
});

test("finish: left speaks, up-right goes back", () => {
  const t = new TypeSession(dict);
  ["right", "left-up"].forEach((g) => t.handle(g));
  t.handle("closed"); t.handle("left-up");
  assert.strictEqual(t.handle("up"), "finish");
  assert.strictEqual(t.handle("left"), "speak");
  assert.strictEqual(t.handle("right"), null);       // other gestures do nothing on the finish screen
  assert.strictEqual(t.handle("right-up"), "back");
  assert.strictEqual(t.finished, false);
});

test("right pages through word suggestions and wraps", () => {
  const t = new TypeSession(dict);
  t.handle("left-up"); t.handle("closed");
  const first = t.ui()["left-up"][0];
  t.handle("right");
  assert.notStrictEqual(t.ui()["left-up"][0], first);
  for (let i = 0; i < 2000 && t.page !== 1; i++) t.handle("right");
  assert.strictEqual(t.page, 1);
});

// ---------- Quick chat ----------
test("quick chat home shows the app's five categories and My phrases", () => {
  const q = new QuickChat();
  assert.deepStrictEqual(q.ui(), { up: "My phrases", "left-up": "Basic needs", "right-up": "Emotions",
    left: "Surroundings", closed: "Activities", right: "Medical needs" });
});

test("a category then one more gesture speaks its phrase; up goes back", () => {
  const q = new QuickChat();
  assert.strictEqual(q.handle("left-up"), null);
  assert.strictEqual(q.ui().up, "Back");
  assert.deepStrictEqual(q.handle("closed"), { speak: "I want to drink water." });
  q.handle("up");
  assert.strictEqual(q.page, "home");
});

test("My phrases lists what was actually said, or explains it is empty", () => {
  const book = new PhraseBook(null);
  const q = new QuickChat(() => book.top(5));
  q.handle("up");
  assert.strictEqual(q.ui()["left-up"], MY_PHRASES_EMPTY);
  assert.strictEqual(q.handle("left-up"), null);      // nothing to speak
  q.handle("up");
  book.record("I would like some water, please.");
  book.record("I would like some water, please.");
  book.record("Call my son.");
  q.handle("up");
  assert.deepStrictEqual(q.handle("left-up"), { speak: "I would like some water, please." });
});

test("phrase book survives a reload through storage", () => {
  const store = {};
  const storage = { getItem: (k) => store[k] ?? null, setItem: (k, v) => { store[k] = v; } };
  new PhraseBook(storage).record("Hello there.");
  assert.deepStrictEqual(new PhraseBook(storage).top(), ["Hello there."]);
});

// ---------- Spell ----------
test("spell: row then key, chosen by blinks", () => {
  const s = new Speller((p, prev) => dict.prefix(p, prev));
  s.start();
  s.tick();                                  // QWERTY -> ASDFGHJKL row
  assert.strictEqual(s.rows()[s.row].items[0].label, "A");
  s.select();                                // latch the row
  assert.strictEqual(s.tier, "column");
  s.tick(); s.tick();                        // A -> S -> D
  s.select();
  assert.strictEqual(s.text, "d");
  assert.strictEqual(s.tier, "row");
});

test("spell: a slow blink picks what was lit when the eyes closed", () => {
  const s = new Speller();
  s.start();
  s.select();                                // QWERTYUIOP row
  s.captureIntent();                         // eyes start closing on Q
  s.tick();                                  // the scan moves on to W while the eyes are still closed
  s.select();
  assert.strictEqual(s.text, "q");
});

test("spell: suggestions complete the word", () => {
  const s = new Speller((p, prev) => dict.prefix(p, prev));
  s.text = "wat";
  const row = s.rows()[0];
  assert.strictEqual(row.id, "suggestions");
  assert.ok(row.items.some((i) => i.value === "water"));
  s.activate(row.items.find((i) => i.value === "water"));
  assert.strictEqual(s.text, "water ");
});

test("spell: actions and speak", () => {
  const s = new Speller();
  s.text = "hi";
  s.activate({ type: "action", value: "space" });
  s.activate({ type: "action", value: "space" });
  assert.strictEqual(s.text, "hi ");
  s.activate({ type: "action", value: "backspace" });
  assert.deepStrictEqual(s.activate({ type: "action", value: "speak" }), { speak: "Hi" });
  assert.strictEqual(s.text, "");
  assert.strictEqual(s.activate({ type: "action", value: "speak" }), null);
});

test("spell: the scan pauses after four empty loops, and a blink resumes it", () => {
  const s = new Speller();
  s.start();
  for (let i = 0; i < 200 && s.tier !== "paused"; i++) s.tick();
  assert.strictEqual(s.tier, "paused");
  s.select();
  assert.strictEqual(s.tier, "row");
});

// ---------- run ----------
let failed = 0;
for (const [name, fn] of tests) {
  try { fn(); console.log(`ok - ${name}`); }
  catch (e) { failed++; console.log(`FAIL - ${name}\n  ${e.message}`); }
}
console.log(`${tests.length - failed}/${tests.length} passed`);
process.exit(failed ? 1 : 0);
