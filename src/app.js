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
import { Capacitor, registerPlugin } from '@capacitor/core';

const NATIVE = Capacitor.isNativePlatform();
const MusicLibrary = registerPlugin('MusicLibrary');
const Strip = registerPlugin('Strip');   // our own native Bluetooth (APK)
const Player = registerPlugin('Player');  // native player: ExoPlayer, same engine as COMET (APK)
const $ = (id) => document.getElementById(id);

/* ---------------- state ---------------- */
const S = Object.assign(
  { r: 225, g: 6, b: 0, bright: 100, speed: 50, sens: 70, power: true, lastId: null, mstyle: 'disco', sync: 150 },
  load('u_state') || {}
);
function save() { localStorage.setItem('u_state', JSON.stringify(S)); }
function load(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } }

/* ---------------- BLE: native (APK) and Web Bluetooth (Chrome) ---------------- */
const SERVICE = '0000fff0-0000-1000-8000-00805f9b34fb';
const IS_STRIP = (n) => /^(ELK|MELK|LEDBLE|BLEDOM)/i.test(n || '');
const err = (code) => Object.assign(new Error(code), { code });

const NativeBLE = {
  connected: false,
  async open(id) { await Strip.connect({ id }); this.connected = true; S.lastId = id; save(); },
  async connect() {
    const st = await Strip.status();
    if (!st.supported) throw err('NO_BLUETOOTH');
    if (!st.enabled) await Strip.enable();                       // one tap system dialog
    if (S.lastId) { try { await this.open(S.lastId); return; } catch (e) { /* try the paired list */ } }
    const { devices } = await Strip.bonded();                      // paired strip: no scan, no Location
    for (const d of devices.filter((x) => IS_STRIP(x.name))) { try { await this.open(d.id); return; } catch (e) { /* next */ } }
    const found = ((await Strip.scan({ ms: 4000 })).devices || []).filter((x) => IS_STRIP(x.name)).sort((a, b) => b.rssi - a.rssi);
    if (!found.length) throw err('NO_STRIP');
    const pick = found.length === 1 ? found[0] : await chooseStrip(found);
    await this.open(pick.id);
  },
  write(bytes, reliable) { return Strip.write({ bytes, reliable: !!reliable }).catch(() => {}); },
  async disconnect() { this.connected = false; try { await Strip.disconnect(); } catch (e) { /* ignore */ } }
};
if (NATIVE) Strip.addListener('disconnected', () => onLost());

const WebBLE = {
  connected: false, ch: null, dev: null,
  async connect() {
    if (!navigator.bluetooth) throw err('NO_WEB_BT');
    this.dev = await navigator.bluetooth.requestDevice({ filters: [{ namePrefix: 'ELK' }], optionalServices: [SERVICE] });
    this.dev.addEventListener('gattserverdisconnected', () => { if (this.connected) onLost(); });
    const server = await this.dev.gatt.connect();
    const svc = await server.getPrimaryService(SERVICE);
    const chars = await svc.getCharacteristics();
    this.ch = chars.find((c) => c.properties.writeWithoutResponse) || chars.find((c) => c.properties.write);
    if (!this.ch) throw err('NOT_A_STRIP');
    this.connected = true;
  },
  async write(bytes, reliable) {
    if (!this.ch) return; const d = new Uint8Array(bytes);
    const safe = reliable && this.ch.properties.write;   // power: wait for the strip to confirm
    for (let i = 0; i < (reliable ? 6 : 1); i++) {
      try { if (!safe && this.ch.properties.writeWithoutResponse) await this.ch.writeValueWithoutResponse(d); else await this.ch.writeValue(d); return; }
      catch (e) { await new Promise((r) => setTimeout(r, 35)); }
    }
  },
  async disconnect() { this.connected = false; try { this.dev && this.dev.gatt.disconnect(); } catch (e) { /* ignore */ } }
};
const BLE = NATIVE ? NativeBLE : WebBLE;

