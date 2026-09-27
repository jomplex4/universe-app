/* =============================================================
   UNIVERSE by DigitalMinds  ::  engine v3
   - Same code for web (Chrome) and Android APK (Capacitor).
   - The strip only receives two commands: POWER and COLOR.
   - Brightness is only moved by the user. Scenes and reactive
     modes change COLOR, never brightness.
   - One render loop (20 fps) eases the output toward a target
     color, so every transition is gradual unless a scene is
     meant to be a hard cut (Jump, Blink, Strobe).
============================================================= */
import { BleClient } from '@capacitor-community/bluetooth-le';
import { Capacitor, registerPlugin } from '@capacitor/core';

const NATIVE = Capacitor.isNativePlatform();
const MusicLibrary = registerPlugin('MusicLibrary');
const $ = (id) => document.getElementById(id);

/* ---------------- state ---------------- */
const S = Object.assign(
  { r: 225, g: 6, b: 0, bright: 100, speed: 50, sens: 70, power: true, lastId: null, mstyle: 'disco', pulse: false },
  load('u_state') || {}
);
function save() { localStorage.setItem('u_state', JSON.stringify(S)); }
function load(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } }

/* ---------------- BLE (web + native via one API) ---------------- */
const SERVICE = '0000fff0-0000-1000-8000-00805f9b34fb';
const BLE = {
  id: null, svc: null, ch: null, noResp: true, connected: false, ready: false,
  async init() {
    if (this.ready) return;
    await BleClient.initialize({ androidNeverForLocation: true });
    this.ready = true;
  },
  async open(deviceId) {
    await BleClient.connect(deviceId, () => onLost(), { timeout: 8000 });
    const services = await BleClient.getServices(deviceId);
    const svc = services.find((s) => s.uuid.toLowerCase().startsWith('0000fff0')) || services[0];
    const chars = svc ? svc.characteristics : [];
    const c = chars.find((x) => x.properties.writeWithoutResponse) || chars.find((x) => x.properties.write);
    if (!c) throw new Error('This strip did not expose a writable channel');
    this.id = deviceId; this.svc = svc.uuid; this.ch = c.uuid;
    this.noResp = !!c.properties.writeWithoutResponse;
    this.connected = true;
    S.lastId = deviceId; save();
  },
  async connect() {
    await this.init();
    if (NATIVE && S.lastId) {
      try { await this.open(S.lastId); return; } catch (e) { /* fall back to picker */ }
    }
    const dev = await BleClient.requestDevice({ namePrefix: 'ELK', optionalServices: [SERVICE] });
    await this.open(dev.deviceId);
  },
  async write(bytes) {
    if (!this.connected) return;
    const dv = new DataView(new Uint8Array(bytes).buffer);
    try {
      if (this.noResp) await BleClient.writeWithoutResponse(this.id, this.svc, this.ch, dv);
      else await BleClient.write(this.id, this.svc, this.ch, dv);
    } catch (e) {
      try { await BleClient.write(this.id, this.svc, this.ch, dv); } catch (e2) { /* ignore */ }
    }
  },
  async disconnect() {
    const id = this.id; this.connected = false;
    try { if (id) await BleClient.disconnect(id); } catch (e) { /* ignore */ }
  }
};

/* ---------------- delivery lane: power is queued, color keeps only the latest ---------------- */
const CMD = {
  power: (on) => [0x7e, 0, 4, on ? 1 : 0, 0, 0, 0, 0, 0xef],
  color: (r, g, b) => [0x7e, 0, 5, 3, r & 255, g & 255, b & 255, 0, 0xef]
};
const queue = []; let busy = false; let latestColor = null;
function pump() {
  if (busy || !BLE.connected) return;
  let bytes;
  if (queue.length) bytes = queue.shift();
  else if (latestColor) { bytes = latestColor; latestColor = null; }
  else return;
  busy = true;
  BLE.write(bytes).finally(() => setTimeout(() => { busy = false; pump(); }, 45));
}
function sendPower(on) { queue.push(CMD.power(on)); pump(); }
function sendColor(rgb) { latestColor = CMD.color(rgb[0], rgb[1], rgb[2]); pump(); }

