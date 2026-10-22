// Aircraft Vision web app: same flow as the phone apps. Live camera -> freeze a frame (or pick a
// photo) -> resizable box -> the model identifies what's inside the box, all in the browser.
import { drawBrackets, drawPlanform } from "./draw.js";
import { BODY, enginesText, specsFor } from "./specs.js";

const $ = (id) => document.getElementById(id);
const MAX_SIDE = 1600;
const LOW = 0.4;

const state = {
  labels: [],       // [{name, family, manufacturer}]
  imgSize: 256,
  familySizes: {},
  image: null,      // canvas holding the photo being identified
  crop: null,       // {left, top, right, bottom} in 0..1
  predictions: [],
  busy: false,
  ms: null,
  error: null,
};

// ------------------------------------------------------------------ model

const worker = new Worker(new URL("./worker.js", import.meta.url), { type: "module" });
let nextId = 0;
const pending = new Map();
worker.onmessage = ({ data }) => {
  const p = pending.get(data.id);
  pending.delete(data.id);
  if (p) data.ok ? p.resolve(data) : p.reject(new Error(data.error));
};
const call = (msg, transfer = []) => new Promise((resolve, reject) => {
  const id = nextId++;
  pending.set(id, { resolve, reject });
  worker.postMessage({ ...msg, id }, transfer);
});

const modelReady = (async () => {
  const labels = await (await fetch("model/labels.json")).json();
  state.imgSize = labels.img_size;
  state.labels = labels.classes;
  for (const c of state.labels) state.familySizes[c.family] = (state.familySizes[c.family] || 0) + 1;
  const model = await (await fetch("model/aircraft.onnx")).arrayBuffer();
  await call({ type: "load", model }, [model]);
})();
modelReady.catch((e) => showCameraError(`Couldn't load the model: ${e.message}`));

/** The region inside the box, resized to S x S (halving first, so big photos don't alias), as planar RGB floats. */
function modelInput(src, box, S) {
  let l = box.left * src.width, t = box.top * src.height, r = box.right * src.width, b = box.bottom * src.height;
  // Keep the aspect ratio within what training's random crops covered (about 3:5 to 5:3).
  const max = 1.66;
  if ((r - l) / (b - t) > max) {
    const g = ((r - l) / max - (b - t)) / 2; t = Math.max(0, t - g); b = Math.min(src.height, b + g);
  } else if ((b - t) / (r - l) > max) {
    const g = ((b - t) / max - (r - l)) / 2; l = Math.max(0, l - g); r = Math.min(src.width, r + g);
  }
  let cur = src, sx = l, sy = t, sw = r - l, sh = b - t;
  while (sw >= S * 2 && sh >= S * 2) {
    const half = canvas(Math.floor(sw / 2), Math.floor(sh / 2));
    half.getContext("2d").drawImage(cur, sx, sy, sw, sh, 0, 0, half.width, half.height);
    cur = half; sx = 0; sy = 0; sw = half.width; sh = half.height;
  }
  const out = canvas(S, S);
  const ctx = out.getContext("2d", { willReadFrequently: true });
  ctx.imageSmoothingQuality = "high";
  ctx.drawImage(cur, sx, sy, sw, sh, 0, 0, S, S);
  const px = ctx.getImageData(0, 0, S, S).data;
  const plane = S * S;
  const input = new Float32Array(3 * plane);
  for (let i = 0; i < plane; i++) {
    input[i] = px[i * 4] / 255;
    input[plane + i] = px[i * 4 + 1] / 255;
    input[2 * plane + i] = px[i * 4 + 2] / 255;
  }
  return input;
}

let runToken = 0;
async function identify() {
  const token = ++runToken;
  Object.assign(state, { busy: true, error: null, predictions: [], ms: null });
  renderSheet();
  startSparkles();
  try {
    await modelReady;
    const start = performance.now();
    const input = modelInput(state.image, state.crop, state.imgSize);
    const { probs } = await call({ type: "run", input, size: state.imgSize }, [input.buffer]);
    if (token !== runToken) return;
    const familyTotals = {};
    state.labels.forEach((l, i) => { familyTotals[l.family] = (familyTotals[l.family] || 0) + probs[i]; });
    state.predictions = [...probs.keys()].sort((a, b) => probs[b] - probs[a]).slice(0, 5).map((i) => ({
      label: state.labels[i],
      confidence: probs[i],
      familyConfidence: familyTotals[state.labels[i].family],
      familySize: state.familySizes[state.labels[i].family],
    }));
    state.ms = Math.round(performance.now() - start);
  } catch (e) {
    if (token !== runToken) return;
    state.error = e.message;
  }
  state.busy = false;
  renderSheet();
  drawPhoto();
}