/* ---------------- delivery lane: power is queued, color keeps only the latest ---------------- */
const CMD = {
  power: (on) => [0x7e, 0, 4, on ? 1 : 0, 0, 0, 0, 0, 0xef],
  color: (r, g, b) => [0x7e, 0, 5, 3, r & 255, g & 255, b & 255, 0, 0xef]
};
const queue = []; let busy = false; let latestColor = null; let lastSendAt = 0;
function pump() {
  if (busy || !BLE.connected) return;
  let job;
  if (queue.length) job = queue.shift();
  else if (latestColor) { job = { bytes: latestColor, reliable: performance.now() < burstUntil }; latestColor = null; }   // confirmed right after power on
  else return;
  busy = true; lastSendAt = performance.now();
  BLE.write(job.bytes, job.reliable).finally(() => setTimeout(() => { busy = false; pump(); }, 45));
}
function sendPower(on) {                       // power is never dropped: queued, retried natively
  for (let i = queue.length - 1; i >= 0; i--) if (queue[i].power) queue.splice(i, 1);   // only the latest state matters
  queue.push({ bytes: CMD.power(on), reliable: true, power: true }); pump();
}
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
let stripOn = null;          // what the strip currently is (power)
const rate = () => 0.15 + (S.speed / 100) * 1.85;

let lastTickAt = 0;
let lastPowerAt = 0, burstUntil = 0, lastReassert = 0, powerRepeats = 0, snapNow = false;
let rtReady = false;
function tick() {
  const now = performance.now();
  if (now - lastTickAt < 40) return;          // native ticker + JS timer never double up
  lastTickAt = now;
  routineCheck();
  const t = (now - t0) / 1000;
  const target = gen(t);
  const e = snapNow ? 1 : ease; snapNow = false;
  for (let i = 0; i < 3; i++) out[i] += (target[i] - out[i]) * e;
  if (!BLE.connected) return;
  const wantOn = S.power && S.bright > 0;
  // Power changes are sent at once; the state is re-affirmed every few seconds so a lost packet heals itself.
  if (stripOn !== wantOn) { burstUntil = now + 1500; powerRepeats = 2; }   // after a change: repeat it twice more
  const repeatDue = powerRepeats > 0 && now - lastPowerAt > 450;
  if (stripOn !== wantOn || repeatDue || now - lastPowerAt > 3000) {
    if (repeatDue && stripOn === wantOn) powerRepeats--;
    sendPower(wantOn); stripOn = wantOn; lastPowerAt = now; lastSent = '';
  }
  if (now < burstUntil && now - lastReassert > 150) { lastReassert = now; lastSent = ''; }
  if (!wantOn) { if (lastSent !== 'off') { lastSent = 'off'; sendColor([0, 0, 0]); } return; }   // black as backup
  if (now - lastSendAt > 2500) lastSent = '';                                                       // idle heartbeat
  // Keep intensity locked to the target: blending two hues must never dim the strip.
  const tm = mode === 'solid' ? Math.max(target[0], target[1], target[2]) : 255, om = Math.max(out[0], out[1], out[2]);
  const k = (S.bright / 100) * (om > 1 && tm > 0 ? tm / om : 1);
  const c = out.map((x) => clamp(Math.round(x * k), 0, 255));
  const key = c.join(',');
  if (key !== lastSent) { lastSent = key; sendColor(c); }
}
setInterval(tick, 50);
window.__ut = tick;               // the APK calls this while in the background

function setMode(m, g, e) { mode = m; gen = g; ease = e; t0 = performance.now(); if (m !== 'voice') stopMic(); }
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
let actx = null, analyser = null, buf = null, tbuf = null, mediaSrc = null, micStream = null, micSrc = null;
function audioCtx() {
  if (!actx) {
    actx = new (window.AudioContext || window.webkitAudioContext)();
    analyser = actx.createAnalyser(); analyser.fftSize = 512; analyser.smoothingTimeConstant = 0.35;
    analyser.minDecibels = -90; analyser.maxDecibels = -10;   // wide range: loud music never saturates
    buf = new Uint8Array(analyser.frequencyBinCount); tbuf = new Float32Array(analyser.fftSize);
  }
  if (actx.state === 'suspended') actx.resume();
  return actx;
}

