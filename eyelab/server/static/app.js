"use strict";

// Vakyam Eye Lab client: webcam frames to the server, one in flight at a time; results drive the page.

const FRAME_WIDTH = 480;       // frames are scaled to this width before JPEG encoding
const JPEG_QUALITY = 0.75;

const $ = (id) => document.getElementById(id);
const screens = ["intro", "calibrate", "talk"];
const state = {
  ws: null, stream: null, inFlight: false, lastSent: 0, maxFps: 12, polly: false,
  mode: "intro", speaking: false, frames: [], camera: false, coaching: false,
  kb: null, sentenceId: 0, audio: null, spoken: false, sentenceSource: null,
  tab: "type", quick: null, speller: null, phrases: null, scanTimer: null, closing: false,
};

function storage() { try { return window.localStorage; } catch (_) { return null; } }

// The app's keyboard, with the app's own word lists
async function loadKeyboard() {
  if (state.kb) return;
  const [words, bigrams] = await Promise.all(["words", "bigrams"].map((n) =>
    fetch(`/words/${n}`).then((r) => { if (!r.ok) throw new Error("word lists unavailable"); return r.text(); })));
  const dict = new VakyamKeyboard.Dictionary(words.split(/\r?\n/).filter(Boolean), bigrams.split(/\r?\n/));
  state.kb = new VakyamKeyboard.TypeSession(dict);
  state.phrases = new VakyamFeatures.PhraseBook(storage());
  state.quick = new VakyamFeatures.QuickChat(() => state.phrases.top(5));
  state.speller = new VakyamFeatures.Speller((partial, previous) => dict.prefix(partial, previous));
}

function show(name) {
  state.mode = name;
  for (const s of screens) $(s).hidden = s !== name;
  document.querySelector(".preview").hidden = !state.camera || !$("showCamera").checked;
  $("tabs").hidden = !(name === "talk" || name === "calibrate");
  $("recalibrate").hidden = !state.camera;
  $("lookTestBtn").hidden = !state.camera;
  $("lookTestBtn").classList.toggle("active", name === "calibrate" && state.lookTest);
  for (const t of document.querySelectorAll(".tab[data-tab]")) t.classList.toggle("active", name === "talk" && t.dataset.tab === state.tab);
  $("recalibrate").classList.toggle("active", name === "calibrate");
  if (name !== "talk") stopScan();
  $("coachBtn").hidden = !(state.camera && name === "talk");
  $("hoodToggle").hidden = !(state.camera && name === "talk");
}

// ---------- sound and speech ----------
let audioCtx;
function beep(freq = 880, ms = 120) {
  try {
    audioCtx = audioCtx || new AudioContext();
    const osc = audioCtx.createOscillator(), gain = audioCtx.createGain();
    osc.frequency.value = freq;
    gain.gain.setValueAtTime(0.15, audioCtx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, audioCtx.currentTime + ms / 1000);
    osc.connect(gain).connect(audioCtx.destination);
    osc.start(); osc.stop(audioCtx.currentTime + ms / 1000);
  } catch (_) { /* sound is a nicety */ }
}
function say(text) {
  if (!("speechSynthesis" in window) || !text) return;
  speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.rate = 0.95;
  speechSynthesis.speak(u);
}

// ---------- connection ----------
function connect() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${location.protocol === "https:" ? "wss" : "ws"}://${location.host}/ws`);
    ws.binaryType = "arraybuffer";
    ws.onopen = () => { state.ws = ws; resolve(ws); };
    ws.onerror = () => reject(new Error("Could not reach the Eye Lab server."));
    ws.onclose = () => { state.ws = null; state.inFlight = false; };
    ws.onmessage = (e) => onMessage(JSON.parse(e.data));
  });
}
function send(obj) { if (state.ws && state.ws.readyState === 1) state.ws.send(JSON.stringify(obj)); }

