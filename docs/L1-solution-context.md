# Crescendo — L1 Solution Context

> **L1 = the board-level view.** *What* we're building and *why*, who uses it, what it
> connects to, the key constraints, and — just as important — what is **out of scope**.
> No code, no service topology (that's L2), no schemas (that's L3).

- **Status:** Draft v0.1
- **Owner:** utkarsh-senpai
- **Author persona reviewing this:** "Sam" — Staff ML Engineer / hiring manager, portfolio coach
- **Date:** 2026-08-04

---

## 1. One-line pitch

**Crescendo** is a non-gambling, non-NFT consumer game where players draft emerging
artists under a salary cap and score on the artists' **real-world momentum (relative
growth)** — competing against a **transparent AI opponent** whose picks and reasoning are
visible. The engine underneath is a **leakage-safe breakout-prediction model trained on a
self-collected YouTube time-series**.

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
| **Product** | Fantasy-music games exist (Vault, FanLabel) | The *combination*: consumer breakout game + a **transparent AI opponent you play against** + roster drafting, **non-NFT / non-gambling**. FanLabel holds 5 patents → we design *around* song-ranking / prediction-market mechanics. |
| **ML** | "Predict who blows up" is a known A&R problem | Done as **leakage-aware time-series forecasting on data collected ourselves**, because the Spotify API wall (Feb 2026 removed follower/popularity + discovery for dev mode) forces it. The constraint *is* the story. |
| **Engineering** | CRUD game backends are common | A real **two-language system** (Python ML/ingestion + Spring Boot game backend + Postgres) with **temporal-split, precision@k** evaluation over a **live self-collected pipeline**. |

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
- Real-money betting / gambling mechanics.
- NFT / on-chain ownership mechanics.
- Spotify extended API (org-only; unavailable to individuals).
- Real-time multiplayer / WebSockets.
- Mobile-native apps.
- Song-ranking / prediction-market contracts (patent-avoidance).

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
- **Legal posture** — non-gambling, non-NFT; framed as fan-engagement / discovery.
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
| **Discovery strategy (under quota)** | **Seed + snowball**: seed from curated public playlists → resolve channel IDs; expand via cheap `channels.list` / `playlistItems` (**1 unit**) instead of `search.list` (**100 units**); reserve daily quota for **snapshotting** the tracked set; snowball via related/featured artists | Removed discovery endpoints don't block us; keeps us well inside 10k units/day; demonstrates deliberate quota budgeting. |

## 10. Remaining questions (defer to L2/L3, not blocking L1)

- **Collection cadence** & exact metrics to snapshot (daily? views, subs, video count, velocity) → L2/L3.
- **Cold-start**: how much history before the model is trainable → resolved empirically in the spike.