const CLUB = [[255,0,0],[0,70,255],[255,0,200],[0,255,200],[255,140,0],[140,0,255],[0,255,40],[255,0,70]];
function reactiveGen(kind) {
  let hue = rgbHsv(S.r, S.g, S.b)[0], avgBass = 0.1, avgMid = 0.1, avgHigh = 0.1, lastBeat = 0, lvl = 0, kick = 0;
  let hard = 0, punch = 1, ci = 0, avgFlux = 0.01; const beats = []; let prev = new Uint8Array(256);
  let last = [255, 0, 0];
  const nat = NATIVE && kind === 'music';          // songs and karaoke: the player's own audio, not the microphone
  return (t) => {
    let B, sr;
    if (nat) { B = nativeSpectrum(); if (!B) return last; sr = specRate; }
    else { if (!analyser) return hsvRgb(hue, 1, 1); analyser.getByteFrequencyData(buf); B = buf; sr = actx.sampleRate; }
    const s = 0.4 + (S.sens / 100) * 1.6;
    if (kind === 'voice') {
      // Loudness in real decibels (independent of the analyser's display range).
      analyser.getFloatTimeDomainData(tbuf);
      let sum = 0; for (let i = 0; i < tbuf.length; i++) sum += tbuf[i] * tbuf[i];
      const db = 20 * Math.log10(Math.sqrt(sum / tbuf.length) + 1e-9);   // speech is about -45 (soft) to -15 dB (loud)
      const raw = clamp(((db + 52) / 40) * s * 0.75, 0, 1);
      lvl += (raw - lvl) * (raw > lvl ? 0.5 : 0.1);
      paintTicks(lvl);
      return wheelAt(3 / 7 + lvl * (4 / 7)); // quiet = blue, loud = red
    }
    // Real frequency bands from the sample rate: 60-250 Hz, 250-2000 Hz, 2000-10000 Hz
    const hz = (f) => clamp(Math.round((f / (sr / 2)) * B.length), 1, B.length - 1);
    const band = (a, b) => { let sum = 0; for (let i = a; i < b; i++) sum += B[i]; return sum / (b - a) / 255; };
    if (prev.length !== B.length) prev = new Uint8Array(B.length);
    const bass = band(hz(60), hz(250) + 1), mid = band(hz(250), hz(2000)), high = band(hz(2000), hz(10000));
    const energy = (bass + mid + high) / 3;
    avgBass += (bass - avgBass) * 0.03; avgMid += (mid - avgMid) * 0.03; avgHigh += (high - avgHigh) * 0.03;
    const loud = clamp(energy * s * 2.4, 0, 1);
    // Beat = sudden NEW energy in the low end (spectral flux), robust with any mix or volume.
    let flux = 0; const b0 = hz(40), b1 = hz(160) + 1;
    for (let i = b0; i < b1; i++) { const d = B[i] - prev[i]; if (d > 0) flux += d; }
    flux /= (b1 - b0) * 255;
    prev.set(B);
    avgFlux += (flux - avgFlux) * 0.05;
    const beat = flux > avgFlux * 2 + 0.02 && t - lastBeat > 0.2;
    if (beat) lastBeat = t;

    if (S.mstyle === 'flow') {
      if (beat) hue += 40 + bass * 60;
      hue += energy * s * 1.2;
      last = hsvRgb(hue, 1, 1); return last;
    }
    // DISCO reads the song. "hard" (0..1) rises with strong, frequent kicks (phonk, drops)
    // and falls on calm passages, so one song can go from smooth fades to hard club cuts.
    if (beat) {
      const strength = flux / (avgFlux + 0.005);
      punch += (strength - punch) * 0.3;
      beats.push(t);
    }
    while (beats.length && t - beats[0] > 4) beats.shift();
    punch += (1 - punch) * 0.008;
    const rate = beats.length / 4;
    const raw = Math.sqrt(clamp((rate - 0.6) / 1.4, 0, 1) * clamp((punch - 2) / 3, 0, 1)) * clamp(loud * 2.5, 0, 1);   // strong AND frequent
    hard += (raw - hard) * 0.04;
    window.__hard = hard;

    if (energy < 0.015) return last;                 // silence: hold the color, never black
    // Instrument color: the band standing out right now (kick red, vocals green, cymbals blue).
    const eb = Math.pow(bass / (avgBass + 0.03), 3);
    const em = Math.pow(mid / (avgMid + 0.03), 3);
    const eh = Math.pow(high / (avgHigh + 0.03), 3);
    let c = [eb, em, eh];
    const mn = Math.min(eb, em, eh); c = c.map((v) => v - mn * 0.85);
    const mx = Math.max(c[0], c[1], c[2]) || 1; c = c.map((v) => (v / mx) * 255);

    // Club color: on each beat jump to a clearly different color and hold it.
    if (beat) {
      ci = (ci + 2 + (Math.random() < 0.5 ? 0 : 1)) % CLUB.length;
      if (hard > 0.55) snapNow = true;               // hard cut, no fade
    }
    kick = beat ? 1 : kick * 0.7;
    const soft = mix(c, [255, 0, 0], kick * 0.35);
    last = mix(soft, CLUB[ci], clamp((hard - 0.35) / 0.4, 0, 1));
    ease = 0.12 + hard * 0.78;                       // calm: slow fades, hard: instant
    return last;
  };
}