// ---------- camera and frame loop ----------
async function startCamera() {
  state.stream = await navigator.mediaDevices.getUserMedia({ video: { width: 640, height: 480, facingMode: "user" }, audio: false });
  const video = $("video");
  video.srcObject = state.stream;
  await video.play();
  state.camera = true;
}
function pump() {
  if (!state.camera || !state.ws || state.ws.readyState !== 1) return;
  if (state.inFlight || state.speaking) return requestAnimationFrame(pump);
  const now = performance.now();
  if (now - state.lastSent < 1000 / state.maxFps) return requestAnimationFrame(pump);
  const video = $("video");
  if (!video.videoWidth) return requestAnimationFrame(pump);
  const grab = $("grab");
  grab.width = FRAME_WIDTH;
  grab.height = Math.round(FRAME_WIDTH * video.videoHeight / video.videoWidth);
  grab.getContext("2d").drawImage(video, 0, 0, grab.width, grab.height);
  state.inFlight = true;
  state.lastSent = now;
  grab.toBlob((blob) => {
    if (!blob || !state.ws) { state.inFlight = false; return requestAnimationFrame(pump); }
    blob.arrayBuffer().then((buf) => state.ws.send(buf));
  }, "image/jpeg", JPEG_QUALITY);
}

// ---------- messages ----------
function onMessage(msg) {
  if (msg.type === "hello") { $("cvVersion").textContent = msg.opencv; return; }
  if (msg.type === "error") {
    if (msg.error === "busy") showIntroError(msg.message);
    else if (msg.error === "not_calibrated" && state.mode === "calibrate") {
      state.lookTest = false;
      $("calibStatus").textContent = msg.message;
    }
    state.inFlight = false; requestAnimationFrame(pump); return;
  }
  if (msg.type === "calibration") return onCalibration(msg);
  if (msg.type === "look_test") return onLookTest(msg);
  if (msg.type === "sentence") return onSentence(msg);
  if (msg.type === "coach") return onCoach(msg);
  if (msg.type === "settings") { $("sensitivity").value = msg.sensitivity; $("sensValue").textContent = msg.sensitivity; return; }
  if (msg.type !== "frame") return;
  state.inFlight = false;
  requestAnimationFrame(pump);
  if (msg.skipped) return;
  state.frames.push(performance.now());
  if (state.frames.length > 20) state.frames.shift();
  drawFace(msg.face);
  state.lastCalibrated = msg.calibrated;
  if (msg.calibration) onCalibration(msg.calibration);
  if (msg.look_test) onLookTest(msg.look_test);
  if (state.mode === "talk" && !state.coaching) onTalkFrame(msg);
  updateHood(msg);
}

// ---------- calibration ----------
function onCalibration(c) {
  if (c.finished) {
    $("target").classList.add("captured");
    beep(1046, 180);
    say("Calibration finished. You can type with your eyes now.");
    // A caregiver can run the look test from here; otherwise typing starts by itself, so no hands are needed
    $("calibStep").textContent = "Calibration finished";
    $("calibPrompt").textContent = "Typing starts in a few seconds.";
    $("calibStatus").textContent = "";
    $("calibDone").hidden = false;
    clearTimeout(state.typingTimer);
    state.typingTimer = setTimeout(startTyping, 8000);
    return;
  }
  if (!c.running) return;
  const target = $("target");
  if (c.captured) {
    beep();
    target.classList.add("captured");
    setTimeout(() => target.classList.remove("captured"), 300);
  }
  target.dataset.look = c.look;
  $("ring").style.strokeDashoffset = String(100 - 100 * c.progress);
  $("calibStep").textContent = `Step ${c.step + 1} of ${c.steps}`;
  $("calibPrompt").textContent = c.prompt;
  $("calibStatus").textContent = c.status_text;
  if (c.reprompt) say(c.prompt);
}
function startTyping() {
  clearTimeout(state.typingTimer);
  $("calibDone").hidden = true;
  if (state.mode === "calibrate" && !state.lookTest) { setTab(state.tab); renderType(); }
}
function startCalibration() {
  clearTimeout(state.typingTimer);
  $("calibDone").hidden = true;
  state.lookTest = false;
  $("calibrate").classList.remove("reporting");
  $("lookReport").hidden = true;
  show("calibrate");
  send({ type: "calibrate" });
}