// ------------------------------------------------------------------ camera

const video = $("video");
let stream = null;

async function startCamera() {
  try {
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } },
      audio: false,
    });
    video.srcObject = stream;
    $("no-camera").hidden = true;
    $("shutter").disabled = false;
    $("hint").textContent = "Point at an aircraft and tap search";
    const track = stream.getVideoTracks()[0];
    const caps = track.getCapabilities ? track.getCapabilities() : {};
    $("torch").hidden = !caps.torch;
    $("torch-spacer").hidden = !!caps.torch;
  } catch (e) {
    $("no-camera").hidden = false;
    $("shutter").disabled = true;
    $("hint").textContent = "Or pick a photo from your library";
  }
}

function stopCamera() {
  stream?.getTracks().forEach((t) => t.stop());
  stream = null;
}

$("allow").onclick = startCamera;
$("torch").onclick = async () => {
  const track = stream?.getVideoTracks()[0];
  if (!track) return;
  const on = !$("torch").classList.contains("on");
  try {
    await track.applyConstraints({ advanced: [{ torch: on }] });
    $("torch").classList.toggle("on", on);
  } catch { /* torch not available */ }
};

function drawViewfinder() {
  const c = $("viewfinder");
  const dpr = window.devicePixelRatio || 1;
  c.width = c.clientWidth * dpr;
  c.height = c.clientHeight * dpr;
  const ctx = c.getContext("2d");
  ctx.scale(dpr, dpr);
  const side = Math.min(c.clientWidth, c.clientHeight) * 0.82;
  drawBrackets(ctx, (c.clientWidth - side) / 2, (c.clientHeight - side) / 2, side, side);
}

/** Freezes what's on screen: the part of the video frame the viewfinder shows (object-fit: cover). */
$("shutter").onclick = () => {
  if (!video.videoWidth) return;
  const vw = video.videoWidth, vh = video.videoHeight;
  const sw = video.clientWidth, sh = video.clientHeight;
  const scale = Math.max(sw / vw, sh / vh);
  const cw = sw / scale, ch = sh / scale;
  const frame = canvas(Math.round(cw), Math.round(ch));
  frame.getContext("2d").drawImage(video, (vw - cw) / 2, (vh - ch) / 2, cw, ch, 0, 0, frame.width, frame.height);
  showResult(frame);
};

$("file").onchange = async (event) => {
  const file = event.target.files[0];
  event.target.value = "";
  if (!file) return;
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode(); // browsers apply the photo's EXIF rotation when drawing an <img>
    const scale = Math.min(1, MAX_SIDE / Math.max(img.naturalWidth, img.naturalHeight));
    const c = canvas(Math.round(img.naturalWidth * scale), Math.round(img.naturalHeight * scale));
    c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
    showResult(c);
  } catch {
    showCameraError("Couldn't read that photo.");
  } finally {
    URL.revokeObjectURL(url);
  }
};

function showCameraError(message) {
  $("cam-error").textContent = message;
  $("cam-error").hidden = false;
}

// ------------------------------------------------------------------ result screen

const photo = $("photo");
const sheet = $("sheet");

function centreSquare(w, h) {
  const side = Math.min(w, h);
  const x = (w - side) / 2 / w, y = (h - side) / 2 / h;
  return { left: x, top: y, right: 1 - x, bottom: 1 - y };
}

function showResult(image) {
  state.image = image;
  state.crop = centreSquare(image.width, image.height);
  stopCamera();
  $("camera").hidden = true;
  $("result").hidden = false;
  sheet.classList.remove("full");
  history.pushState({ result: true }, ""); // so the phone's back gesture returns to the camera
  identify();
  drawPhoto();
}

function backToCamera() {
  runToken++;
  Object.assign(state, { image: null, crop: null, predictions: [], busy: false, error: null });
  $("result").hidden = true;
  $("camera").hidden = false;
  startCamera();
  requestAnimationFrame(drawViewfinder);
}
$("close").onclick = () => history.back();
window.addEventListener("popstate", () => { if (!$("result").hidden) backToCamera(); });

