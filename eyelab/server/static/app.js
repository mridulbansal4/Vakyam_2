"use strict";

// Vakyam Eye Lab client: webcam frames to the server, one in flight at a time; results drive the page.

const QUESTIONS = [
  { q: "What would you like right now?", words: { "left-up": "water", "right-up": "tea", left: "rest", right: "music" } },
  { q: "How are you feeling today?", words: { "left-up": "good", "right-up": "tired", left: "pain", right: "cold" } },
  { q: "Who should we call?", words: { "left-up": "family", "right-up": "nurse", left: "doctor", right: "friend" } },
];
const FRAME_WIDTH = 480;       // frames are scaled to this width before JPEG encoding
const JPEG_QUALITY = 0.75;
const MAX_WORDS = 3;

const $ = (id) => document.getElementById(id);
const screens = ["intro", "calibrate", "talk"];
const state = {
  ws: null, stream: null, inFlight: false, lastSent: 0, maxFps: 12, polly: false,
  mode: "intro", questionIndex: 0, words: [], speaking: false, frames: [], camera: false,
};

function show(name) {
  state.mode = name;
  for (const s of screens) $(s).hidden = s !== name;
  document.querySelector(".preview").hidden = !state.camera;
  $("recalibrate").hidden = !(state.camera && name === "talk");
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
    state.inFlight = false; requestAnimationFrame(pump); return;
  }
  if (msg.type === "calibration") return onCalibration(msg);
  if (msg.type === "sentence") return onSentence(msg);
  if (msg.type !== "frame") return;
  state.inFlight = false;
  requestAnimationFrame(pump);
  if (msg.skipped) return;
  state.frames.push(performance.now());
  if (state.frames.length > 20) state.frames.shift();
  drawFace(msg.face);
  if (msg.calibration) onCalibration(msg.calibration);
  if (state.mode === "talk") onTalkFrame(msg);
  updateHood(msg);
}

// ---------- calibration ----------
function onCalibration(c) {
  if (c.finished) {
    $("target").classList.add("captured");
    beep(1046, 180);
    say("Calibration finished. Now answer with your eyes.");
    setTimeout(() => { show("talk"); renderQuestion(); }, 700);
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
function startCalibration() {
  show("calibrate");
  send({ type: "calibrate" });
}

// ---------- talking ----------
function renderQuestion() {
  const q = QUESTIONS[state.questionIndex];
  $("question").textContent = q.q;
  for (const tile of document.querySelectorAll(".tile")) {
    const look = tile.dataset.look;
    if (q.words[look]) tile.querySelector(".word").textContent = q.words[look];
  }
  state.words = [];
  renderChips();
}
function renderChips() {
  const chips = $("chips");
  chips.innerHTML = "";
  if (!state.words.length) {
    chips.innerHTML = '<span class="chip empty">Your words appear here</span>';
    return;
  }
  for (const w of state.words) {
    const s = document.createElement("span");
    s.className = "chip"; s.textContent = w; chips.appendChild(s);
  }
}
function choose(look) {
  if (state.speaking) return;
  const tile = document.querySelector(`.tile[data-look="${look}"]`);
  if (tile) { tile.classList.remove("fired"); void tile.offsetWidth; tile.classList.add("fired"); }
  if (look === "up") return speak();
  if (look === "closed") { state.words.pop(); beep(520); return renderChips(); }
  const word = QUESTIONS[state.questionIndex].words[look];
  if (!word || state.words.length >= MAX_WORDS || state.words.includes(word)) return;
  state.words.push(word);
  beep();
  renderChips();
}
function speak() {
  if (!state.words.length) { setStatus("Choose a word first, then look up to speak.", "hint"); return; }
  state.speaking = true;
  $("sentenceText").textContent = "…";
  $("sentenceSource").textContent = "Writing your sentence";
  $("sentence").hidden = false;
  if (state.ws) send({ type: "sentence", keywords: state.words, question: QUESTIONS[state.questionIndex].q });
  else onSentence({ text: offlineSentence(state.words), source: "fallback", ms: 0 });
}
function onSentence(s) {
  $("sentenceText").textContent = s.text;
  $("sentenceSource").textContent = s.source === "claude"
    ? `Written by Claude on Amazon Bedrock in ${(s.ms / 1000).toFixed(1)} s`
    : "Built-in sentence (the language model was not available)";
  if (s.audio) new Audio(s.audio).play().catch(() => say(s.text));
  else say(s.text);
  $("sentenceClose").focus();
}
function closeSentence() {
  $("sentence").hidden = true;
  state.speaking = false;
  state.questionIndex = (state.questionIndex + 1) % QUESTIONS.length;
  renderQuestion();
  requestAnimationFrame(pump);
}
function offlineSentence(words) { return `I want ${words.join(" and ")}, please.`; }

function onTalkFrame(msg) {
  const holding = msg.holding || {};
  for (const tile of document.querySelectorAll(".tile")) {
    tile.style.setProperty("--fill", tile.dataset.look === holding.look ? holding.progress : 0);
  }
  if (!msg.face) setStatus("Face not found. Look at the camera.", "warn");
  else if (msg.issue_text) setStatus(msg.issue_text, "warn");
  else setStatus("", "hint");
  if (msg.selected) choose(msg.selected);
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
function showIntroError(text) { show("intro"); const e = $("introError"); e.textContent = text; e.hidden = false; }

// ---------- wiring ----------
$("start").addEventListener("click", async () => {
  $("introError").hidden = true;
  try {
    const cfg = await fetch("/config.json").then((r) => r.json());
    state.maxFps = cfg.maxFps; state.polly = cfg.polly;
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
  try { await connect(); } catch (_) { /* tiles still work, with the built-in sentence */ }
  show("talk"); renderQuestion();
  setStatus("Tap a tile to choose it.", "hint");
});
$("recalibrate").addEventListener("click", startCalibration);
$("nextQuestion").addEventListener("click", () => { state.questionIndex = (state.questionIndex + 1) % QUESTIONS.length; renderQuestion(); });
$("sentenceClose").addEventListener("click", closeSentence);
$("hoodToggle").addEventListener("click", (e) => {
  const open = $("hood").hidden;
  $("hood").hidden = !open;
  e.currentTarget.setAttribute("aria-pressed", String(open));
});
for (const tile of document.querySelectorAll(".tile")) tile.addEventListener("click", () => choose(tile.dataset.look));
show("intro");