// ---------- look test: a minute of prompted looks, scored, so you can see what is read as what ----------
function startLookTest() {
  clearTimeout(state.typingTimer);
  $("calibDone").hidden = true;
  if (!state.lastCalibrated) { setStatus("Calibrate first, then run the look test.", "warn"); return startCalibration(); }
  state.lookTest = true;
  $("calibrate").classList.remove("reporting");
  $("lookReport").hidden = true;
  show("calibrate");
  send({ type: "look_test" });
}
function onLookTest(c) {
  if (c.finished) return showLookReport(c.report);
  const target = $("target");
  if (c.reprompt) {
    beep(c.look === "closed" ? 520 : 880, 120);
    say(c.prompt);
  }
  target.dataset.look = c.look;
  $("ring").style.strokeDashoffset = String(100 - 100 * c.progress);
  $("calibStep").textContent = `Look test · ${c.step + 1} of ${c.steps}`;
  $("calibPrompt").textContent = c.prompt;
  $("calibStatus").textContent = c.status_text;
}
const pct = (x) => `${Math.round(100 * x)}%`;
function showLookReport(r) {
  state.lookTest = false;
  beep(1046, 180);
  $("calibrate").classList.add("reporting");
  $("calibStep").textContent = "Look test finished";
  $("calibPrompt").textContent = "";
  $("calibStatus").textContent = "";
  $("lookSummary").textContent = `${pct(r.accuracy)} of moments read right · ${r.wrong_selections} wrong selection${r.wrong_selections === 1 ? "" : "s"}`;
  const rows = $("lookRows");
  rows.innerHTML = "";
  for (const [name, v] of Object.entries(r.looks)) {
    const tr = document.createElement("tr");
    const readAs = Object.entries(v.read_as).filter(([, f]) => f >= 0.03).map(([k, f]) => `${LOOK_NAMES[k] || k} ${pct(f)}`).join(", ");
    for (const [text, bad] of [[LOOK_NAMES[name] || name, false], [pct(v.correct), v.correct < 0.6], [pct(v.unsure), false],
      [readAs || "–", v.wrong >= 0.1], [String(v.wrong_selections), v.wrong_selections > 0]]) {
      const td = document.createElement("td"); td.textContent = text; if (bad) td.className = "bad"; tr.append(td);
    }
    rows.append(tr);
  }
  const rest = r.looks.straight;
  $("lookAdvice").textContent = rest && rest.wrong_selections > 0
    ? "Resting your eyes picked something. Calibrate again sitting the way you will type, then test again."
    : "Resting your eyes picked nothing. Unsure moments are fine: they never type.";
  $("lookReport").hidden = false;
}