async function startVoice() {
  try {
    audioCtx(); if (NATIVE && MS.playing) Player.toggle().catch(() => {});
    micStream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false } });
    micSrc = actx.createMediaStreamSource(micStream); micSrc.connect(analyser);
    setMode('voice', reactiveGen('voice'), 0.35); clearSel(); $('rcVoice').classList.add('sel');
  } catch (e) { toast('Allow microphone access to use Voice'); }
}
function stopMic() {
  if (micSrc) { try { micSrc.disconnect(); } catch (e) {} micSrc = null; }
  if (micStream) { micStream.getTracks().forEach((x) => x.stop()); micStream = null; }
}

/* ---------------- media library + player (songs and videos) ---------------- */
const LIBS = { audio: null, video: null };
let view = 'folders', openFolder = null, queueItems = [], qi = -1, curKind = null, query = '';
S.lib = S.lib || 'audio'; S.sort = S.sort || 'az';
if (typeof S.sync !== 'number') S.sync = 150;
const withTimeout = (p, ms) => Promise.race([p, new Promise((_, rej) => setTimeout(() => rej(err('TIMEOUT')), ms))]);
const loading = { audio: false, video: false };
async function loadLibrary() {
  const kind = S.lib;
  if (!NATIVE) {
    $('libSeg').hidden = true; $('musicSeg').hidden = true; $('mlist').innerHTML = '';
    document.querySelector('.tools').hidden = true;
    $('musicSub').textContent = 'Music and karaoke play in the Android app. Here, the strip follows the sound around you.';
    $('syncRow').hidden = true; $('syncHint').hidden = true;
    startMicMusic(); return;
  }
  if (LIBS[kind]) { renderList(); return; }
  if (loading[kind]) return;
  loading[kind] = true;
  $('mlist').innerHTML = '<div class="empty">' + (kind === 'video' ? 'Reading your karaoke videos...' : 'Reading your music...') + '</div>';
  const p = MusicLibrary.getLibrary({ kind });
  p.then((r) => { LIBS[kind] = r.folders || []; if (sub === 'music' && S.lib === kind) renderList(); }).catch(() => {});
  try { await withTimeout(p, 30000); }
  catch (e) {
    if (S.lib === kind) {
      const denied = /PERMISSION/.test((e && (e.code || e.message)) || '');
      $('mlist').innerHTML = '<div class="empty">' + (denied
        ? 'UNIVERSE needs access to your ' + (kind === 'video' ? 'videos' : 'music') + ' to show your folders.'
        : 'This is taking too long.') +
        '<br><br><button class="wskip" id="retryLib">Try again</button>' + (denied ? '&nbsp;&nbsp;<button class="wskip" id="setLib">Open settings</button>' : '') + '</div>';
      $('retryLib').onclick = () => loadLibrary();
      if (denied) $('setLib').onclick = () => MusicLibrary.openAppSettings();
    }
  }
  loading[kind] = false;
}
async function startMicMusic() {
  try { audioCtx(); micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    micSrc = actx.createMediaStreamSource(micStream); micSrc.connect(analyser);
    mode = 'music'; gen = reactiveGen('music'); ease = 0.45; clearSel(); $('rcMusic').classList.add('sel');
  } catch (e) { toast('Allow microphone access to use Music'); }
}
const fmt = (ms) => { if (!isFinite(ms) || ms < 0) ms = 0; const s = Math.floor(ms / 1000); const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60);
  return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s % 60).padStart(2, '0'); };
const esc = (s) => String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
const IC_FOLDER = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M3 7h6l2 2h10v10H3z"/></svg>';
const IC_NOTE = '<svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M9 18V6l10-2v12"/><circle cx="6.5" cy="18" r="2.5"/><circle cx="16.5" cy="16" r="2.5"/></svg>';
const IC_VIDEO = '<svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>';

