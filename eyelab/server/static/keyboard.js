"use strict";

// The Vakyam app's Type screen, ported from the Android sources so the web demo behaves the same:
//   BlurryInput.java       four-group ambiguous keyboard, dictionary + bigram + context ranking
//   ContextVocabulary.kt   answer words for the caregiver's question (English lists)
//   TextInfo.java          the "Text:" line
//   TextEntryManager.java  letter layer / word layer / finish screen, driven by six eye gestures

const GROUP_LABELS = ["A-F", "G-M", "N-T", "U-Z"];
const GROUP_NAMES = ["ABCDEF", "GHIJKLM", "NOPQRST", "UVWXYZ"];
const NO_MATCH = "No match";
const WORDS_PER_PAGE = 3;
const NEXT_WORD_SUGGESTIONS = 6;
const START_SENTENCE = "All ALS patients should have a voice in the digital world.";

// Each letter becomes the number of its group: "water" -> w a t e r -> 3 0 2 0 2
function groupCode(word) {
  let code = "";
  for (const c of String(word).toLowerCase().normalize("NFD")) {
    if (c >= "a" && c <= "f") code += "0";
    else if (c >= "g" && c <= "m") code += "1";
    else if (c >= "n" && c <= "t") code += "2";
    else if (c >= "u" && c <= "z") code += "3";
  }
  return code;
}

class Dictionary {
  constructor(words, bigramLines) {
    this.words = words;                       // frequency order
    this.codes = words.map(groupCode);
    this.bigrams = new Map();                 // word -> [next words], most frequent first
    for (const line of bigramLines) {
      const [a, b, n] = line.split(" ");
      if (!a || !b || !n) continue;
      if (!this.bigrams.has(a)) this.bigrams.set(a, []);
      this.bigrams.get(a).push([b, parseInt(n, 10)]);
    }
    for (const list of this.bigrams.values()) list.sort((x, y) => y[1] - x[1]);
  }

  followers(currentWords) {
    if (!currentWords.length) return [];
    return (this.bigrams.get(currentWords[currentWords.length - 1].toLowerCase()) || []).map(([w]) => w);
  }

  // BlurryInput.getMatchingWords: the question's answer words, then bigram followers, then the dictionary
  matching(code, currentWords, contextWords) {
    if (!code) return [];
    const out = [];
    const add = (w) => { if (w && groupCode(w).startsWith(code) && !out.includes(w)) out.push(w); };
    contextWords.forEach(add);
    this.followers(currentWords).forEach(add);
    for (let i = 0; i < this.words.length; i++) {
      if (this.codes[i].startsWith(code) && !out.includes(this.words[i])) out.push(this.words[i]);
    }
    return out;
  }

  // Spell's suggestions: words that start with what has been spelled, bigram followers of the last word first
  prefix(partial, previousWords) {
    const p = String(partial).toLowerCase();
    const out = [];
    const add = (w) => { if (w && w.toLowerCase().startsWith(p) && w.toLowerCase() !== p && !out.includes(w)) out.push(w); };
    this.followers(previousWords).forEach(add);
    for (const w of this.words) { add(w); if (out.length >= 10) break; }
    return out;
  }

  // BlurryInput.getExactMatchingWords("") after a word: grammar first (bigrams), then the question's words
  nextWords(currentWords, contextWords) {
    const out = [];
    const add = (w) => { if (w && !out.includes(w)) out.push(w); };
    this.followers(currentWords).forEach(add);
    contextWords.forEach(add);
    this.words.forEach(add);
    return out;
  }
}

function page(list, n) {
  if (!list.length || (n - 1) * WORDS_PER_PAGE >= list.length) return null;
  return [0, 1, 2].map((i) => list[(n - 1) * WORDS_PER_PAGE + i] || null);
}

// ContextVocabulary.wordsFor, English lists
const TOPICS = [
  { triggers: ["eat", "eating", "food", "hungry", "breakfast", "lunch", "dinner", "meal", "snack"], phrases: [],
    words: ["rice", "roti", "dal", "soup", "bread", "egg", "chicken", "curry", "fruit", "banana", "apple", "idli", "dosa",
      "khichdi", "curd", "paratha", "sandwich", "biscuit", "salad", "noodles", "fish", "vegetables", "porridge", "oats",
      "pasta", "pizza", "sweet", "cake", "paneer", "poha", "upma", "mango", "toast", "potato"] },
  { triggers: ["drink", "drinking", "thirsty", "juice", "beverage", "tea", "coffee", "chai"], phrases: [],
    words: ["water", "tea", "coffee", "milk", "juice", "soup", "lassi", "buttermilk", "coconut", "lemonade", "cold", "hot", "warm"] },
  { triggers: ["hurt", "hurts", "hurting", "pain", "painful", "ache", "aching", "sore", "injury", "itching"], phrases: [],
    words: ["head", "back", "neck", "leg", "stomach", "chest", "throat", "arm", "hand", "shoulder", "knee", "foot", "eyes",
      "tooth", "ear", "mouth", "hip", "everywhere", "here"] },
  { triggers: ["where", "go", "going", "visit", "place", "trip", "outing", "travel", "outside"], phrases: [],
    words: ["park", "home", "garden", "temple", "market", "hospital", "outside", "beach", "mall", "balcony", "terrace",
      "church", "mosque", "office", "village", "shop", "restaurant", "cinema", "room", "bathroom", "bed", "walk", "friend",
      "school", "lake", "river", "station"] },
  { triggers: ["who", "whom", "call", "meet", "phone"], phrases: [],
    words: ["mom", "dad", "wife", "husband", "son", "daughter", "brother", "sister", "friend", "doctor", "nurse", "family",
      "mother", "father", "grandma", "grandpa", "children", "everyone"] },
  { triggers: ["when", "time"], phrases: [],
    words: ["now", "later", "today", "tomorrow", "morning", "evening", "night", "soon", "afternoon", "tonight", "weekend"] },
  { triggers: ["watch", "play", "listen", "tv", "music", "bored"], phrases: ["want to do", "like to do"],
    words: ["tv", "music", "news", "movie", "cricket", "song", "book", "read", "sleep", "rest", "walk", "game", "prayer",
      "video", "phone", "talk"] },
  { triggers: ["feel", "feeling", "mood"], phrases: ["how are you"],
    words: ["good", "fine", "okay", "better", "tired", "sleepy", "sad", "happy", "pain", "weak", "sick", "bored", "worried",
      "scared", "uncomfortable", "cold", "hot", "great", "bad", "worse"] },
  { triggers: ["need", "want", "anything", "help", "comfortable"], phrases: [],
    words: ["water", "food", "toilet", "help", "blanket", "fan", "AC", "light", "pillow", "medicine", "rest", "sleep",
      "bathroom", "turn", "position", "nothing", "doctor", "nurse", "phone", "tv"] },
];

