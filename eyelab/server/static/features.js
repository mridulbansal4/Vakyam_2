"use strict";

// Quick chat and Spell from the Vakyam app, as pure logic (no DOM) so they can be unit tested:
//   QuickChatManager.java   six-gesture categories and phrases, My phrases
//   ScanningEngine.kt       row/column scanning keyboard selected by a deliberate blink

// ---------- Quick chat ----------
const QC_CATEGORIES = {
  "left-up": { title: "Basic needs", phrases: ["I want to eat something.", "I want to sleep.", "I want to go to the bathroom.",
    "I want to drink water.", "I want to rest."] },
  "right-up": { title: "Emotions", phrases: ["I am happy.", "I am sad.", "I am angry.", "I am scared.", "I am confused."] },
  left: { title: "Surroundings", phrases: ["I feel hot.", "I feel cold.", "It is too noisy.", "It is too bright.", "It is too dark."] },
  closed: { title: "Activities", phrases: ["I want to go outside.", "I want to read the newspaper.", "I want to listen to music.",
    "I want to watch television.", "I want to listen to the radio."] },
  right: { title: "Medical needs", phrases: ["I feel some pain.", "I feel extreme pain.", "I need to eat my pills.",
    "I need to go to the hospital.", "I feel fine."] },
};
const QC_SLOTS = ["left-up", "right-up", "left", "closed", "right"];   // phrase order within a category
const MY_PHRASES_EMPTY = "Sentences you speak in Type or Spell will appear here.";

class QuickChat {
  constructor(myPhrases = () => []) {
    this.page = "home";          // "home", a category gesture, or "mine"
    this.myPhrases = myPhrases;
    this.mine = [];
  }

  // What each of the six targets shows
  ui() {
    if (this.page === "home") {
      const out = { up: "My phrases" };
      for (const g of QC_SLOTS) out[g] = QC_CATEGORIES[g].title;
      return out;
    }
    const list = this.page === "mine" ? this.mine : QC_CATEGORIES[this.page].phrases;
    const out = { up: "Back" };
    QC_SLOTS.forEach((g, i) => { out[g] = list[i] || (this.page === "mine" && i === 0 ? MY_PHRASES_EMPTY : ""); });
    return out;
  }

  // Returns {speak: text} when a phrase is chosen, otherwise null
  handle(look) {
    if (look === "up") {
      if (this.page === "home") { this.mine = this.myPhrases().slice(0, 5); this.page = "mine"; }
      else this.page = "home";
      return null;
    }
    if (!QC_SLOTS.includes(look)) return null;
    if (this.page === "home") { this.page = look; return null; }
    const list = this.page === "mine" ? this.mine : QC_CATEGORIES[this.page].phrases;
    const text = list[QC_SLOTS.indexOf(look)];
    return text ? { speak: text } : null;
  }
}

// ---------- My phrases: what the user actually says, most used first ----------
class PhraseBook {
  constructor(storage) {
    this.storage = storage;
    this.counts = {};
    try { this.counts = JSON.parse(storage?.getItem("vakyam.phrases") || "{}") || {}; } catch (_) { this.counts = {}; }
  }
  record(text) {
    const t = String(text || "").trim();
    if (!t || t.length > 200) return;
    this.counts[t] = (this.counts[t] || 0) + 1;
    try { this.storage?.setItem("vakyam.phrases", JSON.stringify(this.counts)); } catch (_) { /* private mode */ }
  }
  top(n = 5) {
    return Object.entries(this.counts).sort((a, b) => b[1] - a[1]).slice(0, n).map(([t]) => t);
  }
}

// ---------- Spell: two-tier scanner ----------
const SCAN_INTERVAL_MS = 1600;     // BlinkConfig.scanIntervalMs
const AUTO_SLEEP_LOOPS = 4;        // BlinkConfig.autoSleepLoops
const COLUMN_SWEEPS = 2;           // BlinkConfig.columnSweepsBeforeCancel
const LETTER_ROWS = ["QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"];
const ACTIONS = [["backspace", "Backspace"], ["space", "Space"], ["speak", "Speak & save"], ["cancel", "Cancel"]];