const byName = (a, b) => (a.title || a.name).localeCompare(b.title || b.name, undefined, { sensitivity: 'base', numeric: true });
function sortItems(list) {
  const k = S.sort, out = list.slice();
  if (k === 'az') out.sort(byName); else if (k === 'za') out.sort((a, b) => byName(b, a));
  else if (k === 'new') out.sort((a, b) => b.added - a.added); else if (k === 'old') out.sort((a, b) => a.added - b.added);
  else if (k === 'long') out.sort((a, b) => b.duration - a.duration); else out.sort((a, b) => a.duration - b.duration);
  return out;
}
function sortFolders(list) {
  const k = S.sort, total = (f) => f.songs.reduce((t, x) => t + x.duration, 0), out = list.slice();
  if (k === 'az' || k === 'long' || k === 'short') out.sort(byName); else if (k === 'za') out.sort((a, b) => byName(b, a));
  else if (k === 'new') out.sort((a, b) => b.added - a.added); else out.sort((a, b) => a.added - b.added);
  if (k === 'long') out.sort((a, b) => total(b) - total(a)); if (k === 'short') out.sort((a, b) => total(a) - total(b));
  return out;
}
function renderList() {
  const lib = LIBS[S.lib], box = $('mlist'), isV = S.lib === 'video';
  $('allTab').textContent = isV ? 'All karaoke' : 'All songs';
  document.querySelectorAll('#musicSeg button').forEach((b) => b.classList.toggle('sel', b.dataset.m === (openFolder ? 'folders' : view)));
  if (!lib) return;
  if (!lib.length) { box.innerHTML = '<div class="empty">' + (isV ? 'No karaoke videos found on this phone.' : 'No songs found on this phone.') + '</div>'; return; }
  const q = query.trim().toLowerCase();
  if (view === 'folders' && !openFolder && !q) {
    const fs = sortFolders(lib);
    box.innerHTML = fs.map((f, i) => '<button class="li" data-f="' + i + '"><div class="ic">' + IC_FOLDER + '</div><div class="m"><div class="t">' + esc(f.name) + '</div><div class="d">' + f.songs.length + (isV ? ' videos' : ' songs') + '</div></div></button>').join('');
    box.querySelectorAll('[data-f]').forEach((b) => (b.onclick = () => { openFolder = fs[+b.dataset.f]; renderList(); $('view').scrollTop = 0; }));
    return;
  }
  let items = openFolder ? openFolder.songs : lib.flatMap((f) => f.songs);
  if (q) items = (openFolder ? openFolder.songs : lib.flatMap((f) => f.songs)).filter((x) => (x.title + ' ' + x.artist).toLowerCase().includes(q));
  items = sortItems(items);
  const head = openFolder ? '<button class="back" id="upF"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 6l-6 6 6 6"/></svg> ' + esc(openFolder.name) + '</button>' : '';
  const cur = queueItems[qi];
  box.innerHTML = head + (items.length ? items.map((x, i) => '<button class="li' + (cur && cur.path === x.path ? ' play' : '') + '" data-s="' + i + '"><div class="ic' + (isV ? ' v' : '') + '">' + (isV ? IC_VIDEO : IC_NOTE) + '</div><div class="m"><div class="t">' + esc(x.title) + '</div><div class="d">' + esc(x.artist || (isV ? 'Karaoke' : '')) + '</div></div><div class="du">' + fmt(x.duration) + '</div></button>').join('')
    : '<div class="empty">Nothing matches your search.</div>');
  if (openFolder) $('upF').onclick = () => { openFolder = null; renderList(); };
  box.querySelectorAll('[data-s]').forEach((b) => (b.onclick = () => { queueItems = items; playAt(+b.dataset.s, S.lib); }));
}

/* ---- native player (ExoPlayer in the APK): songs and karaoke videos ---- */
let MS = { playing: false, path: '', title: '', artist: '', position: 0, duration: 0, kind: null, count: 0 };   // last state the player reported