function contextWords(question) {
  const text = String(question || "").toLowerCase();
  if (!text.trim()) return [];
  const tokens = new Set(text.split(/[^a-z]+/).filter(Boolean));
  const out = [];
  for (const t of TOPICS) {
    if (!t.triggers.some((w) => tokens.has(w)) && !t.phrases.some((p) => text.includes(p))) continue;
    for (const w of t.words) if (!out.includes(w)) out.push(w);
  }
  return out;
}

// TextEntryManager: two layers and a finish screen, six gestures
class TypeSession {
  constructor(dictionary) {
    this.dict = dictionary;
    this.words = [];
    this.groups = "";            // pending letter groups, e.g. "30202"
    this.predictions = [];
    this.page = 1;
    this.layer = "letters";      // or "words"
    this.finished = false;
    this.sentence = START_SENTENCE;
    this.question = "";
    this.context = [];
  }

  setQuestion(text) {
    this.question = String(text || "").trim();
    this.context = contextWords(this.question);
    if (this.layer === "letters" && this.page === 1 && this.groups) this.refreshMatches();
  }

  refreshMatches() {
    this.predictions = this.groups ? this.dict.matching(this.groups, this.words, this.context) : [];
  }

  text() {
    return [...this.words, ...[...this.groups].map((g) => GROUP_LABELS[+g])].join(" ");
  }

  // Returns what the page must do: "sentence" (ask for a new sentence), "speak", or null
  handle(look) {
    if (this.finished) {
      if (look === "left") return "speak";
      if (look === "right-up") { this.finished = false; return "back"; }
      return null;
    }
    if (this.layer === "words") {
      if (look === "up") { this.finished = true; return "finish"; }
      if (look === "left-up") return this.pick(0);
      if (look === "right-up") return this.pick(1);
      if (look === "left") return this.pick(2);
      if (look === "right") { this.page = page(this.predictions, this.page + 1) ? this.page + 1 : 1; return null; }
      if (look === "closed") { this.layer = "letters"; this.page = 1; return null; }
      return null;
    }
    // letters
    if (look === "up") {
      if (this.groups) this.groups = this.groups.slice(0, -1);
      else if (this.words.length) { this.words.pop(); this.refreshMatches(); return this.words.length ? "sentence" : null; }
    } else if (look === "left-up") this.groups += "0";
    else if (look === "right-up") this.groups += "1";
    else if (look === "left") this.groups += "2";
    else if (look === "right") this.groups += "3";
    else if (look === "closed") { this.layer = "words"; this.page = 1; return null; }
    this.refreshMatches();
    return null;
  }

  pick(slot) {
    const index = slot + (this.page - 1) * WORDS_PER_PAGE;
    this.page = 1;
    if (index >= this.predictions.length) return null;
    this.words.push(this.predictions[index]);
    this.groups = "";
    this.predictions = this.dict.nextWords(this.words, this.context).slice(0, NEXT_WORD_SUGGESTIONS);
    this.sentence = "Generating…";
    return "sentence";
  }

  clear() {
    this.words = []; this.groups = ""; this.predictions = []; this.page = 1;
    this.layer = "letters"; this.finished = false; this.sentence = START_SENTENCE;
  }

  // What each target shows: main label, small label
  ui() {
    const p = page(this.predictions, this.page) || [];
    const word = (i) => p[i] || NO_MATCH;
    if (this.layer === "words") {
      return {
        "left-up": [word(0), GROUP_NAMES[0]], "right-up": [word(1), GROUP_NAMES[1]],
        left: [word(2), GROUP_NAMES[2]], right: ["Next page", GROUP_NAMES[3]],
        up: ["Finish"], closed: ["Switch"],
      };
    }
    return {
      "left-up": [GROUP_NAMES[0], word(0)], "right-up": [GROUP_NAMES[1], word(1)],
      left: [GROUP_NAMES[2], word(2)], right: [GROUP_NAMES[3], NO_MATCH],
      up: ["Delete"], closed: ["Switch"],
    };
  }
}

const VakyamKeyboard = { Dictionary, TypeSession, groupCode, contextWords };
if (typeof window !== "undefined") window.VakyamKeyboard = VakyamKeyboard;
if (typeof module !== "undefined") module.exports = VakyamKeyboard;
