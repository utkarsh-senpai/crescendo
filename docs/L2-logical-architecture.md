# Crescendo — L2 Logical Architecture

> **L2 = the logical architecture.** *How* the system is decomposed into components,
> how data flows between them, the boundaries and contracts, and the internal design of
> the MVP-spike components. No schemas/DDL, no API signatures, no infra code — those are L3.
>
> Scope of this document: the **MVP modeling spike** in depth, with the **future game
> backend + AI opponent** shown only as plug-in points (dashed) so the architecture doesn't
> paint us into a corner.

- **Status:** Draft v0.1
- **Owner:** utkarsh-senpai
- **Reviewing persona:** "Sam" — Staff ML Engineer / hiring manager
- **Builds on:** [`L1-solution-context.md`](./L1-solution-context.md), [`research-2026-08.md`](./research-2026-08.md)
- **Date:** 2026-08-04 · **Revised:** 2026-08-10 (research pass — added C3′ data-quality guard, PWA delivery)

---

## 1. Architectural decisions locked at L2

These were the L2 open questions; now decided. They set the shape of every component below.

| Decision | Resolution | Rationale |
|---|---|---|
| **Signal granularity** | **Channel-level only** — snapshot `subscribers`, `total views`, `video count` per artist per day | ~1 quota unit/artist (`channels.list`) → track more artists; enough to compute growth / acceleration / consistency; simple time-series schema. |
| **Collection cadence** | **Daily snapshot** (once/day per tracked artist) | Matches the 30-day target window; daily is the finest grain that keeps quota trivial and noise low. |
| **Orchestration** | **Single Python process + APScheduler** (or system cron) | Zero infra, runs on a laptop, easy to demo/explain. No orchestrator overkill for one daily job. |
| **Model packaging** | **`crescendo` Python package + CLI**, thin report notebook | Testable, reproducible via CLI flags, real-engineering signal; notebook only renders the final report. |
| **Data layering** | **Raw → Curated** boundary inside Postgres | Raw snapshots are append-only/immutable; curated cohort + features are derived + reproducible. Guards against leakage and lets us rebuild features. |
| **Cold-start** | Model trainable only once an artist has **≥ (feature-lookback + 30-day target) history** (~**35–45 days**) | Can't compute a forward 30-day label or trailing momentum features without enough snapshots. Enforced by the cohort builder, not the model. |

---

## 2. Logical component view

See the container/data-flow diagram: [`L2-container-diagram.md`](./L2-container-diagram.md).

The MVP is **one Python codebase** (the `crescendo` package) exposing **four logical
components** plus a **Postgres** store. They are logically separate (clear
responsibilities + contracts) even though they ship as one deployable for the spike — this
keeps the door open to splitting the ML service out later (L1 §6).

| # | Component | Responsibility | In/Out |
|---|---|---|---|
| C1 | **Discovery / Resolver** | Find electronic/EDM channels in the 1k–100k band; resolve to stable channel IDs; register them as tracked artists | seed playlists → `tracked_artist` rows |
| C2 | **Snapshot Collector** | Daily: read each tracked artist's channel stats from YouTube; write immutable raw snapshots; respect quota budget | tracked artists → `raw_snapshot` rows |
| C3 | **Cohort & Feature Builder** | Turn raw snapshots into a leakage-safe modeling table: define cohort entry, compute momentum features (incl. the **inorganic-growth flag**, see C3′), compute the 30-day forward relative-growth label | raw snapshots → `feature_row` / dataset |
| C3′ | **Data-Quality / Signal Integrity** *(new, 2026-08)* | A **sub-concern of C3**, not a new deployable: detect **inorganic growth** (bot/purchased/synthetic spikes — a risk amplified by the 2026 AI-music flood, see research §3). Flags suspect artist-days so the model can down-weight or exclude them, and so the flag itself becomes a feature. | snapshots → `suspected_inorganic` flag on dataset rows |
| C4 | **Modeling & Evaluation** | Train LightGBM/XGBoost; evaluate with temporal split + precision@k vs base-rate baseline; emit metrics + report. **Exposes the `predict()` + `reasons` seam** the transparent-AI opponent uses. | dataset → model artifact + metrics report |
| S1 | **Postgres** | System of record: raw snapshots (immutable) + curated cohort/features | — |