/* ---------------- color math ---------------- */
const STOPS = [[255,0,0],[255,0,212],[106,0,255],[0,145,255],[0,255,191],[123,255,0],[255,212,0],[255,0,0]];
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
function wheelAt(f) { // same palette and order as the drawn wheel
  f = ((f % 1) + 1) % 1; const n = STOPS.length - 1; const p = f * n; const i = Math.floor(p);
  return mix(STOPS[i], STOPS[Math.min(i + 1, n)], p - i);
}
// CSS radial-gradient(circle) reaches the box corner: 68% of R*sqrt(2)
const WHITE_R = 0.68 * Math.SQRT2;
const hex = (c) => '#' + c.map((x) => Math.round(x).toString(16).padStart(2, '0')).join('').toUpperCase();
const hexRgb = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16));
function rgbHsv(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn; let h = 0;
  if (d) h = mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [(h * 60 + 360) % 360, mx ? d / mx : 0, mx];
}
function hsvRgb(h, s, v) {
  h = ((h % 360) + 360) % 360 / 60; const i = Math.floor(h), f = h - i;
  const p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
  const m = [[v,t,p],[q,v,p],[p,v,t],[p,q,v],[t,p,v],[v,p,q]][i];
  return m.map((x) => x * 255);
}

/* ---------------- render loop ---------------- */
const out = [S.r, S.g, S.b];
let mode = 'solid';          // solid | scene | preset | music | voice
let gen = () => [S.r, S.g, S.b];
let ease = 0.5;              // 1 = hard cut, lower = smoother
let t0 = performance.now();
let lastSent = '';
let pulseK = 1;               // only the optional Pulse switch may move intensity
let stripOn = null;          // what the strip currently is (power)
const rate = () => 0.15 + (S.speed / 100) * 1.85;

function tick() {
  const t = (performance.now() - t0) / 1000;
  const target = gen(t);
  for (let i = 0; i < 3; i++) out[i] += (target[i] - out[i]) * ease;
  const wantOn = S.power && S.bright > 0;
  if (BLE.connected && stripOn !== wantOn) { sendPower(wantOn); stripOn = wantOn; lastSent = ''; }
  if (!wantOn || !BLE.connected) return;
  // Keep intensity locked to the target: blending two hues must never dim the strip.
  const tm = mode === 'solid' ? Math.max(target[0], target[1], target[2]) : 255, om = Math.max(out[0], out[1], out[2]);
  const k = (S.bright / 100) * (om > 1 && tm > 0 ? tm / om : 1) * (mode === 'music' ? pulseK : 1);
  const c = out.map((x) => clamp(Math.round(x * k), 0, 255));
  const key = c.join(',');
  if (key !== lastSent) { lastSent = key; sendColor(c); }
}
setInterval(tick, 50);

function setMode(m, g, e) { mode = m; gen = g; ease = e; pulseK = 1; t0 = performance.now(); if (m !== 'voice') stopMic(); }
function solid(fromUser) {
  const base = [S.r, S.g, S.b];
  setMode('solid', () => base, fromUser ? 0.55 : 1);
  clearSel();
}