function paintMini(m) {
  $('mini').classList.remove('hide');
  $('nowT').textContent = m.title || '';
  $('mArt').innerHTML = m.kind === 'video' ? IC_VIDEO : IC_NOTE;
  const dur = m.duration || 0, pos = m.position || 0;
  $('progI').style.width = (dur ? Math.min(100, (pos / dur) * 100) : 0) + '%';
  $('nowD').textContent = dur ? fmt(pos) + ' / ' + fmt(dur) : (m.kind === 'video' ? 'Karaoke' : '');
  $('ppI').innerHTML = m.playing ? '<path d="M6 5h4v14H6zM14 5h4v14h-4z"/>' : '<path d="M7 5v14l12-7z"/>';
}
function onPlayerState(m) {
  if (!m || !m.count) { MS = { playing: false, count: 0 }; curKind = null; $('mini').classList.add('hide'); syncSession(); return; }
  MS = m; curKind = m.kind;
  if (m.index !== qi && m.index >= 0 && m.index < queueItems.length) { qi = m.index; if (sub === 'music') renderList(); }   // next song, headset buttons...
  paintMini(m);
}
function playAt(i, kind) {
  if (!NATIVE || !queueItems.length) return;
  kind = kind || curKind || 'audio';
  qi = (i + queueItems.length) % queueItems.length;
  const x = queueItems[qi];
  curKind = kind; stopMic();
  Player.play({ kind, index: qi, items: queueItems.map((q) => ({ uri: q.uri, path: q.path, title: q.title, artist: q.artist })) })
    .catch(() => toast('This file could not be played'));
  mode = 'music'; gen = reactiveGen('music'); ease = 0.45; clearSel(); $('rcMusic').classList.add('sel');
  paintMini({ title: x.title, kind, playing: true, position: 0, duration: x.duration });
  if (sub === 'music') renderList();
}
if (NATIVE) {
  Player.addListener('state', onPlayerState);
  Player.addListener('error', () => toast('This file could not be played'));
  document.addEventListener('visibilitychange', () => { if (!document.hidden) Player.getState().then(onPlayerState).catch(() => {}); });
}
$('ppB').onclick = () => { if (!NATIVE || !curKind) return; if (curKind === 'video' && !MS.playing) Player.openVideo(); Player.toggle(); };
$('nextB').onclick = () => { if (NATIVE && curKind) Player.next(); };
$('prevB').onclick = () => { if (NATIVE && curKind) Player.prev(); };
$('mOpen').onclick = () => { if (curKind === 'video') Player.openVideo(); else { showTab('p-scenes'); openSub('music'); } };

/* ---- what the lights hear: the audio the player is about to play, 20 times per second ---- */
const specQ = []; let specRate = 44100, specCur = null;
if (NATIVE) Player.addListener('spectrum', (e) => {
  const raw = atob(e.d), a = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) a[i] = raw.charCodeAt(i);
  specQ.push({ t: performance.now(), a }); specRate = e.sr;
  if (specQ.length > 40) specQ.shift();
});
// The newest frame that is at least S.sync ms old. A Bluetooth speaker plays later than the phone
// thinks, so delaying the light by that much puts color and sound back together.
function nativeSpectrum() {
  const due = performance.now() - S.sync;
  let f = null;
  while (specQ.length && specQ[0].t <= due) f = specQ.shift();
  if (f) specCur = f;
  if (specCur && performance.now() - specCur.t > 700 + S.sync) specCur = null;   // paused or stopped: let go
  return specCur ? specCur.a : null;
}

/* ---- light sync ---- */
$('syR').value = S.sync; $('syN').textContent = S.sync + ' ms';
$('syR').addEventListener('input', () => { S.sync = +$('syR').value; $('syN').textContent = S.sync + ' ms'; save(); });

/* ---- library controls ---- */
document.querySelectorAll('#libSeg button').forEach((b) => (b.onclick = () => {
  if (S.lib === b.dataset.k) return;
  S.lib = b.dataset.k; save(); openFolder = null; view = 'folders';
  document.querySelectorAll('#libSeg button').forEach((x) => x.classList.toggle('sel', x === b));
  loadLibrary();
}));
document.querySelectorAll('#musicSeg button').forEach((b) => (b.onclick = () => { view = b.dataset.m; openFolder = null;
  document.querySelectorAll('#musicSeg button').forEach((x) => x.classList.toggle('sel', x === b));
  if (LIBS[S.lib]) renderList(); else loadLibrary(); }));
$('sortSel').value = S.sort;
$('sortSel').onchange = () => { S.sort = $('sortSel').value; save(); if (LIBS[S.lib]) renderList(); };
$('q').addEventListener('input', () => { query = $('q').value; if (LIBS[S.lib]) renderList(); });
document.querySelectorAll('#libSeg button').forEach((x) => x.classList.toggle('sel', x.dataset.k === S.lib));

/* ---- background notification (light) ---- */
let lastSession = '';
function syncSession() {
  if (!NATIVE) return;
  const st = { on: BLE.connected, light: S.power && S.bright > 0 };
  const key = JSON.stringify(st); if (key === lastSession) return; lastSession = key;
  Strip.session(st).catch(() => {});
}
if (NATIVE) Strip.addListener('media', ({ action }) => {
  if (action === 'power') { if (S.bright === 0) setNum('bright', 60); S.power = !S.power; save(); paintPower(); }
});