class Speller {
  constructor(suggest = () => []) {
    this.suggest = suggest;   // (partialWord, previousWords) -> words
    this.text = "";
    this.tier = "paused";     // "row", "column" or "paused"
    this.row = 0;
    this.col = 0;
    this.loops = 0;
    this.sweeps = 0;
    this.intent = null;
  }

  rows() {
    const words = this.text.split(" ");
    const partial = words[words.length - 1];
    const suggestions = partial ? this.suggest(partial, words.slice(0, -1)).slice(0, 2) : [];
    const out = [];
    if (suggestions.length) out.push({ id: "suggestions", items: suggestions.map((w) => ({ id: `word:${w}`, label: w, value: w, type: "word" })) });
    LETTER_ROWS.forEach((r, i) => out.push({ id: `letters${i}`, items: [...r].map((c) => ({ id: c, label: c, value: c.toLowerCase(), type: "char" })) }));
    out.push({ id: "actions", items: ACTIONS.map(([id, label]) => ({ id, label, value: id, type: "action" })) });
    return out;
  }

  start() { this.tier = "row"; this.row = 0; this.col = 0; this.loops = 0; this.intent = null; }
  stop() { this.tier = "paused"; this.intent = null; }

  // One scan step (call every SCAN_INTERVAL_MS)
  tick() {
    const rows = this.rows();
    if (this.tier === "row") {
      const next = (this.row + 1) % rows.length;
      if (next <= this.row && ++this.loops >= AUTO_SLEEP_LOOPS) { this.tier = "paused"; return; }
      this.row = next;
    } else if (this.tier === "column") {
      const items = rows[this.row]?.items || [];
      if (this.col + 1 < items.length) this.col += 1;
      else if (++this.sweeps >= COLUMN_SWEEPS) { this.tier = "row"; this.col = 0; this.loops = 0; }
      else this.col = 0;
    }
  }

  // The eyes started closing: remember what was lit, in case the scan moves on before the blink completes
  captureIntent() { if (this.tier !== "paused") this.intent = { tier: this.tier, row: this.row, col: this.col }; }

  // A deliberate blink. Returns {speak: text} for Speak & save, otherwise null
  select() {
    if (this.intent) { Object.assign(this, this.intent); this.intent = null; }
    if (this.tier === "paused") { this.start(); return null; }
    const rows = this.rows();
    if (this.tier === "row") {
      this.row = Math.min(this.row, rows.length - 1);
      this.tier = "column"; this.col = 0; this.sweeps = 0;
      return null;
    }
    const item = rows[this.row]?.items[this.col];
    this.tier = "row"; this.row = 0; this.col = 0; this.loops = 0;
    return item ? this.activate(item) : null;
  }

  activate(item) {
    if (item.type === "char") this.text += item.value;
    else if (item.type === "word") {
      const words = this.text.split(" ");
      words[words.length - 1] = item.value;
      this.text = words.join(" ") + " ";
    } else if (item.value === "backspace") this.text = this.text.slice(0, -1);
    else if (item.value === "space") { if (this.text && !this.text.endsWith(" ")) this.text += " "; }
    else if (item.value === "cancel") this.text = "";
    else if (item.value === "speak") {
      const said = this.text.trim();
      if (!said) return null;
      this.text = "";
      return { speak: said.charAt(0).toUpperCase() + said.slice(1) };
    }
    return null;
  }
}

const VakyamFeatures = { QuickChat, PhraseBook, Speller, QC_CATEGORIES, SCAN_INTERVAL_MS, MY_PHRASES_EMPTY };
if (typeof window !== "undefined") window.VakyamFeatures = VakyamFeatures;
if (typeof module !== "undefined") module.exports = VakyamFeatures;