/* ---------------- scenes: only colors change ---------------- */
const SCENES = [
  { nm: 'Breathe', sb: 'soft color drift', pv: 'linear-gradient(135deg,#e10600,#ff2ad4)', e: 0.25,
    g: () => { const [h] = rgbHsv(S.r, S.g, S.b); return (t) => hsvRgb(h + Math.sin(t * rate() * 0.9) * 45, 1, 1); } },
  { nm: 'Rainbow', sb: 'full wheel, gradual', pv: 'conic-gradient(#f00,#ff00d4,#6a00ff,#0091ff,#00ffbf,#7bff00,#ffd400,#f00)', e: 0.3,
    g: () => (t) => wheelAt(t * rate() * 0.035) },
  { nm: 'Ocean', sb: 'blues and teals', pv: 'linear-gradient(135deg,#0024ff,#00ffbf)', e: 0.25,
    g: () => (t) => mix([0, 60, 255], [0, 230, 190], (Math.sin(t * rate() * 0.7) + 1) / 2) },
  { nm: 'Jump', sb: 'hard color cuts', pv: 'linear-gradient(90deg,#f00 33%,#00ffbf 33% 66%,#0091ff 66%)', e: 1,
    g: () => (t) => STOPS[Math.floor(t * rate() * 1.2) % 7] },
  { nm: 'Heartbeat', sb: 'red to pink pulse', pv: 'linear-gradient(135deg,#e10600,#ff5c8a)', e: 0.45,
    g: () => (t) => { const p = (t * rate() * 0.8) % 1; const hit = (p < 0.1) || (p > 0.2 && p < 0.3); return hit ? [255, 60, 150] : [225, 6, 0]; } },
  { nm: 'Candle', sb: 'warm flicker', pv: 'radial-gradient(circle at 50% 60%,#ffb020,#ff4a00)', e: 0.2,
    g: () => { let tgt = [255, 120, 0], nx = 0; return (t) => { if (t > nx) { nx = t + (0.08 + Math.random() * 0.25) / rate(); tgt = hsvRgb(12 + Math.random() * 26, 1, 1); } return tgt; }; } },
  { nm: 'Sunrise', sb: 'red, amber, gold', pv: 'linear-gradient(180deg,#ffd400,#ff6a00,#b00500)', e: 0.25,
    g: () => { const P = [[255,12,0],[255,70,0],[255,150,10],[255,205,60],[255,150,10],[255,70,0]];
      return (t) => { const f = (t * rate() * 0.06) % 1 * P.length; const i = Math.floor(f); return mix(P[i], P[(i + 1) % P.length], f - i); }; } },
  { nm: 'Blink', sb: 'color and its opposite', pv: 'linear-gradient(90deg,#e10600 50%,#00d4c8 50%)', e: 1,
    g: () => { const [h] = rgbHsv(S.r, S.g, S.b); const a = hsvRgb(h, 1, 1), b = hsvRgb(h + 180, 1, 1);
      return (t) => (Math.floor(t * rate() * 1.4) % 2 ? b : a); } },
  { nm: 'Strobe', sb: 'fast color flashes', pv: 'linear-gradient(90deg,#ff00d4,#0091ff,#7bff00)', e: 1,
    g: () => (t) => STOPS[Math.floor(t * rate() * 5) % 7] },
  { nm: 'Storm', sb: 'deep blue, cyan flashes', pv: 'linear-gradient(180deg,#1e6bff,#061a4a)', e: 0.35,
    g: () => { let until = 0, nx = 0; return (t) => { if (t > nx) { nx = t + (0.4 + Math.random() * 1.6) / rate(); if (Math.random() < 0.6) until = t + 0.12; }
      return t < until ? [150, 235, 255] : [10, 45, 255]; }; } }
];

/* ---------------- audio (music playback + voice mic) ---------------- */
let actx = null, analyser = null, buf = null, mediaSrc = null, micStream = null, micSrc = null;
function audioCtx() {
  if (!actx) {
    actx = new (window.AudioContext || window.webkitAudioContext)();
    analyser = actx.createAnalyser(); analyser.fftSize = 512; analyser.smoothingTimeConstant = 0.6;
    buf = new Uint8Array(analyser.frequencyBinCount);
  }
  if (actx.state === 'suspended') actx.resume();
  return actx;
}
const band = (a, b) => { let s = 0; for (let i = a; i < b; i++) s += buf[i]; return s / (b - a) / 255; };

function reactiveGen(kind) {
  let hue = rgbHsv(S.r, S.g, S.b)[0], avgBass = 0.1, avgMid = 0.1, avgHigh = 0.1, lastBeat = 0, lvl = 0, kick = 0;
  let last = [255, 0, 0];
  return (t) => {
    if (!analyser) return hsvRgb(hue, 1, 1);
    analyser.getByteFrequencyData(buf);
    const s = 0.4 + (S.sens / 100) * 1.6;
    if (kind === 'voice') {
      const raw = clamp(band(2, 60) * s * 1.8, 0, 1);
      lvl += (raw - lvl) * (raw > lvl ? 0.5 : 0.1);
      paintTicks(lvl);
      return wheelAt(3 / 7 + lvl * (4 / 7)); // quiet = blue, loud = red
    }
    // Real frequency bands from the sample rate: 60-250 Hz, 250-2000 Hz, 2000-10000 Hz
    const hz = (f) => clamp(Math.round((f / (actx.sampleRate / 2)) * buf.length), 1, buf.length - 1);
    const bass = band(hz(60), hz(250) + 1), mid = band(hz(250), hz(2000)), high = band(hz(2000), hz(10000));
    const energy = (bass + mid + high) / 3;
    avgBass += (bass - avgBass) * 0.03; avgMid += (mid - avgMid) * 0.03; avgHigh += (high - avgHigh) * 0.03;
    const loud = clamp(energy * s * 2.4, 0, 1);
    pulseK = S.pulse ? (pulseK += ((0.22 + 0.78 * loud) - pulseK) * 0.5) : 1;
    const beat = bass > avgBass * 1.3 + 0.04 && t - lastBeat > 0.18;
    if (beat) lastBeat = t;

    if (S.mstyle === 'flow') {
      if (beat) hue += 40 + bass * 60;
      hue += energy * s * 1.2;
      last = hsvRgb(hue, 1, 1); return last;
    }
    // DISCO: each band is compared with its own recent average, so the
    // instrument that is standing out right now decides the color family.
    if (energy < 0.015) return last;                 // silence: hold the color, never black
    const eb = Math.pow(bass / (avgBass + 0.03), 3);
    const em = Math.pow(mid / (avgMid + 0.03), 3);
    const eh = Math.pow(high / (avgHigh + 0.03), 3);
    let c = [eb, em, eh];
    const mn = Math.min(eb, em, eh); c = c.map((v) => v - mn * 0.85); // keep it saturated, not white
    const mx = Math.max(c[0], c[1], c[2]) || 1; c = c.map((v) => (v / mx) * 255);
    kick = beat ? 1 : kick * 0.68;
    last = mix(c, [255, 0, 0], kick * 0.75);         // every kick punches toward red
    return last;
  };
}