*(Future, dashed in diagram: **Game Backend** (Spring Boot) and **AI Opponent** — both
consume C4's model behind a contract; not built in MVP.)*

---

## 3. Data flow (end-to-end, MVP)

```
seed playlists ──▶ [C1 Discovery] ──▶ tracked_artist
                                         │
                     (daily, APScheduler)│
YouTube Data API ──▶ [C2 Collector] ──▶ raw_snapshot   (immutable, append-only)
                                         │
                        [C3 Cohort/Feature Builder]
                          • filter to 1k–100k entry band
                          • enforce cold-start (≥~35–45d history)
                          • compute trailing momentum features
                          • [C3′] flag inorganic growth (bot/synthetic spikes)
                          • compute forward 30d relative-growth label
                          • label breakout = cohort top-decile
                                         │
                                     dataset (curated, reproducible)
                                         │
                        [C4 Modeling & Evaluation]
                          • temporal split (train past / test future)
                          • train LightGBM/XGBoost
                          • precision@k vs base-rate baseline
                                         │
                              model artifact + metrics + report notebook
```

**The golden rule threaded through the flow: no future information ever reaches a training
row.** Features use only snapshots at/before the prediction date `t`; the label uses only
snapshots in `(t, t+30d]`. C3 is where this is enforced structurally.

---

## 4. Component deep-dives (MVP)

### C1 — Discovery / Resolver
- **Strategy: seed + snowball** (from L1). Seed = a curated set of public electronic/EDM
  playlists / known channels. Resolve playlist items → channel IDs via cheap
  `playlistItems` (1 unit) + `channels.list` (1 unit). **Avoid `search.list` (100 units)**
  except for occasional discovery bursts.
- **Filter:** keep channels whose current subscriber count is in **1k–100k** and that look
  active (recent uploads). Persist survivors as `tracked_artist` with a stable channel ID.
- **Snowball (optional):** expand via related/featured channels to grow the set organically.
- **Idempotent:** re-running discovery must not duplicate artists (dedupe on channel ID).

### C2 — Snapshot Collector
- **Job:** once/day, for every `tracked_artist`, call `channels.list(part=statistics)` and
  write one `raw_snapshot` (subs, total views, video count, `captured_at`).
- **Quota guard:** a budget accountant tracks units spent today; the job **refuses to
  exceed the daily 10k-unit ceiling** and logs what it dropped (no silent truncation).
- **Immutability:** snapshots are append-only — never updated. This is the audit trail and
  the source for reproducible feature rebuilds.
- **Resilience:** per-artist failures are isolated (one bad channel ID doesn't fail the run);
  partial runs are safe because snapshots are keyed by `(artist, captured_at)`.
- **Scheduling:** APScheduler inside a long-running process, or a system cron invoking the
  CLI (`crescendo collect`). Same job either way.

### C3 — Cohort & Feature Builder (the leakage-critical component)
- **Cohort entry:** an artist enters the modeling cohort on dates where it (a) is in the
  1k–100k band and (b) has enough trailing history for features **and** a full 30-day
  forward window for the label (cold-start rule).
- **Features (trailing, as-of date `t`)** — computed only from snapshots ≤ `t`:
  - **Growth rate** — % change in subs/views over trailing 7d / 30d windows.
  - **Acceleration** — change in growth rate (2nd-order momentum).
  - **Consistency** — variance/steadiness of recent growth.
  - **Level features** — current subs/views (as context, not the target).
- **Label (forward, as-of `t`)** — relative growth over `(t, t+30d]`; **breakout = top-decile
  of the cohort's forward growth** for that period.
- **Output:** a curated, versioned `dataset` (one row per artist-per-eligible-date) with a
  clear `as_of_date` column that C4 uses for the temporal split.
- **Reproducible:** given the immutable raw snapshots, C3 is a pure function → same dataset
  every time.

### C3′ — Data-Quality / Signal Integrity (new, 2026-08 research pass)
- **Why it exists now:** the AI-music flood (research §3) means growth can be *manufactured*
  (bot subs, synthetic-native artists spiking overnight). Untreated, this pollutes the
  momentum signal and inflates "breakouts" that aren't real audience growth.
- **What it does (pure, offline, $0 — no extra API calls):** over the *same* as-of snapshot
  window, compute cheap anomaly signals — e.g. **implausible day-over-day subscriber jumps**
  (z-score vs. the artist's own recent volatility), **subs rising while views flat**
  (engagement mismatch), **step discontinuities**. Emit a `suspected_inorganic` flag +
  a continuous `inorganic_score` per artist-day.
- **How the model uses it (config-switchable):** (a) as an **input feature** (the model learns
  to discount suspicious momentum), and/or (b) as an **exclusion filter** for training/label
  cohorts. Default: include as feature, don't exclude — so the choice is auditable.
- **Leakage-safe:** uses only snapshots ≤ `as_of`, exactly like every other C3 feature — it is
  literally another as-of feature, so it inherits C3's purity + reproducibility.

### C4 — Modeling & Evaluation
- **Split:** **temporal only** — train on rows with `as_of_date < cutoff`, test on rows
  after. Never random (would leak future).
- **Model:** LightGBM/XGBoost on the tabular momentum features (fast, strong on this shape,
  interpretable via feature importance).
- **Metrics:** **precision@k** on the ranked breakout list (does the top-k the model picks
  actually break out?), compared against a **base-rate baseline** (random / most-recent-
  growth heuristic). Report lift over base rate.
- **Outputs:** trained model artifact, a metrics JSON, and a **report notebook** that imports
  the package and renders the story: features, split, precision@k curve, honest findings.
- **CLI surface (illustrative, detailed in L3):** `crescendo discover`, `crescendo collect`,
  `crescendo build-dataset`, `crescendo train`, `crescendo evaluate`.

---

## 5. Boundaries & contracts

| Boundary | Contract (logical; signatures in L3) | Why it matters |
|---|---|---|
| **C2 → S1 (raw)** | Append-only snapshot keyed by `(artist_id, captured_at)` | Immutable audit trail; enables reproducible rebuilds. |
| **S1 raw → C3** | C3 reads only raw snapshots ≤ `as_of_date` for features, and `(t, t+30d]` for labels | The structural leakage guard. |
| **C3 → C4** | A curated dataset with an explicit `as_of_date` per row | Lets C4 do a clean temporal split without knowing collection internals. |
| **C4 → (future) Game/AI** | A `predict(artist_features_as_of_t) → breakout_score/rank` interface | The **one seam** that the future Spring game + AI opponent depend on. Keeping it a clean function means the game never reaches into the model internals. |

**Future ML↔Game contract (not built now, but designed for):** the game backend will call
the model through a thin prediction interface (in-process call in MVP → HTTP/gRPC endpoint
later). The AI opponent uses the *same* interface, so "the AI plays by the same rules it
shows the player" is true by construction.

---

## 6. Cross-cutting concerns

- **Quota management** — central budget accountant (C2); logs dropped work; a resume-worthy
  "I budgeted a scarce resource" story.
- **Leakage safety** — enforced structurally in C3 (as-of dates) + C4 (temporal split), not
  by convention. This is the project's headline correctness property.
- **Reproducibility** — immutable raw layer + pure C3 + CLI-driven runs + pinned deps
  (`uv`, Python 3.12). Any result can be regenerated.
- **Configuration** — genre, sub-band, windows, top-decile k, cutoff date all live in config,
  not code, so the spike is re-runnable for other genres/params.
- **Observability (light)** — structured logs for collection runs (artists snapshotted, units
  spent, failures) and eval runs (rows, metrics). No heavy infra for MVP.
- **Secrets** — YouTube API key via `.env` (git-ignored), never committed.
- **Data-quality / signal integrity** *(new)* — inorganic-growth detection (C3′) as an as-of
  feature + flag; keeps the momentum signal honest against the AI-music flood. Costs $0 (pure
  compute on already-collected snapshots, no extra API calls).
- **Zero-cost & delivery** *(new)* — every component must run on a **free tier** (L1 $0
  mandate). Consumer surface ships as an **installable PWA** (desktop + Android; no iOS/App
  Store) so there are no store fees or native build pipelines.

---

## 7. Technology mapping (logical → concrete)

| Logical component | MVP technology | Later |
|---|---|---|
| C1 Discovery, C2 Collector, C3 Builder (incl. C3′), C4 Modeling | Python 3.12 (`uv`), `google-api-python-client`, pandas, LightGBM/XGBoost, APScheduler | Split C1–C2 into an ingestion service; C4 into a prediction service |
| S1 Store | Postgres (local/Docker) | **Neon free tier** (managed Postgres, $0) |
| Scheduling | APScheduler / cron | **GitHub Actions cron** (free minutes) |
| Packaging | `crescendo` package + CLI + report notebook | + FastAPI prediction endpoint (free tier: Fly/Render) |
| (future) Game Backend | — | Spring Boot (free-tier host) |
| (future) Consumer client | — | **Installable PWA** (desktop + Android; **no iOS/App Store**) on a free static host (Vercel/GitHub Pages) — satisfies the $0 mandate |
| (future) AI Opponent | — | Reuses C4 via the prediction contract; surfaces `reasons` (transparent play) |

---

## 8. What L2 deliberately defers to L3

- Concrete **Postgres schema / DDL** (tables, keys, indexes for `tracked_artist`,
  `raw_snapshot`, `dataset`).
- Exact **feature formulas & window parameters**, and the precision@k `k` / cutoff values.
- **CLI command signatures**, config file format, and env-var names.
- **Package/module layout** (`src/crescendo/...`) and test strategy.
- Local **run/infra** (Docker Compose for Postgres, `uv` setup).
- The future **prediction API** signature (HTTP contract) for the game.