// ---------- typing, as on the app's Type screen ----------
function renderType() {
  const kb = state.kb;
  if (!kb) return;
  const ui = kb.ui();
  for (const tile of document.querySelectorAll("#talk .tile")) {
    const [main, sub] = ui[tile.dataset.look] || [];
    const mainEl = tile.querySelector(".main");
    mainEl.textContent = main ?? "";
    mainEl.classList.toggle("long", (main ?? "").length > 8);
    const subEl = tile.querySelector(".sub");
    if (subEl) subEl.textContent = sub ?? "";
    tile.classList.toggle("empty", main === "No match");
  }
  $("textLine").textContent = `Text: ${kb.text()}`;
  $("llm").textContent = `LLM: ${kb.sentence}`;
  if (!$("sentence").hidden) showFinish();
}
function flash(look) {
  for (const tile of document.querySelectorAll(`.tile[data-look="${look}"]`)) {
    tile.classList.remove("fired"); void tile.offsetWidth; tile.classList.add("fired");
  }
}
function choose(look) {
  const kb = state.kb;
  if (!kb) return;
  flash(look);
  const action = kb.handle(look);
  beep(look === "closed" ? 520 : 880, 90);
  if (action === "sentence") requestSentence();
  else if (action === "finish") showFinish();
  else if (action === "speak") speakSentence();
  else if (action === "back") {
    $("sentence").hidden = true;
    if (state.spoken) { kb.clear(); state.spoken = false; state.audio = null; }
  }
  renderType();
}
function requestSentence() {
  const kb = state.kb;
  state.audio = null;
  state.sentenceSource = null;
  if (!kb.words.length) { kb.sentence = ""; return; }
  const id = ++state.sentenceId;
  if (state.ws) send({ type: "sentence", id, keywords: kb.words.slice(-4), question: kb.question });
  else onSentence({ id, text: `I want ${kb.words.join(" ")}, please.`, source: "fallback", ms: 0 });
}
function onSentence(s) {
  if (s.id !== undefined && s.id !== state.sentenceId) return; // a newer word has been typed since
  state.kb.sentence = s.text;
  state.audio = s.audio || null;
  state.sentenceSource = s;
  renderType();
}
function showFinish() {
  const kb = state.kb;
  $("sentenceText").textContent = `LLM: ${kb.sentence}`;
  const s = state.sentenceSource;
  $("sentenceSource").textContent = !kb.words.length ? "Type a word first, or look right up to go back."
    : !s ? "Writing your sentence…"
    : s.source === "claude" ? `Written by Claude on Amazon Bedrock in ${(s.ms / 1000).toFixed(1)} s · look left to speak`
    : "Built-in sentence (the language model is not available here) · look left to speak";
  $("sentence").hidden = false;
}
function speakSentence() {
  const kb = state.kb;
  if (!kb.words.length || !state.sentenceSource) return;
  if (state.audio) new Audio(state.audio).play().catch(() => say(kb.sentence));
  else say(kb.sentence);
  state.phrases.record(kb.sentence);
  state.spoken = true;
}

// ---------- tabs ----------
function setTab(tab) {
  state.tab = tab;
  const panels = { quick: "panelQuick", type: "panelType", spell: "panelSpell", settings: "panelSettings" };
  for (const [name, id] of Object.entries(panels)) $(id).hidden = name !== tab;
  // The camera and readout live in the middle resting area of the open screen, or under it
  const slot = document.querySelector(`#${panels[tab]} .cam-slot`);
  if (slot) slot.append($("camrow")); else $("hood").before($("camrow"));
  if (state.mode !== "talk") show("talk"); else show("talk");
  if (tab === "spell") startScan(); else stopScan();
  if (tab === "quick") renderQuick();
  if (tab === "settings") $("phraseCount").textContent = `${state.phrases.top(100).length} saved`;
}
// Every gesture goes to whichever screen is open
function gesture(look) {
  if (state.tab === "type") return choose(look);
  if (state.tab === "quick") return quickChoose(look);
  if (state.tab === "spell" && look === "closed") return spellSelect();
}

// ---------- Quick chat ----------
function renderQuick() {
  const ui = state.quick.ui();
  for (const tile of document.querySelectorAll("#panelQuick .tile")) {
    const text = ui[tile.dataset.look] || "";
    const main = tile.querySelector(".main");
    main.textContent = text;
    main.className = "main" + (text === VakyamFeatures.MY_PHRASES_EMPTY ? " note" : text.length > 14 ? " phrase" : "");
  }
}
function quickChoose(look) {
  flash(look);
  beep(look === "closed" ? 520 : 880, 90);
  const out = state.quick.handle(look);
  if (out && out.speak) { say(out.speak); state.phrases.record(out.speak); }
  renderQuick();
}