async function startVoice() {
  try {
    audioCtx(); if (!au.paused) au.pause();
    micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false } });
    micSrc = actx.createMediaStreamSource(micStream); micSrc.connect(analyser);
    setMode('voice', reactiveGen('voice'), 0.35); clearSel(); $('rcVoice').classList.add('sel');
  } catch (e) { toast('Allow microphone access to use Voice'); }
}
function stopMic() {
  if (micSrc) { try { micSrc.disconnect(); } catch (e) {} micSrc = null; }
  if (micStream) { micStream.getTracks().forEach((x) => x.stop()); micStream = null; }
}

/* ---------------- music library + player ---------------- */
const au = $('au');
let LIB = null, view = 'folders', openFolder = null, queueSongs = [], qi = -1;
function wireMedia() {
  audioCtx();
  if (!mediaSrc) { mediaSrc = actx.createMediaElementSource(au); mediaSrc.connect(actx.destination); mediaSrc.connect(analyser); }
}
async function loadLibrary() {
  if (!NATIVE) {
    $('musicSub').textContent = 'Folder playback works in the Android app. Here, the strip follows the sound around you.';
    $('musicSeg').hidden = true; $('mlist').innerHTML = '';
    startMicMusic(); return;
  }
  if (LIB) { renderList(); return; }
  $('mlist').innerHTML = '<div class="empty">Reading your music...</div>';
  try { LIB = (await MusicLibrary.getLibrary()).folders || []; renderList(); }
  catch (e) { $('mlist').innerHTML = '<div class="empty">Allow access to your music to see your folders.<br><br><button class="wskip" id="retryLib">Try again</button></div>';
    const r = $('retryLib'); if (r) r.onclick = () => { LIB = null; loadLibrary(); }; }
}
async function startMicMusic() {
  try { audioCtx(); micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    micSrc = actx.createMediaStreamSource(micStream); micSrc.connect(analyser);
    mode = 'music'; gen = reactiveGen('music'); ease = 0.45; clearSel(); $('rcMusic').classList.add('sel');
  } catch (e) { toast('Allow microphone access to use Music'); }
}
const fmt = (ms) => { const s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + String(s % 60).padStart(2, '0'); };
const esc = (s) => String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const IC_FOLDER = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 7h6l2 2h10v10H3z"/></svg>';
const IC_NOTE = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/></svg>';
function renderList() {
  const el = $('mlist');
  document.querySelectorAll('#musicSeg button').forEach((b) => b.classList.toggle('sel', b.dataset.m === (openFolder ? 'folders' : view)));
  if (!LIB.length) { el.innerHTML = '<div class="empty">No songs found on this phone.</div>'; return; }
  if (view === 'folders' && !openFolder) {
    el.innerHTML = LIB.map((f, i) => '<button class="li" data-f="' + i + '"><div class="ic">' + IC_FOLDER + '</div><div class="m"><div class="t">' + esc(f.name) + '</div><div class="d">' + f.songs.length + ' songs</div></div></button>').join('');
    el.querySelectorAll('[data-f]').forEach((b) => (b.onclick = () => { openFolder = LIB[+b.dataset.f]; renderList(); $('view').scrollTop = 0; }));
    return;
  }
  const songs = openFolder ? openFolder.songs : LIB.flatMap((f) => f.songs).sort((a, b) => a.title.localeCompare(b.title));
  const head = openFolder ? '<button class="back" id="upF"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 6l-6 6 6 6"/></svg> ' + esc(openFolder.name) + '</button>' : '';
  const cur = queueSongs[qi];
  el.innerHTML = head + songs.map((s, i) => '<button class="li' + (cur && cur.path === s.path ? ' play' : '') + '" data-s="' + i + '"><div class="ic">' + IC_NOTE + '</div><div class="m"><div class="t">' + esc(s.title) + '</div><div class="d">' + esc(s.artist) + '</div></div><div class="du">' + fmt(s.duration) + '</div></button>').join('');
  if (openFolder) $('upF').onclick = () => { openFolder = null; renderList(); };
  el.querySelectorAll('[data-s]').forEach((b) => (b.onclick = () => { queueSongs = songs; playAt(+b.dataset.s); }));
}
function playAt(i) {
  if (!queueSongs.length) return;
  qi = (i + queueSongs.length) % queueSongs.length;
  const s = queueSongs[qi];
  wireMedia(); stopMic();
  au.src = Capacitor.convertFileSrc(s.path);
  au.play().catch(() => toast('Could not play this file'));
  mode = 'music'; gen = reactiveGen('music'); ease = 0.45; clearSel(); $('rcMusic').classList.add('sel');
  $('now').classList.remove('hide'); $('nowT').textContent = s.title; renderList();
}
au.addEventListener('timeupdate', () => {
  if (!au.duration) return;
  $('progI').style.width = (au.currentTime / au.duration) * 100 + '%';
  $('nowD').textContent = fmt(au.currentTime * 1000) + ' / ' + fmt(au.duration * 1000);
});
au.addEventListener('play', () => ($('ppI').innerHTML = '<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>'));
au.addEventListener('pause', () => ($('ppI').innerHTML = '<path d="M7 5v14l12-7z"/>'));
au.addEventListener('ended', () => playAt(qi + 1));
$('ppB').onclick = () => { if (au.paused) { audioCtx(); au.play(); } else au.pause(); };
$('nextB').onclick = () => playAt(qi + 1);
$('prevB').onclick = () => (au.currentTime > 3 ? (au.currentTime = 0) : playAt(qi - 1));
document.querySelectorAll('#musicSeg button').forEach((b) => (b.onclick = () => { view = b.dataset.m; openFolder = null; if (LIB) renderList(); }));