/* ---------------- music style ---------------- */
const HINT = { disco: 'Disco: smooth on calm songs, hard cuts on strong beats.', flow: 'Flow: colors glide around the wheel and jump on every beat.' };
function paintStyle() {
  document.querySelectorAll('#styleSeg button').forEach((b) => b.classList.toggle('sel', b.dataset.st === S.mstyle));
  $('styleHint').textContent = HINT[S.mstyle];
}
document.querySelectorAll('#styleSeg button').forEach((b) => (b.onclick = () => { S.mstyle = b.dataset.st; save(); paintStyle(); }));
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
const NUM = { speed: ['spR', 'spN'], sens: ['snR', 'snN'] };
function setNum(k, v) {
  v = clamp(Math.round(+v || 0), 0, 100); S[k] = v;
  if (NUM[k]) { $(NUM[k][0]).value = v; if (document.activeElement !== $(NUM[k][1])) $(NUM[k][1]).value = v; }
  if (k === 'bright') { $('qbR').value = v; if (document.activeElement !== $('qbN')) $('qbN').value = v; if (v > 0 && !S.power) S.power = true; paintPower(); }
  save();
}
function numField(input, k) {
  input.addEventListener('focus', () => { input.select(); setTimeout(() => input.select(), 0); });
  input.addEventListener('input', () => { if (input.value !== '' && !isNaN(+input.value)) setNum(k, input.value); });   // live while typing
  const done = () => { setNum(k, input.value === '' ? S[k] : input.value); input.value = S[k]; };
  input.addEventListener('change', done); input.addEventListener('blur', done);
  input.addEventListener('keydown', (e) => { if (e.key === 'Enter') input.blur(); });
}
Object.keys(NUM).forEach((k) => {
  $(NUM[k][0]).addEventListener('input', (e) => setNum(k, e.target.value));
  numField($(NUM[k][1]), k);
});
numField($('qbN'), 'bright');
$('qbR').addEventListener('input', (e) => setNum('bright', e.target.value));

/* ---------------- power + connection ---------------- */
function paintPower() { $('pwr').classList.toggle('on', S.power && S.bright > 0); if (typeof syncSession === 'function') syncSession(); }
$('pwr').onclick = () => {
  if (S.bright === 0) setNum('bright', 60);
  S.power = !S.power; save(); paintPower();
  toast(S.power ? 'Strip on' : 'Strip off');
};
function setStatus(on) { $('status').classList.toggle('on', on); $('statusTxt').textContent = on ? 'Connected' : 'Tap to connect'; }
function onLost() { BLE.connected = false; stripOn = null; setStatus(false); syncSession(); toast('Strip disconnected'); }

