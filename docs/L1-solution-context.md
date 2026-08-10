# Crescendo — L1 Solution Context

> **L1 = the board-level view.** *What* we're building and *why*, who uses it, what it
> connects to, the key constraints, and — just as important — what is **out of scope**.
> No code, no service topology (that's L2), no schemas (that's L3).

- **Status:** Draft v0.1
- **Owner:** utkarsh-senpai
- **Author persona reviewing this:** "Sam" — Staff ML Engineer / hiring manager, portfolio coach
- **Date:** 2026-08-04 · **Revised:** 2026-08-10 (after competitive + ML research pass — see §11)

---

## 1. One-line pitch

**Crescendo** is a **free, non-gambling, non-NFT** consumer game where players draft emerging
artists under a salary cap and score on the artists' **real-world momentum (relative
growth)** — competing against a **transparent AI opponent** whose picks *and reasoning* are
visible. The engine underneath is a **leakage-safe breakout-prediction model trained on a
self-collected YouTube time-series**.

**Two non-negotiable framings (owner directive, 2026-08-10):** this is a **learning &
uniqueness** project, not a revenue play — and it must be **$0 to build and $0 to run** (free
tiers only, no paid infra, no app-store fees). Both shape every downstream decision: they
*rule out* the paid-cash-prize lane every competitor has chosen (see §11) and *rule in* a
free, browser-installable delivery on **desktop + Android** (§7).

## 2. Why this exists (the goal behind the goal)

This is a **portfolio / resume project**. Its job is to prove one crisp engineering story
in interviews, not to be a shippable startup. The headline claim to defend:

> *"Is emerging-artist breakout predictable from momentum features on a self-collected
> YouTube time-series — and can I prove it with a leakage-safe evaluation before building
> any game around it?"*

Everything else (draft, leaderboard, AI opponent) is the **product wrapper** that makes
the ML tangible and demoable.

## 3. Novelty — what is and isn't defensible

| Layer | Not novel (be honest) | Crescendo's defensible edge |
|---|---|---|
| **Product** | Fantasy-music games exist (Vault, FanLabel) | The *combination*: consumer breakout game + a **transparent AI opponent you play against** + roster drafting, **free / non-NFT / non-gambling**. The 2026 research (§11) shows every serious rival ran the *opposite* way — into **paid cash-prize song-picking** — vacating exactly this lane. |
| **ML** | "Predict who blows up" is a known A&R problem | Done as **leakage-aware time-series forecasting on data collected ourselves** — **artist-level momentum, no audio features** — a clean gap vs. mature audio-based "Hit Song Science" (§11), which the Spotify API wall forces and the AI-music flood makes newly interesting. The constraint *is* the story. |
| **AI-opponent** | Human-vs-AI games exist (chess, *Deviation Game*) | **No one applies a *reason-showing* AI opponent to music prediction.** The AI both plays and *explains each pick* from the same model the scoring uses → "it plays by the rules it shows you." This is the **headline creative + learning bet** (elevated from a UX detail). |
| **Engineering** | CRUD game backends are common | A real **two-language system** (Python ML/ingestion + Spring Boot game backend + Postgres) with **temporal-split, precision@k** evaluation over a **live self-collected pipeline**, shipped **$0 as an installable PWA** on desktop + Android (§7). |

## 4. Scope

### 4.1 MVP scope (this iteration) — **Modeling spike only**
Prove *"is breakout predictable?"* on **offline, self-collected** data with a **leakage-safe
evaluation**, before building any game infrastructure.

- Ingest a YouTube time-series for a discovered set of emerging artists.
- Engineer momentum features (growth rate, acceleration, consistency over rolling windows).
- Define the **prediction target = relative growth rate** over a forward window.
- Train a baseline model (LightGBM/XGBoost); evaluate with **temporal split + precision@k**
  against a **base-rate baseline**.
- Deliverable: a notebook/report answering "predictable? by how much over base rate?"

### 4.2 Later scope (future iterations, NOT now)
Game backend (users/auth, salary-cap draft, scoring engine, leaderboard) · minimal UI ·
the transparent AI opponent · live/continuous scoring.