/* ---------------- music style + pulse ---------------- */
const HINT = { disco: 'Disco: kick drums go red, vocals green, cymbals blue.', flow: 'Flow: colors glide around the wheel and jump on every beat.' };
function paintStyle() {
  document.querySelectorAll('#styleSeg button').forEach((b) => b.classList.toggle('sel', b.dataset.st === S.mstyle));
  $('pulseB').querySelector('.sw').classList.toggle('off', !S.pulse);
  $('styleHint').textContent = HINT[S.mstyle] + (S.pulse ? ' Pulse makes the light breathe with the volume.' : '');
}
document.querySelectorAll('#styleSeg button').forEach((b) => (b.onclick = () => { S.mstyle = b.dataset.st; save(); paintStyle(); }));
$('pulseB').onclick = () => { S.pulse = !S.pulse; save(); paintStyle(); };
paintStyle();

/* ---------------- UI: color ---------------- */
function showColor(c) {
  $('chip').style.background = hex(c); $('hex').textContent = hex(c);
  $('rv').textContent = 'R ' + Math.round(c[0]); $('gv').textContent = 'G ' + Math.round(c[1]); $('bv').textContent = 'B ' + Math.round(c[2]);
}
function placeHandle() {
  const [h, s] = rgbHsv(S.r, S.g, S.b);
  const w = $('wheel').clientWidth || 210, R = w / 2;
  // invert wheelAt: find the stop position closest to this hue by sampling
  let best = 0, bd = 1e9;
  for (let i = 0; i < 360; i++) { const c = wheelAt(i / 360); const d = Math.abs(rgbHsv(c[0], c[1], c[2])[0] - h); const dd = Math.min(d, 360 - d); if (dd < bd) { bd = dd; best = i; } }
  const theta = ((best + 90) % 360) * Math.PI / 180;
  const rr = Math.min(1, s * WHITE_R); $('wh').style.left = R + Math.sin(theta) * rr * R + 'px'; $('wh').style.top = R - Math.cos(theta) * rr * R + 'px';
  $('wh').style.background = hex([S.r, S.g, S.b]);
}
function pickWheel(e) {
  const r = $('wheel').getBoundingClientRect(), R = r.width / 2;
  let x = e.clientX - r.left - R, y = e.clientY - r.top - R;
  const d = Math.hypot(x, y); if (d > R) { x *= R / d; y *= R / d; }
  const dist = Math.min(1, d / R);
  const theta = (Math.atan2(x, -y) * 180 / Math.PI + 360) % 360;   // clockwise from top, like CSS
  const c = mix(wheelAt(((theta - 90 + 360) % 360) / 360), [255, 255, 255], clamp(1 - dist / WHITE_R, 0, 1)).map(Math.round);
  S.r = c[0]; S.g = c[1]; S.b = c[2]; save();
  $('wh').style.left = R + x + 'px'; $('wh').style.top = R + y + 'px'; $('wh').style.background = hex(c);
  showColor(c); solid(true);
  document.querySelectorAll('.gt').forEach((g) => g.classList.remove('sel'));
}
let drag = false;
$('wheel').addEventListener('pointerdown', (e) => { drag = true; $('wheel').setPointerCapture(e.pointerId); pickWheel(e); });
$('wheel').addEventListener('pointermove', (e) => drag && pickWheel(e));
$('wheel').addEventListener('pointerup', () => (drag = false));