// ---------- Spell ----------
function renderSpell() {
  const sp = state.speller;
  const spelled = $("spelled");
  spelled.innerHTML = "";
  if (sp.text) spelled.append(document.createTextNode(sp.text));
  else { const ph = document.createElement("span"); ph.className = "placeholder"; ph.textContent = "Start spelling…"; spelled.append(ph); }
  const caret = document.createElement("span"); caret.className = "caret"; spelled.append(caret);
  const keys = $("spellKeys");
  keys.innerHTML = "";
  sp.rows().forEach((row, r) => {
    const div = document.createElement("div");
    div.className = "krow" + (sp.tier !== "paused" && r === sp.row ? " active" : "");
    row.items.forEach((item, c) => {
      const b = document.createElement("button");
      b.className = `k ${item.type}` + (sp.tier === "column" && r === sp.row && c === sp.col ? " active" : "");
      b.dataset.id = item.id;
      b.textContent = item.label;
      b.addEventListener("click", () => { const out = sp.activate(item); if (out) spellSpeak(out.speak); renderSpell(); });
      div.append(b);
    });
    keys.append(div);
  });
  $("spellState").textContent = sp.tier === "row" ? "Choosing a row: blink when your row is lit"
    : sp.tier === "column" ? "Choosing a key: blink when your key is lit" : "Paused: blink (or press Space) to start";
}
function startScan() {
  stopScan();
  state.speller.start();
  state.scanTimer = setInterval(() => { state.speller.tick(); renderSpell(); }, VakyamFeatures.SCAN_INTERVAL_MS);
  renderSpell();
}
function stopScan() {
  if (state.scanTimer) clearInterval(state.scanTimer);
  state.scanTimer = null;
  if (state.speller) state.speller.stop();
}
function spellSelect() {
  if (!state.scanTimer) startScan();
  beep(880, 90);
  const out = state.speller.select();
  if (out && out.speak) spellSpeak(out.speak);
  // restart the interval so the newly lit row or key gets a full turn
  clearInterval(state.scanTimer);
  state.scanTimer = setInterval(() => { state.speller.tick(); renderSpell(); }, VakyamFeatures.SCAN_INTERVAL_MS);
  renderSpell();
}
function spellSpeak(text) { say(text); state.phrases.record(text); }
function setQuestion(text) {
  if (!state.kb) return;
  state.kb.setQuestion(text);
  if (state.kb.words.length) requestSentence();
  renderType();
}

const LOOK_NAMES = { "left-up": "top-left", "right-up": "top-right", left: "bottom-left", right: "bottom-right",
  up: "up", closed: "blink (eyes closed)", straight: "centre (resting)" };
function onTalkFrame(msg) {
  const holding = msg.holding || {};
  const look = msg.look;
  $("seeLook").textContent = !msg.face ? "no face" : !msg.calibrated ? "calibrate first" : look ? (LOOK_NAMES[look.name] || look.name) : "–";
  const ok = $("seeOk");
  ok.textContent = look ? (look.ok ? "accepted" : "unsure") : "";
  ok.className = "ok " + (look ? (look.ok ? "yes" : "no") : "");
  $("seeHold").style.width = `${Math.round(100 * (holding.progress || 0))}%`;
  for (const tile of document.querySelectorAll(".tile")) {
    tile.style.setProperty("--fill", tile.dataset.look === holding.look ? holding.progress : 0);
  }
  if (!msg.face) setStatus("Face not found. Look at the camera.", "warn");
  else if (msg.issue_text) setStatus(msg.issue_text, "warn");
  else setStatus("", "hint");
  if (state.tab === "spell") {
    const closing = holding.look === "closed" && holding.progress > 0;
    if (closing && !state.closing) state.speller.captureIntent();   // remember what was lit as the eyes closed
    state.closing = closing;
    $("spellEyes").textContent = closing ? "Eyes closed" : "Eyes open";
    $("spellHold").style.width = `${Math.round(100 * (holding.look === "closed" ? holding.progress : 0))}%`;
  }
  if (msg.selected) gesture(msg.selected);
}