### 4.3 Explicitly OUT of scope
- Real-money betting / gambling mechanics **and paid-entry cash-prize contests** (the entire
  competitor lane — §11 — plus regulatory + financial cost we refuse).
- NFT / on-chain ownership mechanics.
- Spotify extended API (org-only; unavailable to individuals).
- Real-time multiplayer / WebSockets.
- **Native mobile apps & the Apple App Store** — no paid developer accounts; ship a PWA (§7).
- Song-ranking / prediction-market contracts (FanLabel patent-avoidance, §11).
- **Any paid infrastructure** — if a component can't run on a free tier, it's redesigned or deferred.

## 5. Actors

| Actor | Type | Interacts with | Notes |
|---|---|---|---|
| **Player** | Human | Game backend (later) | Drafts roster, sees scores/leaderboard. *Not exercised in MVP spike.* |
| **AI Opponent** | System (bot) | Same prediction model | Transparent picks + reasoning. *Later scope.* |
| **Data Collector** | System (cron) | YouTube Data API, Postgres | Builds the mandatory self-collected history. **Core of MVP.** |
| **Modeler** | Human (you) | Offline data + notebook | Runs the spike; the primary "user" of the MVP. |

## 6. System context (what connects to what)

See the Mermaid diagram in [`L1-system-context.md`](./L1-system-context.md) (renders
natively on GitHub). External dependency in the MVP is **only** the YouTube Data API v3.

## 7. Key non-functional constraints (L1-level)

- **Self-collected history is mandatory** — snapshot APIs give no history; momentum must be
  computed from data we store over time. This shapes the whole architecture.
- **Leakage-safe evaluation** — temporal split only (never random); features must use only
  information available at prediction time.
- **Individual-buildable** — no org-tier API access; must live within YouTube Data API v3
  quota (**10k units/day**, `search.list` = 100 units → quota budgeting required).
- **Legal posture** — non-gambling, non-NFT, **no paid entry / no cash prizes**; framed as
  fan-engagement / discovery. This is now also a *competitive* choice (§11): it sidesteps the
  DFS-style state-by-state regulation (rivals are geo-blocked in 6+ US states) and the
  FanLabel prediction-market patents at zero legal/financial cost.
- **Zero-cost mandate ($0 build + $0 run)** — free tiers only. No paid hosting, no managed DB
  bill, **no app-store developer fees**. Delivery = an **installable PWA** (one web build)
  that runs on **desktop browsers and Android**; **iOS/App Store is explicitly out of scope**
  (the $99/yr Apple fee has no place in a free personal project, and PWA install on iOS is
  degraded anyway). See the delivery-target row in §9 and L3 infra.
- **Reproducibility** — deterministic pipeline; Python 3.12 via `uv` (system is 3.14, too
  new for some ML wheels).

## 8. Success criteria for the MVP spike

1. A stored, growing YouTube time-series for a discovered artist set.
2. A documented **prediction target** (relative growth rate) with an explicit forward window.
3. A model whose **precision@k beats the base-rate baseline** on a temporal holdout — *or* a
   clear, honest negative result explaining why not (both are valid interview stories).
4. A written report of features, eval methodology, leakage guards, and findings.

## 9. Resolved design decisions (locked at L1)

These were L1 open questions; now decided. They shape the MVP dataset and target.