const GRADS = [['#e10600','#ff2a6d'],['#6610db','#43caff'],['#6610db','#00ffa0'],['#03294c','#529fff'],['#0024ff','#72e3ff'],['#05107c','#e504ff'],['#fe80b5','#7142fe'],['#05f0fe','#1519bc'],['#6a03ff','#1519bc'],['#630682','#ee065e'],['#26086a','#f00635'],['#ef9962','#d9001e']];
$('gtable').innerHTML = GRADS.map((g, i) => '<button class="gt" data-g="' + i + '" style="background:linear-gradient(135deg,' + g[0] + ',' + g[1] + ')"></button>').join('');
document.querySelectorAll('.gt').forEach((b) => (b.onclick = () => {
  const full = (x) => { const m = Math.max(...x) || 1; return x.map((v) => (v * 255) / m); };
  const [a, c] = GRADS[+b.dataset.g].map(hexRgb).map(full); // same hues, constant intensity
  setMode('preset', (t) => mix(a, c, (Math.sin(t * rate() * 0.6 - Math.PI / 2) + 1) / 2), 0.3);
  clearSel(); b.classList.add('sel'); showColor(a);
}));

/* ---------------- UI: scenes ---------------- */
$('fxg').innerHTML = SCENES.map((s, i) => '<button class="fx" data-x="' + i + '"><div class="pv" style="background:' + s.pv + '"></div><div><div class="nm">' + s.nm + '</div><div class="sb">' + s.sb + '</div></div></button>').join('');
document.querySelectorAll('.fx').forEach((b) => (b.onclick = () => {
  const sc = SCENES[+b.dataset.x]; setMode('scene', sc.g(), sc.e); clearSel(); b.classList.add('sel');
}));
function clearSel() { document.querySelectorAll('.fx.sel,.gt.sel,.rc.sel').forEach((x) => x.classList.remove('sel')); }

/* ---------------- UI: numeric controls ---------------- */
const NUM = { bright: ['brR', 'brN'], speed: ['spR', 'spN'], sens: ['snR', 'snN'] };
function setNum(k, v) {
  v = clamp(Math.round(+v || 0), 0, 100); S[k] = v;
  $(NUM[k][0]).value = v; $(NUM[k][1]).value = v;
  if (k === 'bright') { $('qbR').value = v; $('qbN').textContent = v; if (v > 0 && !S.power) { S.power = true; paintPower(); } }
  save();
}
Object.keys(NUM).forEach((k) => {
  $(NUM[k][0]).addEventListener('input', (e) => setNum(k, e.target.value));
  $(NUM[k][1]).addEventListener('change', (e) => setNum(k, e.target.value));
  $(NUM[k][1]).addEventListener('focus', (e) => e.target.select());
});
document.querySelectorAll('.ar button').forEach((b) => (b.onclick = () => setNum(b.dataset.k, S[b.dataset.k] + +b.dataset.d)));
$('qbR').addEventListener('input', (e) => setNum('bright', e.target.value));