// ---------- overlay and hood ----------
function drawFace(face) {
  const canvas = $("overlay"), video = $("video");
  if (!video.videoWidth) return;
  canvas.width = video.videoWidth; canvas.height = video.videoHeight;
  const ctx = canvas.getContext("2d");
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  if (!face) return;
  const k = canvas.width / FRAME_WIDTH;
  const [x, y, w, h] = face.box.map((v) => v * k);
  ctx.strokeStyle = "#EDC565"; ctx.lineWidth = 3;
  ctx.strokeRect(x, y, w, h);
  ctx.fillStyle = "#B9DAAE";
  for (const [px, py] of face.landmarks) { ctx.beginPath(); ctx.arc(px * k, py * k, 4, 0, 2 * Math.PI); ctx.fill(); }
}
function updateHood(msg) {
  if ($("hood").hidden) return;
  if (msg.crops) { $("cropL").src = msg.crops[0]; $("cropR").src = msg.crops[1]; }
  $("hMs").textContent = `${msg.ms} ms`;
  if (state.frames.length > 1) {
    const span = (state.frames[state.frames.length - 1] - state.frames[0]) / 1000;
    $("hFps").textContent = `${((state.frames.length - 1) / span).toFixed(1)} fps`;
  }
  const q = msg.quality || {};
  $("hScore").textContent = msg.face ? msg.face.score.toFixed(2) : "no face";
  $("hPose").textContent = q.yaw_deg != null ? `${q.yaw_deg.toFixed(0)}° / ${q.pitch_deg.toFixed(0)}°` : "–";
  $("hSharp").textContent = q.sharpness != null ? q.sharpness.toFixed(0) : "–";
  $("hIssue").textContent = msg.issue ? msg.issue.toLowerCase().replace("_", " ") : "OK";
  const errors = (msg.look && msg.look.errors) || {};
  const box = $("hErrors");
  box.innerHTML = "";
  const best = msg.look && msg.look.name;
  for (const [name, err] of Object.entries(errors).sort((a, b) => a[1] - b[1])) {
    const row = document.createElement("div");
    row.className = "err" + (name === best && msg.look.ok ? " best" : "");
    const pct = Math.max(2, Math.min(100, 100 * (1 - err / 2)));
    row.innerHTML = `<span>${name}</span><span class="barwrap"><span class="bar-fill" style="width:${pct}%"></span></span><span>${err.toFixed(2)}</span>`;
    box.appendChild(row);
  }
}
function setStatus(text, kind) {
  const el = $("liveStatus");
  el.textContent = text;
  el.dataset.kind = kind;
}
// ---------- Calibration Coach ----------
const TOOL_LABELS = {
  inspect_camera: "Inspect camera", check_templates: "Compare templates", self_test: "Self test",
  instruct_user: "Instruct user", recalibrate_look: "Re-record look", ask_caregiver: "Ask caregiver",
  propose_sensitivity: "Propose setting", finish: "Finish",
};
function startCoach() {
  if (state.tab !== "type") setTab("type");   // the coach points at the Type screen's six targets
  state.coaching = true;
  $("coachSteps").innerHTML = "";
  $("coachResult").hidden = true;
  $("coachPanel").hidden = false;
  document.body.classList.add("coach-open");
  for (const tile of document.querySelectorAll(".tile")) tile.style.setProperty("--fill", 0);
  send({ type: "coach_start" });
}
function stopCoach() { send({ type: "coach_stop" }); }
function saw(tool, r) {
  if (!r) return "";
  if (r.error) return r.error;
  const cam = r.camera_after || (tool === "inspect_camera" ? r : null);
  if (cam) {
    const issues = Object.entries(cam.issues || {}).map(([k, v]) => `${k.toLowerCase().replace("_", " ")} ${Math.round(v * 100)}%`);
    return `face ${Math.round((cam.face_found_rate || 0) * 100)}% · brightness ${cam.face_brightness ?? "–"} · yaw ${cam.yaw_deg ?? "–"}°` +
      (issues.length ? ` · ${issues.join(", ")}` : " · no issues");
  }
  if (tool === "check_templates") {
    const c = r.confusable || [];
    const p = (r.closest_pairs || [])[0];
    return c.length ? `look-alikes: ${c.map((x) => `${x.looks.join("/")} ${x.distance}`).join(", ")}`
      : p ? `closest: ${p.looks.join("/")} ${p.distance} (limit ${r.threshold})` : "";
  }
  if (tool === "self_test") return `${r.look}: recognised ${Math.round(r.recognised_rate * 100)}% · best ${Math.round((r.best_match_rate || 0) * 100)}% · error ${r.median_error ?? "–"}`;
  if (tool === "recalibrate_look") return r.captured ? "captured" : `not captured (${r.status})`;
  if (tool === "ask_caregiver") return `caregiver: ${r.answer}`;
  if (tool === "propose_sensitivity") return r.approved ? `approved, sensitivity ${r.sensitivity}` : "declined";
  if (tool === "finish") return r.summary;
  return "";
}
function onCoach(e) {
  const steps = $("coachSteps");
  if (e.event === "step_start") {
    const li = document.createElement("li");
    li.className = "running";
    li.id = `coachStep${e.step}`;
    const tool = document.createElement("span"); tool.className = "tool"; tool.textContent = TOOL_LABELS[e.tool] || e.tool;
    const tag = document.createElement("span"); tag.className = "tag"; tag.textContent = e.policy;
    const what = e.input && (e.input.look || e.input.message || e.input.instruction || (e.input.value ? `→ ${e.input.value}` : ""));
    const why = document.createElement("span"); why.className = "why"; why.textContent = [what, e.rationale].filter(Boolean).join(" — ");
    const out = document.createElement("span"); out.className = "saw";
    li.append(tool, tag, why, out);
    steps.appendChild(li);
    li.scrollIntoView({ block: "nearest" });
  } else if (e.event === "step") {
    const li = $(`coachStep${e.step}`);
    if (li) {
      li.classList.remove("running");
      const out = li.querySelector(".saw");
      out.textContent = saw(e.tool, e.result);
      if (e.is_error) out.classList.add("err");
    }
  } else if (e.event === "instruct") {
    showBanner(e.text, e.look);
    say(e.text);
  } else if (e.event === "instruct_done") {
    hideBanner();
  } else if (e.event === "caregiver" || e.event === "approval") {
    openAsk(e);
  } else if (e.event === "caregiver_timeout" || e.event === "approval_timeout") {
    $("coachAsk").hidden = true;
  } else if (e.event === "fallback") {
    const li = document.createElement("li");
    li.innerHTML = '<span class="tool">Fallback</span>';
    const why = document.createElement("span"); why.className = "why";
    why.textContent = `${e.from} unavailable (${e.reason}); the rule policy continues.`;
    li.appendChild(why); steps.appendChild(li);
  } else if (e.event === "done") {
    state.coaching = false;
    hideBanner();
    $("coachAsk").hidden = true;
    const box = $("coachResult");
    box.className = `coach-result ${e.outcome}`;
    box.textContent = `${e.outcome === "ready" ? "Ready" : e.outcome === "needs_help" ? "Needs help" : "Stopped"}: ${e.summary}`;
    box.hidden = false;
    say(e.outcome === "ready" ? "All looks are working. You can talk now." : e.summary);
  }
}
function showBanner(text, look) {
  $("coachBannerText").textContent = text;
  $("coachBanner").hidden = false;
  for (const el of document.querySelectorAll(".tile, .preview")) el.classList.remove("target");
  if (look === "straight") document.querySelector(".preview").classList.add("target");
  else if (look) {
    const t = document.querySelector(`#talk .panel:not([hidden]) .tile[data-look="${look}"]`);
    if (t) t.classList.add("target");
  }
}
function hideBanner() {
  $("coachBanner").hidden = true;
  for (const el of document.querySelectorAll(".tile, .preview")) el.classList.remove("target");
}
let askKind = null;
function openAsk(e) {
  askKind = e.event;
  const approval = e.event === "approval";
  $("coachAskKind").textContent = approval ? "Approval needed" : "For the caregiver";
  $("coachAskTitle").textContent = approval ? `${e.change}?` : e.text;
  $("coachAskReason").textContent = approval ? e.reason : "";
  $("coachAskYes").textContent = approval ? "Approve" : "Done";
  $("coachAskNo").textContent = approval ? "Decline" : "I can't";
  $("coachAsk").hidden = false;
  say(approval ? "A caregiver needs to approve a change." : e.text);
  $("coachAskYes").focus();
}
function answerAsk(yes) {
  if (!askKind) return;
  const value = askKind === "approval" ? yes : (yes ? "done" : "cannot");
  send({ type: "coach_answer", kind: askKind, value });
  askKind = null;
  $("coachAsk").hidden = true;
}