| Decision | Resolution | Rationale |
|---|---|---|
| **Artist scope** | **One genre, global** — default **electronic / EDM** (swappable) | Coherent audience behavior → cleaner momentum signal; bounded, quota-friendly dataset; defensible "I scoped a vertical to prove the method" story. |
| **"Emerging" band (at entry)** | **1,000 – 100,000 subscribers** | Enough activity/history to model, genuine headroom to break out, filters out dead/inactive channels. "Broke out" later = crosses well above the band. |
| **Prediction target** | **30-day forward relative growth**; label **breakout = top-decile (top 10%) of the cohort** | 30d smooths daily noise; cohort-relative is fair across artist sizes; top-decile gives a clean positive class for **precision@k** on a temporal holdout. |
| **Discovery strategy (under quota)** | **Seed + snowball**: seed from curated public playlists → resolve channel IDs; expand via cheap `channels.list` / `playlistItems` (**1 unit**) instead of `search.list` (**100 units**); reserve daily quota for **snapshotting** the tracked set; snowball via related/featured artists | Removed discovery endpoints don't block us; keeps us well inside 10k units/day; demonstrates deliberate quota budgeting. **Vindicated by 2026 research (§11):** since 2026-06-01 `search.list` has a *separate ~100-call/day bucket* — avoiding it isn't just cheap, it's now capacity-capped. |
| **Delivery target (NEW, 2026-08-10)** | **Installable PWA** — one web build, installable on **desktop browsers + Android**; **iOS/App Store out of scope** | Satisfies the $0 mandate: no store fees, no native build pipeline, no paid signing. One codebase covers the two platforms that matter for a free personal project; the transparent-AI game UI is web-native anyway. |

## 10. Remaining questions (defer to L2/L3, not blocking L1)

- **Collection cadence** & exact metrics to snapshot (daily? views, subs, video count, velocity) → L2/L3.
- **Cold-start**: how much history before the model is trainable → resolved empirically in the spike.

---

## 11. Research pass (2026-08-10) — what changed the positioning

A competitive + ML-literature research pass (web, Aug 2026). Findings **sharpened** the plan;
none broke it. Full angle-by-angle notes live in [`research-2026-08.md`](./research-2026-08.md);
the load-bearing conclusions:

**A. The competitive field vacated our lane by running toward paid cash prizes.**
- **FanLabel** (holder of the 5 gamification/prediction-market patents) launched **"FanLabel
  SongPicks"** — a **paid-entry, skill-based, cash-prize** contest app — and now calls itself a
  *"music gamification and **prediction market** entertainment company."* Modes are song-level
  (*Ranker*, *Best of Five*).
- **SongPicks.com**: cash entry from $1, "fantasy sports for music," **geo-blocked in 6 US
  states** (AZ, IA, LA, MI, SC, WA) — the DFS/gambling regulatory footprint.
- **Vault "Fantasy Record Label"** (FanDuel founders): weekly cash prizes.
- **Implication:** every serious rival is now *song-level, paid, patent-fenced, gambling-
  regulated.* Crescendo's **free / artist-level / relative-growth / non-gambling** lane is
  **more** open than when first scoped — and aligns perfectly with the owner's $0, learning-
  first mandate. **We do not compete on prizes; we compete on the AI and the method.**

**B. Transparent AI opponent is genuinely unclaimed in music.**
- Human-vs-AI as entertainment is proven (*Deviation Game* on Steam; a 2026 scoping review /
  meta-analysis on player enjoyment competing vs. AI). **But none apply a *reason-showing* AI
  to music prediction.** → We **elevate the transparent AI opponent to a headline** (§3), not a
  footnote: the AI ranks *and explains* using the same model that scores the game.

**C. The ML frontier moved — our framing lands in a clean gap.**
- "Hit Song Science" (DeepHits, APEX, 2026) is mature but centered on **pre-release *audio*
  features of already-released commercial songs**. The hot new problem is the **AI-generated
  music flood** (Suno/Udio: ~12M→100M users in ~a year) where reputation signals are absent.
- Crescendo's **no-audio, artist-level, self-collected momentum** approach is *orthogonal* to
  the audio-feature canon → defensibly novel, and free (no audio-model compute).
- **New design thread (creative, $0):** treat the AI-music flood as **both a breakout signal
  and an inorganic-growth data-quality risk** (bot/synthetic spikes). Pure analysis — costs
  nothing, adds a distinctive "I modeled data integrity in the wild" story. Detailed in L2/L3.

**D. The YouTube API wall is real and tightening — our strategy is vindicated.**
- Still free, 10k units/day, resets midnight PT. **Since 2026-06-01, `search.list` has its own
  ~100-call/day bucket.** Our seed+snowball "avoid `search.list`" design is now not merely
  cheap but the only way to scale discovery for free → reinforces the $0 mandate.