/* ---------------- power + connection ---------------- */
function paintPower() { $('pwr').classList.toggle('on', S.power && S.bright > 0); }
$('pwr').onclick = () => {
  if (S.bright === 0) setNum('bright', 60);
  S.power = !S.power; save(); paintPower();
  toast(S.power ? 'Strip on' : 'Strip off');
};
function setStatus(on) { $('status').classList.toggle('on', on); $('statusTxt').textContent = on ? 'Connected' : 'Tap to connect'; }
function onLost() { BLE.connected = false; stripOn = null; setStatus(false); toast('Strip disconnected'); }

async function doConnect(fromWelcome) {
  const btn = $('connectBtn'); btn.disabled = true; $('wmsg').textContent = 'Searching...';
  try {
    if (NATIVE && !(await BleClient.isEnabled())) { await BleClient.requestEnable(); }
    await BLE.connect();
    stripOn = null; lastSent = ''; setStatus(true); $('welcome').classList.add('hide'); $('wmsg').textContent = '';
    if (!fromWelcome) toast('Connected');
  } catch (e) {
    let m = (e && e.message) || 'Could not connect';
    if (/cancel/i.test(m)) m = 'No strip selected';
    if (NATIVE) { try { if (!(await BleClient.isLocationEnabled())) m = 'Turn on Location so the phone can find the strip'; } catch (x) {} }
    $('wmsg').textContent = m; if (!fromWelcome) toast(m);
  }
  btn.disabled = false;
}
$('connectBtn').onclick = () => doConnect(true);
$('skipBtn').onclick = () => $('welcome').classList.add('hide');
$('status').onclick = () => {
  if (!BLE.connected) return doConnect(false);
  $('shD').textContent = 'Connected to your strip.'; $('sheet').classList.remove('hide');
};
const closeSheet = () => $('sheet').classList.add('hide');
$('shCancel').onclick = closeSheet;
$('sheet').onclick = (e) => { if (e.target.id === 'sheet') closeSheet(); };
$('shDisc').onclick = async () => { closeSheet(); await BLE.disconnect(); stripOn = null; setStatus(false); toast('Disconnected'); };
$('shOffDisc').onclick = async () => {
  closeSheet(); S.power = false; save(); paintPower();
  await BLE.write(CMD.power(false));
  setTimeout(async () => { await BLE.disconnect(); stripOn = null; setStatus(false); toast('Strip off and disconnected'); }, 150);
};

/* ---------------- navigation (Android back button uses history) ---------------- */
function showTab(id) {
  document.querySelectorAll('.nav button').forEach((b) => b.classList.toggle('sel', b.dataset.t === id));
  document.querySelectorAll('.pane').forEach((p) => p.classList.toggle('on', p.id === id));
  closeSub(true); $('view').scrollTop = 0;
}
document.querySelectorAll('.nav button').forEach((b) => (b.onclick = () => showTab(b.dataset.t)));
let sub = null;
function openSub(which) {
  sub = which; $('sc-main').hidden = true; $('sc-music').hidden = which !== 'music'; $('sc-voice').hidden = which !== 'voice';
  $('view').scrollTop = 0; history.pushState({ sub: which }, '');
  if (which === 'voice') startVoice(); else loadLibrary();
}
function closeSub(silent) {
  if (!sub) return;
  if (sub === 'voice') { stopMic(); if (mode === 'voice') solid(false); }
  if (sub === 'music' && !NATIVE) { stopMic(); if (mode === 'music') solid(false); }
  sub = null; $('sc-main').hidden = false; $('sc-music').hidden = true; $('sc-voice').hidden = true;
  if (!silent && history.state && history.state.sub) history.back();
}
window.addEventListener('popstate', () => { if (sub) { const s = sub; sub = null; closeSubFromBack(s); } });
function closeSubFromBack(s) {
  if (s === 'voice') { stopMic(); if (mode === 'voice') solid(false); }
  if (s === 'music' && !NATIVE) { stopMic(); if (mode === 'music') solid(false); }
  $('sc-main').hidden = false; $('sc-music').hidden = true; $('sc-voice').hidden = true;
}
$('rcMusic').onclick = () => openSub('music');
$('rcVoice').onclick = () => openSub('voice');
document.querySelectorAll('[data-back]').forEach((b) => (b.onclick = () => closeSub(false)));