function showIntroError(text) { show("intro"); const e = $("introError"); e.textContent = text; e.hidden = false; }

// ---------- wiring ----------
$("start").addEventListener("click", async () => {
  $("introError").hidden = true;
  try {
    const cfg = await fetch("/config.json").then((r) => r.json());
    state.maxFps = cfg.maxFps; state.polly = cfg.polly;
    await loadKeyboard();
    await startCamera();
    await connect();
    startCalibration();
    requestAnimationFrame(pump);
  } catch (e) {
    showIntroError(e && e.name === "NotAllowedError"
      ? "Camera access was refused. Allow the camera, or use the tiles instead."
      : (e && e.message) || "Something went wrong starting the camera.");
  }
});
$("noCamera").addEventListener("click", async () => {
  try { await loadKeyboard(); } catch (e) { return showIntroError(e.message); }
  try { await connect(); } catch (_) { /* tiles still work, with the built-in sentence */ }
  setTab(state.tab); renderType();
  setStatus("Tap a tile to choose it.", "hint");
});
$("recalibrate").addEventListener("click", startCalibration);
$("lookTestBtn").addEventListener("click", startLookTest);
$("offerLookTest").addEventListener("click", startLookTest);
$("startTyping").addEventListener("click", startTyping);
$("lookAgain").addEventListener("click", startLookTest);
$("lookDone").addEventListener("click", () => { $("calibrate").classList.remove("reporting"); $("lookReport").hidden = true; setTab(state.tab); });
$("coachBtn").addEventListener("click", startCoach);
$("coachStop").addEventListener("click", stopCoach);
$("coachAskYes").addEventListener("click", () => answerAsk(true));
$("coachAskNo").addEventListener("click", () => answerAsk(false));
$("question").addEventListener("change", (e) => setQuestion(e.target.value));
$("question").addEventListener("keydown", (e) => { if (e.key === "Enter") e.target.blur(); });
$("mic").addEventListener("click", () => {
  const Recognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!Recognition) { $("question").focus(); setStatus("This browser cannot listen; type the question instead.", "hint"); return; }
  const rec = new Recognition();
  rec.lang = "en-IN"; rec.interimResults = false; rec.maxAlternatives = 1;
  $("mic").classList.add("listening");
  rec.onresult = (e) => { const text = e.results[0][0].transcript; $("question").value = text; setQuestion(text); };
  rec.onend = () => $("mic").classList.remove("listening");
  rec.onerror = () => $("mic").classList.remove("listening");
  rec.start();
});
$("hoodToggle").addEventListener("click", (e) => {
  const open = $("hood").hidden;
  $("hood").hidden = !open;
  e.currentTarget.setAttribute("aria-pressed", String(open));
});
for (const tile of document.querySelectorAll(".tile")) {
  tile.addEventListener("click", () => (tile.closest("#panelQuick") ? quickChoose : choose)(tile.dataset.look));
}
document.addEventListener("keydown", (e) => {  // keyboard stand-ins for the six gestures, handy for a caregiver
  if (state.mode !== "talk" || e.target.tagName === "INPUT") return;
  if (e.key === " " && state.tab === "spell") { e.preventDefault(); return spellSelect(); }
  const key = { q: "left-up", e: "right-up", a: "left", d: "right", w: "up", s: "closed" }[e.key.toLowerCase()];
  if (key) gesture(key);
});
for (const t of document.querySelectorAll(".tab[data-tab]")) t.addEventListener("click", () => setTab(t.dataset.tab));
$("sensitivity").addEventListener("input", (e) => { $("sensValue").textContent = e.target.value; });
$("sensitivity").addEventListener("change", (e) => send({ type: "settings", sensitivity: parseInt(e.target.value, 10) }));
$("showCamera").addEventListener("change", () => show(state.mode));
$("clearPhrases").addEventListener("click", () => {
  try { storage()?.removeItem("vakyam.phrases"); } catch (_) { /* private mode */ }
  state.phrases = new VakyamFeatures.PhraseBook(storage());
  state.quick.myPhrases = () => state.phrases.top(5);
  $("phraseCount").textContent = "0 saved";
});
show("intro");