/** Where the photo sits: fitted between the close button and the sheet, with room to grab the corners. */
function photoArea() {
  const W = photo.clientWidth, H = photo.clientHeight;
  const top = 64, bottom = H * 0.42 + 8, pad = 20; // the sheet's resting height is 42% (--peek)
  const availW = W - 2 * pad, availH = H - top - bottom;
  const w = state.image.width, h = state.image.height;
  const s = Math.min(availW / w, availH / h);
  return { x: (W - w * s) / 2, y: top + (availH - h * s) / 2, w: w * s, h: h * s };
}

const boxPx = (b, a) => ({ x: a.x + b.left * a.w, y: a.y + b.top * a.h, w: (b.right - b.left) * a.w, h: (b.bottom - b.top) * a.h });

const dots = (() => {
  let seed = 42;
  const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
  return Array.from({ length: 40 }, () => [rnd(), rnd(), rnd()]);
})();

let dragBox = null;
function drawPhoto(time = performance.now()) {
  if (!state.image) return;
  const dpr = window.devicePixelRatio || 1;
  photo.width = photo.clientWidth * dpr;
  photo.height = photo.clientHeight * dpr;
  const ctx = photo.getContext("2d");
  ctx.scale(dpr, dpr);
  const a = photoArea();
  ctx.drawImage(state.image, a.x, a.y, a.w, a.h);
  const b = boxPx(dragBox || state.crop, a);
  ctx.fillStyle = "rgba(0,0,0,.5)";
  ctx.fillRect(a.x, a.y, a.w, b.y - a.y);
  ctx.fillRect(a.x, b.y + b.h, a.w, a.y + a.h - b.y - b.h);
  ctx.fillRect(a.x, b.y, b.x - a.x, b.h);
  ctx.fillRect(b.x + b.w, b.y, a.x + a.w - b.x - b.w, b.h);
  ctx.strokeStyle = "rgba(255,255,255,.5)";
  ctx.lineWidth = 1;
  ctx.strokeRect(b.x, b.y, b.w, b.h);
  if (state.busy) {
    const t = (time / 2400) % 1;
    for (const [x, y, phase] of dots) {
      const v = ((Math.sin((t + phase) * 2 * Math.PI) + 1) / 2) ** 2;
      const cx = b.x + x * b.w, cy = b.y + y * b.h;
      ctx.fillStyle = `rgba(63,169,245,${0.35 * v})`;
      ctx.beginPath(); ctx.arc(cx, cy, 3 + 4 * v, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = `rgba(255,255,255,${v})`;
      ctx.beginPath(); ctx.arc(cx, cy, 1 + 1.5 * v, 0, Math.PI * 2); ctx.fill();
    }
  }
  drawBrackets(ctx, b.x, b.y, b.w, b.h);
}

let sparkling = false;
function startSparkles() {
  if (sparkling) return;
  sparkling = true;
  const frame = (t) => {
    drawPhoto(t);
    if (state.busy) requestAnimationFrame(frame);
    else sparkling = false;
  };
  requestAnimationFrame(frame);
}

// Drag a corner to resize the box, drag inside to move it; letting go identifies the new area.
let drag = null;
photo.addEventListener("pointerdown", (e) => {
  if (!state.image) return;
  const a = photoArea();
  const b = boxPx(state.crop, a);
  const p = { x: e.offsetX, y: e.offsetY };
  const corners = { tl: [b.x, b.y], tr: [b.x + b.w, b.y], bl: [b.x, b.y + b.h], br: [b.x + b.w, b.y + b.h] };
  let handle = null, best = 40;
  for (const [k, [cx, cy]] of Object.entries(corners)) {
    const d = Math.hypot(cx - p.x, cy - p.y);
    if (d <= best) { best = d; handle = k; }
  }
  if (!handle && p.x > b.x && p.x < b.x + b.w && p.y > b.y && p.y < b.y + b.h) handle = "move";
  if (!handle) return;
  drag = { handle, start: p, box: { ...state.crop }, area: a };
  photo.setPointerCapture(e.pointerId);
});
photo.addEventListener("pointermove", (e) => {
  if (!drag) return;
  const { handle, start, box, area } = drag;
  const dx = (e.offsetX - start.x) / area.w, dy = (e.offsetY - start.y) / area.h;
  const minW = 56 / area.w, minH = 56 / area.h;
  const clamp = (v, lo, hi) => Math.max(lo, Math.min(v, hi));
  const b = { ...box };
  if (handle === "move") {
    const w = box.right - box.left, h = box.bottom - box.top;
    b.left = clamp(box.left + dx, 0, 1 - w); b.top = clamp(box.top + dy, 0, 1 - h);
    b.right = b.left + w; b.bottom = b.top + h;
  } else {
    if (handle[1] === "l") b.left = clamp(box.left + dx, 0, box.right - minW);
    if (handle[1] === "r") b.right = clamp(box.right + dx, box.left + minW, 1);
    if (handle[0] === "t") b.top = clamp(box.top + dy, 0, box.bottom - minH);
    if (handle[0] === "b") b.bottom = clamp(box.bottom + dy, box.top + minH, 1);
  }
  dragBox = b;
  drawPhoto();
});
const endDrag = () => {
  if (!drag) return;
  drag = null;
  if (dragBox) {
    state.crop = dragBox;
    dragBox = null;
    identify();
  }
};
photo.addEventListener("pointerup", endDrag);
photo.addEventListener("pointercancel", endDrag);

// ------------------------------------------------------------------ sheet

// Drag the grabber (or tap it) to move between half and full height.
{
  const grab = sheet.querySelector(".grabber");
  let startY = null, startFull = false, moved = false;
  grab.addEventListener("pointerdown", (e) => {
    startY = e.clientY; startFull = sheet.classList.contains("full"); moved = false;
    sheet.classList.add("dragging");
    grab.setPointerCapture(e.pointerId);
  });
  grab.addEventListener("pointermove", (e) => {
    if (startY === null) return;
    const dy = e.clientY - startY;
    if (Math.abs(dy) > 4) moved = true;
    const base = startFull ? 0 : sheet.clientHeight - window.innerHeight * 0.42;
    sheet.style.transform = `translateY(${Math.max(0, base + dy)}px)`;
  });
  const release = (e) => {
    if (startY === null) return;
    const dy = e.clientY - startY;
    sheet.classList.remove("dragging");
    sheet.style.transform = "";
    if (!moved) sheet.classList.toggle("full");
    else sheet.classList.toggle("full", startFull ? dy < 80 : dy < -60);
    startY = null;
  };
  grab.addEventListener("pointerup", release);
  grab.addEventListener("pointercancel", release);
}

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
const pct = (x) => `${Math.round(x * 100)}%`;
const confColor = (c) => (c >= 0.7 ? "var(--high)" : c >= 0.4 ? "var(--mid)" : "var(--low)");
const icon = (name) => `<svg><use href="#i-${name}"/></svg>`;

function familyTitle(label) {
  const family = label.family || label.name;
  const withMaker = label.manufacturer && !family.startsWith(label.manufacturer) ? `${label.manufacturer} ${family}` : family;
  return `${withMaker} family`;
}

function renderSheet() {
  const body = $("sheet-body");
  let html = "";
  if (state.image && !state.busy) {
    const c = centreSquare(state.image.width, state.image.height);
    const moved = ["left", "top", "right", "bottom"].some((k) => Math.abs(state.crop[k] - c[k]) > 1e-6);
    html += `<div class="hint-row"><span>Drag the box corners to fit the aircraft</span>${moved ? '<button class="text" id="reset">Reset box</button>' : ""}</div>`;
  }
  if (state.error) {
    html += `<div class="err-card">${esc(state.error)}</div>`;
  } else if (state.busy || !state.predictions.length) {
    html += `<div class="searching"><div class="head"><span class="spinner"></span>Looking closely…</div>
      <div class="sk" style="width:200px;height:34px"></div><div class="sk" style="width:130px;height:18px"></div>
      <div class="sk" style="height:44px"></div><div class="sk" style="height:44px"></div><div class="sk" style="height:44px"></div></div>`;
  } else {
    html += resultHTML();
  }
  if (!state.busy) html += `<button class="again" id="again">${icon("search")}Search another aircraft</button>`;
  body.innerHTML = html;

  $("reset")?.addEventListener("click", () => { state.crop = centreSquare(state.image.width, state.image.height); identify(); drawPhoto(); });
  $("again")?.addEventListener("click", () => history.back());
  const plan = body.querySelector(".plan canvas");
  if (plan) {
    const css = getComputedStyle(document.documentElement);
    const v = (n) => css.getPropertyValue(n).trim();
    drawPlanform(plan, specsFor(state.predictions[0].label.name), {
      ink: v("--text"), skin: v("--sheet"), premium: v("--premium"), economy: v("--accent"),
    });
  }
  requestAnimationFrame(() => body.querySelectorAll(".bar i").forEach((i) => { i.style.width = i.dataset.w; }));
}

function resultHTML() {
  const preds = state.predictions;
  const best = preds[0];
  const specs = specsFor(best.label.name);
  // Close variants (737-800 vs -900) are the hardest call. When the model is torn between
  // variants but sure of the family, say the family rather than guess a variant.
  const familyMode = best.familySize > 1 && best.confidence < 0.6 && best.familyConfidence >= 0.75;
  const score = familyMode ? best.familyConfidence : best.confidence;
  const likely = preds.filter((p) => p.label.family === best.label.family).slice(0, 2);
  const sub = familyMode
    ? `Variant unclear · most likely ${likely.map((p) => esc(p.label.name)).join(" or ")}`
    : best.label.family && best.label.family !== best.label.name ? esc(best.label.family) : "";
  const passenger = specs.seatGroups.length && !["fighter", "light", "transport"].includes(specs.kind);
  const specRow = (k, v) => `<div class="spec"><span>${k}</span><b>${esc(v)}</b></div>`;

  return `
    <div class="title-row">
      <div>
        <div class="caps">${esc(best.label.manufacturer)}</div>
        <div class="type-name">${esc(familyMode ? familyTitle(best.label) : best.label.name)}</div>
        ${sub ? `<div class="sub">${sub}</div>` : ""}
      </div>
      <div class="score"><b style="color:${confColor(score)}">${pct(score)}</b><span class="caps">Match</span></div>
    </div>
    ${score < LOW ? `<div class="low">${icon("info")}Low match. Fit the box tightly around one aircraft.</div>` : ""}
    <div class="plan"><canvas></canvas></div>
    <div class="legend">
      ${passenger ? '<span><i style="background:var(--premium)"></i>Premium</span><span><i style="background:var(--accent)"></i>Economy</span>' : ""}
      <em>Typical layout · illustrative</em>
    </div>
    <div class="caps section">Specifications</div>
    ${specRow("Manufacturer", best.label.manufacturer || "—")}
    ${specRow("Family", best.label.family || best.label.name)}
    ${familyMode ? specRow("Variant", likely.map((p) => `${p.label.name} ${pct(p.confidence)}`).join(" / ")) : ""}
    ${specRow("Body", BODY[specs.kind])}
    ${specRow("Seating", specs.seating)}
    ${specRow("Engines", enginesText(specs))}
    ${preds.length > 1 ? `<div class="caps section">Could also be</div>` + preds.slice(1).map((p) => `
      <div class="runner">
        <div class="top"><div><b>${esc(p.label.name)}</b><small>${esc([p.label.manufacturer, BODY[specsFor(p.label.name).kind]].filter(Boolean).join(" · "))}</small></div>
        <span class="pct">${pct(p.confidence)}</span></div>
        <div class="bar"><i style="width:0" data-w="${Math.max(1, p.confidence * 100)}%"></i></div>
      </div>`).join("") : ""}
    ${state.ms != null ? `<p class="footnote">Identified on your phone in ${state.ms} ms · no internet used</p>` : ""}`;
}

// ------------------------------------------------------------------ supported types

$("types-btn").onclick = async () => {
  await modelReady.catch(() => {});
  $("types-title").textContent = `${state.labels.length} aircraft types`;
  renderTypes("");
  $("types").showModal();
};
$("types-close").onclick = () => $("types").close();
$("types-search").oninput = (e) => renderTypes(e.target.value);

function renderTypes(query) {
  const q = query.trim().toLowerCase();
  const groups = {};
  for (const t of state.labels) {
    if (q && ![t.name, t.family, t.manufacturer].some((s) => s.toLowerCase().includes(q))) continue;
    (groups[t.manufacturer || "Other"] ||= []).push(t);
  }
  $("types-list").innerHTML = Object.keys(groups).sort((a, b) => a.localeCompare(b)).map((m) =>
    `<h3 class="caps">${esc(m)}</h3>` + groups[m].sort((a, b) => a.name.localeCompare(b.name)).map((t) =>
      `<p><span>${esc(t.name)}</span>${t.family && t.family !== t.name ? `<small>${esc(t.family)}</small>` : ""}</p>`).join(""),
  ).join("");
}

// ------------------------------------------------------------------ start

function canvas(w, h) {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  return c;
}

window.addEventListener("resize", () => { drawViewfinder(); drawPhoto(); });
drawViewfinder();
startCamera();
if ("serviceWorker" in navigator && location.protocol === "https:") navigator.serviceWorker.register("sw.js");