/* ---------------- voice ticks ---------------- */
const TN = 72; let litPrev = -1;
(function () {
  let s = '';
  for (let i = 0; i < TN; i++) {
    const a = (i / TN) * 2 * Math.PI - Math.PI / 2;
    s += '<line x1="' + (105 + 70 * Math.cos(a)).toFixed(1) + '" y1="' + (105 + 70 * Math.sin(a)).toFixed(1) + '" x2="' + (105 + 95 * Math.cos(a)).toFixed(1) + '" y2="' + (105 + 95 * Math.sin(a)).toFixed(1) + '" stroke="#26262a" stroke-width="3" stroke-linecap="round"/>';
  }
  $('ticks').innerHTML = s;
})();
function paintTicks(l) {
  const lit = Math.round(l * TN); if (lit === litPrev) return; litPrev = lit;
  const ls = $('ticks').children; for (let i = 0; i < TN; i++) ls[i].setAttribute('stroke', i < lit ? '#ff2a2a' : '#26262a');
  $('micP').textContent = Math.round(l * 100) + '%';
}

/* ---------------- routines ---------------- */
const RT = load('u_rt') || { on: { time: '19:00', days: [1,1,1,1,1,0,0], active: true }, off: { time: '23:00', days: [1,1,1,1,1,0,0], active: true } };
const DAYS = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
const ampm = (t) => { let [h, m] = t.split(':').map(Number); return [(h % 12) || 12, String(m).padStart(2, '0'), h >= 12 ? 'PM' : 'AM']; };
function renderRT() {
  ['on', 'off'].forEach((k) => {
    const r = RT[k], [h, m, ap] = ampm(r.time);
    $(k === 'on' ? 'rOn' : 'rOff').innerHTML =
      '<div class="rule"><div><div class="rl">' + (k === 'on' ? 'Turn on' : 'Turn off') + '</div><div class="rt">' + h + ':' + m + ' <small>' + ap + '</small><input type="time" value="' + r.time + '" data-rk="' + k + '"></div></div><button class="sw' + (r.active ? '' : ' off') + '" data-sw="' + k + '"><i></i></button></div>' +
      '<div class="days">' + r.days.map((d, i) => '<button class="day' + (d ? ' on' : '') + '" data-dk="' + k + '" data-d="' + i + '">' + DAYS[i] + '</button>').join('') + '</div>';
  });
  const a = ampm(RT.on.time), b = ampm(RT.off.time);
  $('sum').innerHTML = 'Your strip <b>turns on</b> at <b>' + a[0] + ':' + a[1] + ' ' + a[2] + '</b> and <b>turns off</b> at <b>' + b[0] + ':' + b[1] + ' ' + b[2] + '</b> on the selected days.';
  document.querySelectorAll('[data-rk]').forEach((i) => (i.onchange = () => { RT[i.dataset.rk].time = i.value; saveRT(); }));
  document.querySelectorAll('[data-sw]').forEach((b) => (b.onclick = () => { RT[b.dataset.sw].active = !RT[b.dataset.sw].active; saveRT(); }));
  document.querySelectorAll('[data-dk]').forEach((b) => (b.onclick = () => { const d = RT[b.dataset.dk].days; d[+b.dataset.d] = d[+b.dataset.d] ? 0 : 1; saveRT(); }));
}
function saveRT() { localStorage.setItem('u_rt', JSON.stringify(RT)); renderRT(); }
document.querySelectorAll('[data-pre]').forEach((b) => (b.onclick = () => {
  const m = { all: [1,1,1,1,1,1,1], wkdy: [1,1,1,1,1,0,0], wknd: [0,0,0,0,0,1,1] }[b.dataset.pre];
  RT.on.days = m.slice(); RT.off.days = m.slice(); saveRT(); toast('Days updated');
}));
let fired = '';
setInterval(() => {
  const d = new Date(), day = (d.getDay() + 6) % 7;
  const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  ['on', 'off'].forEach((k) => {
    const r = RT[k], key = k + hm + day;
    if (r.active && r.days[day] && r.time === hm && fired !== key) { fired = key; S.power = k === 'on'; if (S.power && !S.bright) setNum('bright', 60); save(); paintPower(); toast('Routine: strip ' + k); }
  });
}, 15000);

/* ---------------- toast + boot ---------------- */
function toast(m) { const t = $('toast'); t.textContent = m; t.classList.add('show'); clearTimeout(t.h); t.h = setTimeout(() => t.classList.remove('show'), 2200); }
Object.keys(NUM).forEach((k) => setNum(k, S[k]));
showColor([S.r, S.g, S.b]); paintPower(); renderRT(); setStatus(false); solid(false);
requestAnimationFrame(placeHandle);