const MSG = {
  BT_OFF: 'Bluetooth is off. Turn it on to connect.',
  PERMISSION_DENIED: 'Allow "Nearby devices" so UNIVERSE can reach your strip.',
  NO_STRIP: 'No strip found. Check it is plugged in and close the other LED app.',
  CONNECT_FAILED: 'Could not connect. Close the other LED app and try again.',
  TIMEOUT: 'The strip did not answer. Close the other LED app and try again.',
  NOT_A_STRIP: 'That device is not a compatible LED strip.',
  NO_BLUETOOTH: 'This phone has no Bluetooth.',
  NO_WEB_BT: 'Open this page in Chrome to use Bluetooth.'
};
function explain(e) {
  const c = (e && (e.code || e.message)) || '';
  if (/cancel/i.test(c)) return 'No strip selected';
  return MSG[c] || 'Could not connect. Try again.';
}
async function doConnect(fromWelcome) {
  const btn = $('connectBtn'); btn.disabled = true; $('wmsg').textContent = 'Connecting...';
  try {
    await BLE.connect();
    stripOn = null; lastSent = ''; setStatus(true); $('welcome').classList.add('hide'); $('wmsg').textContent = '';
    syncSession(); syncRoutines();
    if (!fromWelcome) toast('Connected');
  } catch (e) {
    const code = (e && (e.code || e.message)) || '';
    if (code === 'LOCATION_OFF') { $('wmsg').textContent = ''; showLocationHelp(); }
    else if (code === 'PERMISSION_DENIED' && NATIVE) {
      $('wmsg').textContent = '';
      openSheet('Permission needed', 'UNIVERSE needs the "Nearby devices" permission to talk to your strip. Open settings, tap Permissions and allow it.',
        [['Open settings', 'dang', () => Strip.openAppSettings()], ['Cancel', '', null]]);
    }
    else { const m = explain(e); $('wmsg').textContent = m; if (!fromWelcome) toast(m); }
  }
  btn.disabled = false;
}
/* Only reached if the strip was never connected AND is not paired, on Android 11 or older. */
function showLocationHelp() {
  openSheet('Find your strip', 'This is only needed once. To search for a new strip, Android 11 requires Location to be on. After the first connection UNIVERSE connects directly and never asks again. Tip: pairing the strip in Bluetooth settings skips this step entirely.',
    [['Open Location settings', 'dang', () => Strip.openLocationSettings()], ['Cancel', '', null]]);
}
function chooseStrip(list) {
  return new Promise((res, rej) => openSheet('Choose your strip', 'Several strips are nearby. Pick yours.',
    list.map((d) => [d.name + '  ·  ' + d.id.slice(-5), '', () => res(d)]).concat([['Cancel', '', () => rej(err('cancel'))]])));
}
function openSheet(title, desc, buttons) {
  $('shT').textContent = title; $('shD').textContent = desc;
  const box = $('shBtns'); box.innerHTML = '';
  buttons.forEach(([label, cls, fn]) => { const b = document.createElement('button'); b.textContent = label; if (cls) b.className = cls;
    b.onclick = () => { closeSheet(); fn && fn(); }; box.appendChild(b); });
  $('sheet').classList.remove('hide');
}
$('connectBtn').onclick = () => doConnect(true);
$('skipBtn').onclick = () => $('welcome').classList.add('hide');
function closeSheet() { $('sheet').classList.add('hide'); }
$('sheet').onclick = (e) => { if (e.target.id === 'sheet') closeSheet(); };
async function endSession(msg) {
  await BLE.disconnect(); stripOn = null; setStatus(false);
  syncSession();
  toast(msg);
}
$('status').onclick = () => {
  if (!BLE.connected) return doConnect(false);
  openSheet('Connection', 'Connected to your strip.' + (NATIVE ? ' Lights keep running when you leave the app.' : ''), [
    ['Turn off and disconnect', 'dang', async () => { S.power = false; save(); paintPower(); await BLE.write(CMD.power(false)); setTimeout(() => endSession('Strip off and disconnected'), 150); }],
    ['Disconnect', '', () => endSession('Disconnected')],
    ['Cancel', '', null]
  ]);
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
window.addEventListener('popstate', () => {
  if (sub) { const s = sub; sub = null; closeSubFromBack(s); }
});
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
rtReady = true;
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
function saveRT() { localStorage.setItem('u_rt', JSON.stringify(RT)); renderRT(); syncRoutines(); }
document.querySelectorAll('[data-pre]').forEach((b) => (b.onclick = () => {
  const m = { all: [1,1,1,1,1,1,1], wkdy: [1,1,1,1,1,0,0], wknd: [0,0,0,0,0,1,1] }[b.dataset.pre];
  RT.on.days = m.slice(); RT.off.days = m.slice(); saveRT(); toast('Days updated');
}));
function syncRoutines() {
  if (NATIVE && S.lastId) Strip.setRoutines({ mac: S.lastId, on: RT.on, off: RT.off }).catch(() => {});
}
let fired = '', lastRoutineCheck = 0;
function routineCheck() {
  if (!rtReady) return;
  const nowMs = Date.now(); if (nowMs - lastRoutineCheck < 1000) return; lastRoutineCheck = nowMs;
  const d = new Date(), day = (d.getDay() + 6) % 7;
  const hm = String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0');
  ['on', 'off'].forEach((k) => {
    const r = RT[k], key = k + hm + day;
    if (r.active && r.days[day] && r.time === hm && fired !== key) { fired = key; S.power = k === 'on'; if (S.power && !S.bright) setNum('bright', 60); save(); paintPower(); toast('Routine: strip ' + k); }
  });
}

/* ---------------- toast + boot ---------------- */
function toast(m) { const t = $('toast'); t.textContent = m; t.classList.add('show'); clearTimeout(t.h); t.h = setTimeout(() => t.classList.remove('show'), 2200); }
Object.keys(NUM).forEach((k) => setNum(k, S[k])); setNum('bright', S.bright);
showColor([S.r, S.g, S.b]); paintPower(); renderRT(); setStatus(false); solid(false);
if (NATIVE) $('rtHint').textContent = 'Routines also run when the app is closed, as long as Bluetooth is on.';
requestAnimationFrame(placeHandle);
