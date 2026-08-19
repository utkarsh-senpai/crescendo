/* ============================================================================
   Crescendo v1.1 front-end — vanilla JS, no framework.
   Drives: new game → draft board → lock/reveal (you vs transparent AI) → score →
   leaderboard, against the Spring API. Keeps state in one object; renders to the
   static shell in index.html.
   ============================================================================ */
'use strict';

const API = '/api';
const state = {
  game: null,        // latest GameView
  board: null,       // DraftBoardResponse
  selected: new Set(),
};

const $ = (sel) => document.querySelector(sel);
const el = (tag, cls, html) => {
  const n = document.createElement(tag);
  if (cls) n.className = cls;
  if (html != null) n.innerHTML = html;
  return n;
};
const esc = (s) => String(s).replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmtScore = (v) => v == null ? '–' : (v * 100).toFixed(0);
const fmtPct = (v) => v == null ? '–' : (v >= 0 ? '+' : '') + (v * 100).toFixed(0) + '%';

/* ---- tiny helpers ---- */
function toast(msg) {
  const t = el('div', 'toast', esc(msg));
  document.body.appendChild(t);
  setTimeout(() => t.remove(), 3200);
}
function show(view) {
  ['home', 'board', 'reveal', 'score', 'leaderboard'].forEach((v) =>
    $('#view-' + v).classList.toggle('hide', v !== view));
  window.scrollTo({ top: 0, behavior: 'smooth' });
}
async function api(path, opts) {
  const res = await fetch(API + path, {
    headers: { 'Content-Type': 'application/json' },
    ...opts,
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || ('HTTP ' + res.status));
  return body;
}
function countUp(node, to, ms = 900) {
  const target = Number(to) || 0;
  if (matchMedia('(prefers-reduced-motion: reduce)').matches) { node.textContent = target; return; }
  const start = performance.now();
  (function tick(now) {
    const p = Math.min((now - start) / ms, 1);
    const eased = 1 - Math.pow(1 - p, 3);
    node.textContent = Math.round(target * eased);
    if (p < 1) requestAnimationFrame(tick);
  })(start);
}
function miniEq(n = 5) {
  return '<span class="eq" aria-hidden="true">' + '<span></span>'.repeat(n) + '</span>';
}

/* ---- flow: new game ---- */
async function startGame() {
  const name = ($('#playerName').value || '').trim() || 'Scout';
  $('#startBtn').disabled = true;
  try {
    state.game = await api('/games', { method: 'POST', body: JSON.stringify({ playerName: name }) });
    state.selected.clear();
    await loadBoard();
    show('board');
  } catch (e) {
    toast('Could not start game: ' + e.message);
  } finally {
    $('#startBtn').disabled = false;
  }
}

/* ---- flow: board ---- */
async function loadBoard() {
  toast('Scoring the board via /predict…');
  state.board = await api('/games/' + state.game.gameId + '/board');
  renderBoard();
}
function isInorganic(reasons) {
  return (reasons || []).some((r) => r.toLowerCase().includes('inorganic'));
}
function renderBoard() {
  const b = state.board;
  $('#capVal').textContent = b.salaryCap;
  $('#rosterSize').textContent = b.rosterSize;
  $('#rosterSize2').textContent = b.rosterSize;

  const grid = $('#boardGrid');
  grid.innerHTML = '';
  b.artists.forEach((a) => {
    const inorg = isInorganic(a.reasons);
    const card = el('article', 'card hoverable artist' + (inorg ? ' inorganic' : ''));
    card.dataset.id = a.artistId;
    const reason = (a.reasons && a.reasons[0]) || 'no signal available';
    const breakout = a.breakoutScore != null && a.breakoutScore >= 0.7 && !inorg;
    card.innerHTML = `
      <div class="top">
        <div>
          <div class="name">${esc(a.name)}</div>
          <div class="sub">${esc(a.genre)}</div>
        </div>
        ${inorg ? '<span class="pill flag">⚠ Inorganic</span>'
                : breakout ? '<span class="pill breakout">Breakout</span>' : ''}
      </div>
      <div class="score-row">
        ${miniEq(5)}
        <span class="score tnum">${fmtScore(a.breakoutScore)}</span>
        <span class="sub">momentum</span>
      </div>
      <div class="reasons">${esc(reason)}</div>
      <div class="foot">
        <span class="cost tnum">$${a.salary}</span>
        <button class="btn-add" data-add="${a.artistId}">+ Draft</button>
      </div>`;
    grid.appendChild(card);
  });
  updateSelectionUi();
}
function togglePick(id) {
  const b = state.board;
  if (state.selected.has(id)) {
    state.selected.delete(id);
  } else {
    if (state.selected.size >= b.rosterSize) { toast('Roster is full (' + b.rosterSize + ').'); return; }
    state.selected.add(id);
  }
  updateSelectionUi();
}
function updateSelectionUi() {
  const b = state.board;
  const byId = Object.fromEntries(b.artists.map((a) => [a.artistId, a]));
  let spent = 0;
  state.selected.forEach((id) => { spent += byId[id].salary; });

  document.querySelectorAll('#boardGrid .artist').forEach((card) => {
    const id = Number(card.dataset.id);
    const on = state.selected.has(id);
    card.classList.toggle('selected', on);
    const btn = card.querySelector('[data-add]');
    btn.classList.toggle('on', on);
    btn.textContent = on ? '✓ Drafted' : '+ Draft';
  });

  $('#spentVal').textContent = spent;
  $('#pickedCount').textContent = state.selected.size;
  const over = spent > b.salaryCap;
  const meter = $('#capMeter');
  meter.style.setProperty('--fill', Math.min(spent / b.salaryCap, 1).toFixed(3));
  meter.classList.toggle('over', over);

  const ready = state.selected.size === b.rosterSize && !over;
  const btn = $('#draftBtn');
  btn.disabled = !ready;
  btn.textContent = over ? 'Over the cap!'
    : state.selected.size !== b.rosterSize ? `Pick ${b.rosterSize - state.selected.size} more`
    : 'Lock roster & reveal AI';
}

/* ---- flow: draft + reveal ---- */
async function lockRoster() {
  $('#draftBtn').disabled = true;
  try {
    state.game = await api('/games/' + state.game.gameId + '/draft', {
      method: 'POST', body: JSON.stringify({ artistIds: [...state.selected] }),
    });
    renderReveal();
    show('reveal');
  } catch (e) {
    toast('Draft failed: ' + e.message);
    $('#draftBtn').disabled = false;
  }
}
function pickCard(p, withRationale) {
  const why = withRationale ? p.rationale : ((p.draftReasons && p.draftReasons[0]) || '');
  return `<div class="pick">
      <div class="pn"><b>${esc(p.name)}</b><span class="tnum muted">$${p.salaryPaid} · ${fmtScore(p.draftBreakoutScore)}</span></div>
      ${why ? `<div class="why">${esc(why)}</div>` : ''}
    </div>`;
}
function renderReveal() {
  const g = state.game;
  $('#youName').textContent = g.playerName;
  $('#youSpent').textContent = g.salarySpent;
  $('#youPicks').innerHTML = g.roster.map((p) => pickCard(p, false)).join('');

  const ai = g.opponent;
  if (ai) {
    $('#aiSpent').textContent = ai.salarySpent;
    $('#aiPicks').innerHTML = ai.roster.map((p) => pickCard(p, true)).join('');
    $('#aiSnubs').innerHTML = (ai.snubs && ai.snubs.length)
      ? '<div class="sub" style="margin-bottom:.35rem">Passed on</div>' +
        ai.snubs.map((s) => `<div class="s"><b>${esc(s.name)}</b> — ${esc(s.reason)}</div>`).join('')
      : '';
  }
}

/* ---- flow: score ---- */
async function scoreGame() {
  $('#scoreBtn').disabled = true;
  try {
    state.game = await api('/games/' + state.game.gameId + '/score', {
      method: 'POST', body: JSON.stringify({}),
    });
    renderScore();
    show('score');
  } catch (e) {
    toast('Scoring failed: ' + e.message);
  } finally {
    $('#scoreBtn').disabled = false;
  }
}
function renderScore() {
  const g = state.game;
  countUp($('#youScore'), fmtScore(g.playerScore));
  countUp($('#aiScore'), fmtScore(g.opponent ? g.opponent.score : 0));
  const v = $('#verdict');
  const map = {
    PLAYER_WINS: ['win', `You beat the AI 🎉`],
    AI_WINS: ['lose', `The AI edged you out`],
    TIE: ['tie', `Dead heat`],
  };
  const [cls, text] = map[g.outcome] || ['tie', ''];
  v.className = 'verdict ' + cls;
  v.textContent = text;
}

/* ---- flow: leaderboard ---- */
async function loadLeaderboard() {
  try {
    const rows = await api('/leaderboard');
    const lb = $('#lb');
    lb.innerHTML = '';
    $('#lbEmpty').classList.toggle('hide', rows.length > 0);
    const myId = state.game && state.game.gameId;
    rows.forEach((r, i) => {
      const row = el('div', 'lb-row' + (r.gameId === myId ? ' me' : ''));
      row.innerHTML = `
        <div class="lb-rank">${i + 1}</div>
        <div><b>${esc(r.playerName)}</b> <span class="dim">· ${r.rosterSize} picks</span></div>
        <div class="lb-score tnum">${fmtScore(r.playerScore)}</div>`;
      lb.appendChild(row);
    });
    show('leaderboard');
  } catch (e) {
    toast('Could not load leaderboard: ' + e.message);
  }
}

/* ---- wiring ---- */
function nav(view) {
  if (view === 'home') { show('home'); }
  else if (view === 'leaderboard') { loadLeaderboard(); }
  else { show(view); }
}
document.addEventListener('click', (e) => {
  const add = e.target.closest('[data-add]');
  if (add) { togglePick(Number(add.dataset.add)); return; }
  const navBtn = e.target.closest('[data-nav]');
  if (navBtn) { nav(navBtn.dataset.nav); return; }
});
$('#startBtn').addEventListener('click', startGame);
$('#playerName').addEventListener('keydown', (e) => { if (e.key === 'Enter') startGame(); });
$('#toLeaderboard').addEventListener('click', () => loadLeaderboard());
$('#draftBtn').addEventListener('click', lockRoster);
$('#scoreBtn').addEventListener('click', scoreGame);

/* ============================================================================
   Ambient "spores" — a lightweight full-page canvas of drifting, rising,
   twinkling bioluminescent motes (the organic-growth motif). Cheap by design:
   one pre-rendered sprite drawn per particle, count scaled to viewport area and
   capped, paused when the tab is hidden, and reduced to a faint static field
   when the user prefers reduced motion.
   ============================================================================ */
(function ambient() {
  const canvas = document.getElementById('ambient');
  if (!canvas || !canvas.getContext) return;
  const ctx = canvas.getContext('2d', { alpha: true });
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;

  let W = 0, H = 0, dpr = 1, particles = [], raf = 0, last = 0;

  // Pre-render the glow once into an offscreen sprite so per-frame draws are just blits.
  const SPRITE = 64;
  const sprite = document.createElement('canvas');
  sprite.width = sprite.height = SPRITE;
  const sctx = sprite.getContext('2d');
  const g = sctx.createRadialGradient(SPRITE / 2, SPRITE / 2, 0, SPRITE / 2, SPRITE / 2, SPRITE / 2);
  g.addColorStop(0, 'rgba(180,255,200,1)');
  g.addColorStop(0.35, 'rgba(120,240,170,0.55)');
  g.addColorStop(1, 'rgba(80,220,150,0)');
  sctx.fillStyle = g;
  sctx.fillRect(0, 0, SPRITE, SPRITE);

  const rand = (a, b) => a + Math.random() * (b - a);

  function makeParticle(seedY) {
    const r = rand(1.1, 3.6);
    return {
      x: Math.random() * W,
      y: seedY == null ? Math.random() * H : seedY,
      r,
      vy: -rand(4, 16),                 // rise (px/s)
      sway: rand(6, 22),                // horizontal sway amplitude
      phase: Math.random() * Math.PI * 2,
      swaySpeed: rand(0.15, 0.5),
      baseAlpha: rand(0.15, 0.6),
      twinkle: rand(0.4, 1.2),
    };
  }

  function resize() {
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = window.innerWidth; H = window.innerHeight;
    canvas.width = Math.floor(W * dpr);
    canvas.height = Math.floor(H * dpr);
    canvas.style.width = W + 'px';
    canvas.style.height = H + 'px';
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    // ~1 mote / 14k px², capped — dense enough to read as a field, cheap enough to be free.
    const target = Math.min(120, Math.round((W * H) / 14000));
    particles = Array.from({ length: target }, () => makeParticle());
  }

  function drawMote(p, t) {
    const a = reduce
      ? p.baseAlpha * 0.5
      : p.baseAlpha * (0.6 + 0.4 * Math.sin(t * p.twinkle + p.phase));
    const x = p.x + (reduce ? 0 : Math.sin(t * p.swaySpeed + p.phase) * p.sway);
    const d = p.r * 6;
    ctx.globalAlpha = Math.max(0, a);
    ctx.drawImage(sprite, x - d / 2, p.y - d / 2, d, d);
  }

  function frame(now) {
    const t = now / 1000;
    const dt = last ? Math.min((now - last) / 1000, 0.05) : 0;
    last = now;
    ctx.clearRect(0, 0, W, H);
    for (const p of particles) {
      p.y += p.vy * dt;
      if (p.y < -20) { p.y = H + 20; p.x = Math.random() * W; }   // wrap to bottom
      drawMote(p, t);
    }
    ctx.globalAlpha = 1;
    raf = requestAnimationFrame(frame);
  }

  function start() { if (!raf) { last = 0; raf = requestAnimationFrame(frame); } }
  function stop() { if (raf) { cancelAnimationFrame(raf); raf = 0; } }

  resize();
  window.addEventListener('resize', resize, { passive: true });

  if (reduce) {
    // One static, faint pass — decorative, no animation loop.
    ctx.clearRect(0, 0, W, H);
    const t = 0;
    for (const p of particles) drawMote(p, t);
    ctx.globalAlpha = 1;
  } else {
    document.addEventListener('visibilitychange', () => (document.hidden ? stop() : start()));
    start();
  }
})();

/* ---- service worker (installable, offline shell) ---- */
if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => { /* non-fatal */ });
  });
}
