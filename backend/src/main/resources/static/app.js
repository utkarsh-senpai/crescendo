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
  leagues: [],       // [{id,label,band,tagline}] from /api/leagues
  league: null,      // chosen league id (e.g. "POP")
  replayDate: null,  // v1.7: ISO date string for replay mode, or null
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

/* ---- v1.7: replay mode toggle ---- */
(function initReplayToggle() {
  const toggle = document.getElementById('replayToggle');
  const panel = document.getElementById('replayPanel');
  const input = document.getElementById('replayDate');
  if (!toggle || !panel || !input) return;

  // Default max = today (can't replay the future)
  const today = new Date().toISOString().slice(0, 10);
  input.max = today;

  toggle.addEventListener('click', () => {
    const expanded = toggle.getAttribute('aria-expanded') === 'true';
    toggle.setAttribute('aria-expanded', String(!expanded));
    panel.classList.toggle('hide', expanded);
    if (!expanded) {
      input.focus();
    } else {
      // Collapsing clears the replay date
      input.value = '';
      state.replayDate = null;
    }
  });

  input.addEventListener('change', () => {
    state.replayDate = input.value || null;
  });
})();

/* ---- flow: new game ---- */
async function startGame() {
  const name = ($('#playerName').value || '').trim() || 'Scout';
  const btn = $('#startBtn');
  btn.disabled = true;
  const origText = btn.textContent;
  // On the free tier the API + model can be cold-starting; escalate the message so a first-time
  // visitor sees progress instead of a dead button.
  btn.textContent = 'Warming up…';
  const slow = setTimeout(() => { btn.textContent = 'Waking the model up…'; }, 4000);
  const slower = setTimeout(() => { btn.textContent = 'Almost there (free-tier cold start)…'; }, 12000);
  try {
    const body = { playerName: name, league: state.league || 'POP' };
    if (state.replayDate) body.replayDate = state.replayDate;
    state.game = await api('/games', {
      method: 'POST',
      body: JSON.stringify(body),
    });
    state.selected.clear();
    await loadBoard();
    show('board');
  } catch (e) {
    toast('Could not start game: ' + e.message);
  } finally {
    clearTimeout(slow); clearTimeout(slower);
    btn.disabled = false;
    btn.textContent = origText;
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
function leagueLabel(id) {
  const l = state.leagues.find((x) => x.id === id);
  return l ? l.label : (id || 'Pop');
}
function renderBoard() {
  const b = state.board;
  $('#capVal').textContent = b.salaryCap;
  $('#rosterSize').textContent = b.rosterSize;
  $('#rosterSize2').textContent = b.rosterSize;
  const bl = $('#boardLeague');
  if (bl) bl.textContent = leagueLabel(b.league);

  // v1.7: replay mode banner
  const replayBanner = $('#replayBanner');
  if (replayBanner) {
    if (b.isReplayMode && b.replayDate) {
      replayBanner.textContent = 'REPLAY: Drafting as of ' + b.replayDate + ' — see what happened';
      replayBanner.classList.remove('hide');
    } else {
      replayBanner.classList.add('hide');
    }
  }

  const grid = $('#boardGrid');
  grid.innerHTML = '';
  b.artists.forEach((a) => {
    const inorg = isInorganic(a.reasons);
    const card = el('article', 'card hoverable artist' + (inorg ? ' inorganic' : ''));
    card.dataset.id = a.artistId;
    const reason = (a.reasons && a.reasons[0]) || 'no signal available';
    const breakout = a.breakoutScore != null && a.breakoutScore >= 0.7 && !inorg;

    // v1.5: discovery edge badge — shows how much above cohort median this pick is
    const edge = a.discoveryEdge;
    const edgeBadge = (() => {
      if (inorg || edge == null) return '';
      const pct = (edge * 100).toFixed(1);
      const sign = edge >= 0 ? '+' : '';
      const cls = edge >= 0.05 ? 'edge-high' : edge >= 0 ? 'edge-mid' : 'edge-low';
      return `<span class="pill edge ${cls}" title="Discovery Edge: how far above cohort average">${sign}${pct}% edge</span>`;
    })();

    // v1.5: confidence tier badge
    const tier = a.confidenceTier;
    const tierBadge = (() => {
      if (!tier || inorg) return '';
      const cls = tier === 'HIGH' ? 'conf-high' : tier === 'MEDIUM' ? 'conf-mid' : 'conf-low';
      const label = tier === 'HIGH' ? 'High conf' : tier === 'MEDIUM' ? 'Mid conf' : 'Uncertain';
      return `<span class="pill conf ${cls}" title="Model confidence tier (v1.7 will show prediction intervals)">${label}</span>`;
    })();

    // v1.7: prediction interval bar
    const intervalBar = (() => {
      const lo = a.predictionIntervalLo;
      const hi = a.predictionIntervalHi;
      if (lo == null || hi == null || inorg) return '';
      const loPct = (lo * 100).toFixed(0);
      const hiPct = (hi * 100).toFixed(0);
      const loSign = lo >= 0 ? '+' : '';
      const hiSign = hi >= 0 ? '+' : '';
      return `<div class="interval-bar"><span class="interval-range tnum">${loSign}${loPct}% → ${hiSign}${hiPct}%</span></div>`;
    })();

    card.innerHTML = `
      <div class="top">
        <div>
          <div class="name">${esc(a.name)}</div>
          <div class="sub">${esc(a.genre)}</div>
        </div>
        <div class="badges">
          ${inorg ? '<span class="pill flag">⚠ Inorganic</span>'
                  : breakout ? '<span class="pill breakout">Breakout</span>' : ''}
          ${edgeBadge}
        </div>
      </div>
      <div class="score-row">
        ${miniEq(5)}
        <span class="score tnum">${fmtScore(a.breakoutScore)}</span>
        <span class="sub">momentum</span>
        ${tierBadge}
      </div>
      ${intervalBar}
      <div class="reasons">${esc(reason)}</div>
      <div class="live" data-live="${a.artistId}"></div>
      <div class="foot">
        <span class="cost tnum">$${a.salary}</span>
        <button class="btn-add" data-add="${a.artistId}">+ Draft</button>
      </div>`;
    grid.appendChild(card);
  });
  updateSelectionUi();
  loadLiveStats();
}

/* ---- real-time YouTube stats (v1.4): enrich each card with live subs/views ---- */
const fmtCompact = (n) => {
  const x = Number(n) || 0;
  if (x >= 1e9) return (x / 1e9).toFixed(1).replace(/\.0$/, '') + 'B';
  if (x >= 1e6) return (x / 1e6).toFixed(1).replace(/\.0$/, '') + 'M';
  if (x >= 1e3) return (x / 1e3).toFixed(1).replace(/\.0$/, '') + 'K';
  return String(x);
};
async function loadLiveStats() {
  if (!state.game) return;
  let data;
  try {
    data = await api('/live/board/' + state.game.gameId);
  } catch { return; }              // best-effort; board still works without it
  if (!data || !data.enabled || !data.artists) return;
  const byId = Object.fromEntries(data.artists.map((a) => [a.artistId, a]));
  document.querySelectorAll('#boardGrid .live').forEach((slot) => {
    const s = byId[Number(slot.dataset.live)];
    if (!s) return;
    slot.innerHTML =
      `<span class="live-dot" aria-hidden="true"></span>` +
      `<span class="live-stat">${fmtCompact(s.subscribers)} subs</span>` +
      `<span class="live-stat dim">${fmtCompact(s.views)} views</span>`;
    if (s.summary) slot.title = s.summary;
  });
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
    PLAYER_WINS: ['win', `You beat the AI`],
    AI_WINS: ['lose', `The AI edged you out`],
    TIE: ['tie', `Dead heat`],
  };
  const [cls, text] = map[g.outcome] || ['tie', ''];
  v.className = 'verdict ' + cls;
  v.textContent = text;

  // v1.7: replay discovery line
  const replayDiscover = $('#replayDiscover');
  if (replayDiscover) {
    if (g.isReplayMode && g.replayDate && g.roster && g.roster.length) {
      const byGrowth = [...g.roster].sort((a, b) => (b.realisedGrowth30d || 0) - (a.realisedGrowth30d || 0));
      const standout = byGrowth[0];
      const endDate = g.scoreAsOfDate || (g.replayDate + ' (+30 days)');
      replayDiscover.textContent = 'You discovered ' + standout.name + ' in the +30 day window ending ' + endDate;
      replayDiscover.classList.remove('hide');
    } else {
      replayDiscover.classList.add('hide');
    }
  }

  renderReasoning(g);
}

/* ---- post-results "why you won/lost" breakdown (computed from both rosters) ---- */
function renderReasoning(g) {
  const host = $('#reasoning');
  if (!host) return;
  const you = (g.roster || []).map((p) => ({ name: p.name, g: p.realisedGrowth30d || 0, salary: p.salaryPaid }));
  const ai = (g.opponent && g.opponent.roster ? g.opponent.roster : [])
    .map((p) => ({ name: p.name, g: p.realisedGrowth30d || 0, salary: p.salaryPaid }));
  if (!you.length || !ai.length) { host.innerHTML = ''; return; }

  const mean = (arr) => arr.reduce((s, x) => s + x.g, 0) / (arr.length || 1);
  const youMean = mean(you), aiMean = mean(ai);
  const margin = Math.abs(youMean - aiMean) * 100;
  const won = g.outcome === 'PLAYER_WINS', tie = g.outcome === 'TIE';

  const byGrowthDesc = (a, b) => b.g - a.g;
  const yourBest = [...you].sort(byGrowthDesc)[0];
  const yourWorst = [...you].sort(byGrowthDesc)[you.length - 1];
  // High-momentum artists the AI rostered that you didn't pick.
  const yourNames = new Set(you.map((x) => x.name));
  const missed = ai.filter((x) => !yourNames.has(x.name)).sort(byGrowthDesc).slice(0, 2);

  // A small stacked bar comparing per-pick realised growth, you vs AI.
  const maxG = Math.max(...you.map((x) => x.g), ...ai.map((x) => x.g), 0.001);
  const bars = (arr, klass) => arr.map((x) =>
    `<div class="rb-row"><span class="rb-name">${esc(x.name)}</span>
       <span class="rb-track"><span class="rb-fill ${klass}" style="width:${Math.max(3, (x.g / maxG) * 100).toFixed(0)}%"></span></span>
       <span class="rb-val tnum">${fmtPct(x.g)}</span></div>`).join('');

  const headline = tie
    ? `A dead heat — your picks averaged the same realised growth as the AI.`
    : won
      ? `You won by <b>${margin.toFixed(0)}</b> — your roster's average realised growth beat the AI's.`
      : `The AI won by <b>${margin.toFixed(0)}</b> — its picks averaged higher realised growth than yours.`;

  const insight = tie ? '' : won
    ? `<li>Your standout was <b>${esc(yourBest.name)}</b> (${fmtPct(yourBest.g)} realised).</li>`
    : (missed.length
        ? `<li>The AI backed <b>${missed.map((m) => esc(m.name) + ' (' + fmtPct(m.g) + ')').join('</b>, <b>')}</b> — high-momentum picks you passed on.</li>`
        : '') +
      `<li>Your weakest slot was <b>${esc(yourWorst.name)}</b> (${fmtPct(yourWorst.g)}) — a pick with less momentum for the money.</li>`;

  host.innerHTML = `
    <div class="reasoning-card card">
      <div class="eyebrow">Why</div>
      <p class="reasoning-headline">${headline}</p>
      <ul class="reasoning-points">
        ${insight}
        <li>Scored on <b>mean realised momentum</b> — 5 picks, averaged (size doesn't help).</li>
      </ul>
      <div class="rb-legend"><span class="rb-key you"></span>You <span class="rb-key ai"></span>Crescendo AI</div>
      <div class="rb-cols">
        <div class="rb-col"><div class="sub">Your picks</div>${bars(you, 'you')}</div>
        <div class="rb-col"><div class="sub">AI picks</div>${bars(ai, 'ai')}</div>
      </div>
    </div>`;
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
/* ---- league picker (one pool per game, no cross-select) ---- */
async function loadLeagues() {
  const host = $('#leaguePicker');
  if (!host) return;
  try {
    state.leagues = await api('/leagues');
  } catch {
    // Fallback so the home screen is still playable if /leagues is unreachable.
    state.leagues = [{ id: 'POP', label: 'Pop', band: 'Global pop superstars', tagline: '' }];
  }
  state.league = state.league || (state.leagues[0] && state.leagues[0].id) || 'POP';
  host.innerHTML = state.leagues.map((l) => `
    <button type="button" class="league-card${l.id === state.league ? ' on' : ''}"
            role="radio" aria-checked="${l.id === state.league}" data-league="${esc(l.id)}">
      <span class="lc-label">${esc(l.label)}</span>
      <span class="lc-band">${esc(l.band)}</span>
      <span class="lc-tag">${esc(l.tagline)}</span>
    </button>`).join('');
}
function selectLeague(id) {
  state.league = id;
  document.querySelectorAll('#leaguePicker .league-card').forEach((c) => {
    const on = c.dataset.league === id;
    c.classList.toggle('on', on);
    c.setAttribute('aria-checked', on ? 'true' : 'false');
  });
}
document.addEventListener('click', (e) => {
  const card = e.target.closest('[data-league]');
  if (card) { selectLeague(card.dataset.league); }
});

$('#startBtn').addEventListener('click', startGame);
$('#playerName').addEventListener('keydown', (e) => { if (e.key === 'Enter') startGame(); });
$('#toLeaderboard').addEventListener('click', () => loadLeaderboard());
$('#draftBtn').addEventListener('click', lockRoster);
$('#scoreBtn').addEventListener('click', scoreGame);

/* ============================================================================
   v1.2 — modals (how-to-play + feedback), curated "started small" showcase,
   feedback submit. All vanilla, no deps.
   ============================================================================ */
let lastFocused = null;
function openModal(name) {
  const m = $('#modal-' + name);
  if (!m) return;
  lastFocused = document.activeElement;
  m.classList.remove('hide');
  document.body.style.overflow = 'hidden';
  if (name === 'feedback') refreshFeedbackCount();
  const focusable = m.querySelector('textarea, input, button:not(.modal-close)');
  if (focusable) focusable.focus();
}
function closeModal(m) {
  m.classList.add('hide');
  document.body.style.overflow = '';
  if (lastFocused && lastFocused.focus) lastFocused.focus();
}
document.addEventListener('click', (e) => {
  const opener = e.target.closest('[data-modal]');
  if (opener) { openModal(opener.dataset.modal); return; }
  const closer = e.target.closest('[data-close]');
  if (closer) { closeModal(closer.closest('.modal-backdrop')); return; }
  // Click on the dimmed backdrop (not the card) closes the modal.
  if (e.target.classList && e.target.classList.contains('modal-backdrop')) { closeModal(e.target); return; }
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape') {
    const open = document.querySelector('.modal-backdrop:not(.hide)');
    if (open) closeModal(open);
  }
});


/* ---- feedback form ---- */
let fbRating = null;
document.querySelectorAll('#starRating .star').forEach((btn) => {
  btn.addEventListener('click', () => {
    fbRating = Number(btn.dataset.star);
    document.querySelectorAll('#starRating .star').forEach((s) =>
      s.classList.toggle('on', Number(s.dataset.star) <= fbRating));
  });
});
async function refreshFeedbackCount() {
  try {
    const { count } = await api('/feedback/count');
    $('#fbCount').textContent = count > 0 ? `${count} note${count === 1 ? '' : 's'} so far` : '';
  } catch { /* non-fatal */ }
}
$('#feedbackForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const message = ($('#fbMessage').value || '').trim();
  if (!message) { toast('Please add a short note.'); $('#fbMessage').focus(); return; }
  const name = ($('#fbName').value || '').trim();
  const btn = $('#fbSubmit');
  btn.disabled = true;
  try {
    await api('/feedback', {
      method: 'POST',
      body: JSON.stringify({ rating: fbRating, message, name: name || null }),
    });
    closeModal($('#modal-feedback'));
    toast('Thank you — feedback received! 🙏');
    // reset
    $('#fbMessage').value = ''; $('#fbName').value = ''; fbRating = null;
    document.querySelectorAll('#starRating .star').forEach((s) => s.classList.remove('on'));
  } catch (err) {
    toast('Could not send: ' + err.message);
  } finally {
    btn.disabled = false;
  }
});

/* ---- populate the league picker on load ---- */
loadLeagues();

/* ---- first-time visitors: auto-open How to Play once ---- */
try {
  if (!localStorage.getItem('crescendo.seenHowTo')) {
    openModal('howto');
    localStorage.setItem('crescendo.seenHowTo', '1');
  }
} catch { /* localStorage may be unavailable; skip */ }

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
