# Crescendo — L3 Detailed Design

> **L3 = the buildable blueprint.** Concrete repo layout, database DDL, exact feature
> formulas & windows, the label spec (with the leakage subtlety pinned), the evaluation
> protocol, CLI contract, config/secrets, package/module layout, local + free-tier infra,
> and the future prediction API. Everything here is specific enough to code against.
>
> Scope: the **MVP modeling spike (v0.1)** in full detail, with **v0.2+ hooks** designed for.

- **Status:** Draft v0.1
- **Owner:** utkarsh-senpai
- **Reviewing persona:** "Sam" — Staff ML Engineer / hiring manager
- **Builds on:** [`L1-solution-context.md`](./L1-solution-context.md), [`L2-logical-architecture.md`](./L2-logical-architecture.md)
- **Date:** 2026-08-04

---

## 0. Decisions carried in (context)

- **Monorepo** — one `crescendo` repo, module dirs (`ml/`, later `serving/`/`backend/`/`frontend/`).
- **Genre:** electronic/EDM (config-swappable). **Emerging band:** 1k–100k subs at entry.
- **Target:** 30-day forward relative growth; **breakout = cohort top-decile**, scored per fold.
- **Signal:** channel-level daily (subs, views, video count). **Cadence:** daily.
- **Stack:** Python 3.12 (`uv`), Postgres (Docker), pandas, LightGBM/XGBoost, scikit-learn, APScheduler.
- **Deploy:** free-tier first (Neon + GitHub Actions cron), AWS/CDK later.

---

## 1. Repository layout (monorepo)

```
crescendo/
├── docs/                         # L1–L3 design docs
├── ml/                           # ← v0.1 MVP spike lives here
│   ├── pyproject.toml            # uv-managed; project + deps + CLI entrypoint
│   ├── uv.lock
│   ├── .env.example              # documents required env vars (no secrets)
│   ├── Makefile                  # thin wrappers: make collect / dataset / train / eval
│   ├── config/
│   │   └── crescendo.toml        # genre, bands, windows, k, cutoff — all params
│   ├── seeds/
│   │   └── electronic_playlists.txt   # curated seed playlist/channel IDs
│   ├── src/crescendo/
│   │   ├── __init__.py
│   │   ├── config.py             # load + validate config + env
│   │   ├── youtube.py            # thin YouTube API client + quota accountant
│   │   ├── discovery.py          # C1: seed+snowball → tracked_artist
│   │   ├── collector.py          # C2: daily snapshot job
│   │   ├── db.py                 # connection, schema bootstrap, repositories
│   │   ├── features.py           # C3: as-of momentum features
│   │   ├── dataset.py            # C3: cohort assembly + label + dataset export
│   │   ├── model.py              # C4: train LightGBM/XGBoost
│   │   ├── evaluate.py           # C4: temporal split, precision@k, baselines
│   │   ├── schedule.py           # APScheduler runner (wraps collector)
│   │   └── cli.py                # Typer/argparse CLI: discover/collect/... 
│   ├── notebooks/
│   │   └── 01_spike_report.ipynb # imports the package; renders the final report
│   └── tests/
│       ├── test_features.py      # feature-math unit tests (fixture time-series)
│       ├── test_dataset.py       # leakage + cold-start + label tests
│       ├── test_evaluate.py      # temporal-split + precision@k tests
│       └── test_quota.py         # quota accountant never exceeds ceiling
├── serving/                      # v0.3: FastAPI predict() (stub only for now)
├── backend/                      # v1.0: Spring Boot game (later)
├── frontend/                     # v1.0+: web UI (later)
├── docker-compose.yml            # local Postgres (+ services later)
└── .github/workflows/
    └── collect.yml               # v0.2: daily collector cron (GitHub Actions)
```

---

## 2. Database schema (Postgres DDL)

Three tables across the **raw → curated** boundary (L2 §1). Raw is append-only; curated is
derived and rebuildable.

```sql
-- ============ CURATED: the tracked universe ============
CREATE TABLE tracked_artist (
    artist_id       BIGSERIAL PRIMARY KEY,
    channel_id      TEXT NOT NULL UNIQUE,          -- YouTube channel ID (stable)
    title           TEXT NOT NULL,
    genre           TEXT NOT NULL DEFAULT 'electronic',
    discovered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    subs_at_entry   BIGINT NOT NULL,               -- must be within 1k–100k at discovery
    is_active       BOOLEAN NOT NULL DEFAULT TRUE, -- soft-drop dead channels
    source          TEXT NOT NULL                  -- 'seed' | 'snowball'
);
CREATE INDEX idx_artist_active ON tracked_artist (is_active);

-- ============ RAW: immutable daily snapshots ============
CREATE TABLE raw_snapshot (
    artist_id       BIGINT NOT NULL REFERENCES tracked_artist(artist_id),
    captured_on     DATE NOT NULL,                 -- collection day (UTC)
    subscribers     BIGINT NOT NULL,
    total_views     BIGINT NOT NULL,
    video_count     INTEGER NOT NULL,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (artist_id, captured_on)            -- one snapshot/artist/day; idempotent
);
CREATE INDEX idx_snapshot_day ON raw_snapshot (captured_on);
-- Append-only by convention: collector only INSERTs (ON CONFLICT DO NOTHING).

-- ============ CURATED: reproducible modeling dataset ============
-- Rebuildable from raw_snapshot; stored so runs are reproducible + inspectable.
CREATE TABLE dataset (
    artist_id       BIGINT NOT NULL REFERENCES tracked_artist(artist_id),
    as_of_date      DATE NOT NULL,                 -- prediction date t (temporal-split key)
    -- features (as-of t; see §3) --
    subs            BIGINT NOT NULL,
    growth_7d       DOUBLE PRECISION,
    growth_30d      DOUBLE PRECISION,
    accel           DOUBLE PRECISION,
    consistency     DOUBLE PRECISION,
    views_growth_7d DOUBLE PRECISION,
    upload_rate_30d DOUBLE PRECISION,
    -- label (forward (t, t+30d]; see §4) --
    fwd_growth_30d  DOUBLE PRECISION NOT NULL,      -- raw forward relative growth
    is_breakout     BOOLEAN,                        -- top-decile within fold (set at eval)
    dataset_version TEXT NOT NULL,                  -- config hash for reproducibility
    PRIMARY KEY (artist_id, as_of_date, dataset_version)
);
CREATE INDEX idx_dataset_asof ON dataset (as_of_date);
```

**Design notes:**
- `raw_snapshot` PK `(artist_id, captured_on)` makes re-runs **idempotent** (safe partial runs).
- `dataset.as_of_date` is the **temporal-split key** — C4 splits on it, never randomly.
- `is_breakout` is intentionally **nullable and set at eval time**, because top-decile is
  computed **within each temporal fold** (see §4/§5) — it is *not* a fixed property of a row.
- `dataset_version` = hash of the config that produced it → different params never collide.

---

## 3. Feature specification (C3, as-of date `t`)

All features use **only** snapshots with `captured_on ≤ t`. Let `S(d)` = subscribers on day
`d`, `V(d)` = total views on day `d`, using the most recent snapshot at/before the target day.

| Feature | Formula | Intuition |
|---|---|---|
| `subs` | `S(t)` | Current size (context/level; not the target). |
| `growth_7d` | `(S(t) − S(t−7)) / max(S(t−7), 1)` | Short-term subscriber momentum. |
| `growth_30d` | `(S(t) − S(t−30)) / max(S(t−30), 1)` | Medium-term momentum. |
| `accel` | `growth_7d − growth_7d_prev` where `growth_7d_prev` is the 7d growth as of `t−7` | 2nd-order momentum (is growth speeding up?). |
| `consistency` | `1 / (1 + stdev(daily_growth over trailing 14d))` | Steady growth scores high; spiky scores low. |
| `views_growth_7d` | `(V(t) − V(t−7)) / max(V(t−7), 1)` | View momentum (complements subs). |
| `upload_rate_30d` | `(video_count(t) − video_count(t−30))` | Release activity (a breakout driver). |

**Edge handling:**
- Missing a snapshot exactly at `t−k`? Use the **nearest earlier** snapshot and record the
  actual gap; if the gap exceeds a tolerance (config, default 3 days), the feature is `NULL`
  and the row is dropped for that `as_of_date`.
- All windows (7/30/14) are **config parameters** (`config/crescendo.toml`), not literals.

---

## 4. Label specification (C3 + C4)

**Target axis = relative growth (L1).** For prediction date `t`:

```
fwd_growth_30d(artist, t) = ( S(t+30) − S(t) ) / max(S(t), 1)
```

- Requires a snapshot at/near `t+30`; if absent beyond tolerance, the row has **no label** and
  is excluded from training/eval (never imputed — that would fabricate outcomes).

**Breakout label — the leakage-critical rule (pinned):**

> `is_breakout` = 1 iff `fwd_growth_30d` is in the **top decile (top 10%) of the cohort that
> shares the same temporal fold**, i.e. rows whose `as_of_date` falls in the same evaluation
> period. The decile threshold is computed **per fold, from that fold's own rows only** —
> **never** across the entire timeline.

Why this matters (Sam's flag from the L2 review): if you compute the top-decile threshold
over *all* dates at once, the threshold encodes the global (future-inclusive) growth
distribution → **future leakage into the label**. Computing it within each fold keeps the
label honest and makes precision@k meaningful. This is the single subtlest correctness point
in the project and is enforced in `evaluate.py`, not left to convention.

---

## 5. Evaluation protocol (C4)

**Split — temporal, walk-forward (never random):**
- Order rows by `as_of_date`. Choose a **cutoff** date (config). Train on `as_of_date < cutoff`,
  test on `as_of_date ≥ cutoff` (and `< cutoff + horizon` so labels are fully realized).
- Optional **walk-forward**: multiple expanding-window folds for a robustness curve.

**Models:**
- **Baseline(s):** (a) random ranking; (b) **naive momentum heuristic** = rank by `growth_7d`.
  These define the "beat base rate" bar.
- **Model:** LightGBM (primary) / XGBoost (comparison) on the §3 features. Class handling via
  ranking/regression on `fwd_growth_30d`, then threshold to top-decile for precision@k.

**Primary metric — precision@k:**
```
precision@k = (# of the model's top-k picks that are actually breakouts) / k
```
- `k` = config (default: `k` = size of the fold's top decile, so precision@k is directly
  comparable to the breakout base rate of ~10%).
- Report **lift** = `precision@k / base_rate`. Lift > 1 = the model has real signal.
- Secondary: NDCG@k, ROC-AUC, calibration — for the report notebook.

**Success bar (from L1 §8):** precision@k **beats the base-rate baseline** on the temporal
holdout — or a documented, honest negative result. Both are valid resume stories.

---

## 6. CLI contract (`crescendo.cli`)

Single entrypoint (Typer). Every command reads `config/crescendo.toml` + `.env`.

| Command | Purpose | Key flags |
|---|---|---|
| `crescendo discover` | C1: seed+snowball → populate `tracked_artist` | `--seeds PATH`, `--max-artists N`, `--snowball/--no-snowball` |
| `crescendo collect` | C2: one daily snapshot pass over active artists | `--dry-run`, `--limit N` (quota-aware) |
| `crescendo build-dataset` | C3: assemble features + labels → `dataset` | `--as-of-start`, `--as-of-end` |
| `crescendo train` | C4: fit model on train fold | `--model {lgbm,xgb}`, `--cutoff DATE` |
| `crescendo evaluate` | C4: temporal split, precision@k, baselines, report | `--cutoff DATE`, `--k N`, `--walk-forward` |
| `crescendo status` | ops: artists tracked, snapshots, quota used today | — |

Exit codes non-zero on failure; all commands log structured JSON lines.

---

## 7. Configuration & secrets

**`config/crescendo.toml`** (checked in — no secrets):
```toml
[genre]
name = "electronic"
seed_file = "seeds/electronic_playlists.txt"

[cohort]
subs_min = 1000
subs_max = 100000
min_history_days = 45          # cold-start guard (feature lookback + 30d label)
snapshot_gap_tolerance_days = 3

[features]
short_window = 7
long_window = 30
consistency_window = 14

[label]
forward_days = 30
breakout_decile = 0.10         # top 10%

[eval]
cutoff = "2026-09-15"          # temporal split date (example)
k = "auto"                     # auto = fold's top-decile size
walk_forward_folds = 3

[quota]
daily_unit_ceiling = 9500      # safety margin under 10k
```

**`.env`** (git-ignored; `.env.example` documents it):
```
YOUTUBE_API_KEY=...
DATABASE_URL=postgresql://crescendo:crescendo@localhost:5432/crescendo
```

Config is loaded + **validated** in `config.py` (fail fast on bad ranges / missing env).

---

## 8. Module responsibilities (mapping to L2 components)

| Module | L2 comp | Responsibility |
|---|---|---|
| `youtube.py` | C1/C2 | API client + **quota accountant** (tracks units, refuses to exceed ceiling, logs drops). |
| `discovery.py` | C1 | Seed parse → resolve channel IDs (cheap calls) → band filter → upsert `tracked_artist` (dedupe on `channel_id`). |
| `collector.py` | C2 | For each active artist: fetch stats, `INSERT ... ON CONFLICT DO NOTHING` into `raw_snapshot`; per-artist failure isolation. |
| `features.py` | C3 | Pure functions: snapshots → as-of features (§3). No DB writes; fully unit-testable. |
| `dataset.py` | C3 | Assemble cohort (band + cold-start), join features + forward label, write `dataset`. |
| `model.py` | C4 | Train/persist LightGBM/XGBoost; expose a `predict(features_df)` fn — the future serving seam. |
| `evaluate.py` | C4 | Temporal split, **fold-scoped breakout labeling**, precision@k, baselines, metrics JSON. |
| `db.py` | S1 | Connection pool, schema bootstrap (runs the §2 DDL), thin repositories. |
| `schedule.py` | C2 | APScheduler job wrapping `collect` for local daily runs. |
| `cli.py` | all | Typer commands (§6). |

---

## 9. Testing strategy (v0.1)

| Test | What it asserts |
|---|---|
| `test_features.py` | On a hand-built fixture time-series, each feature matches the §3 formula; NULL on gaps > tolerance. |
| `test_dataset.py` | **Leakage:** no feature uses a snapshot after `as_of_date`; label uses only `(t, t+30]`. **Cold-start:** artists with < `min_history_days` are excluded. |
| `test_evaluate.py` | Temporal split never puts a future row in train; **top-decile threshold is computed per fold** (regression test for the §4 rule); precision@k math correct. |
| `test_quota.py` | Quota accountant blocks the call that would exceed the ceiling and logs the dropped work. |

Run via `pytest`; target the leakage/label tests as the "correctness gate" before any model result is trusted.

---

## 10. Local & free-tier infra

**Local (`docker-compose.yml`):**
```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_USER: crescendo
      POSTGRES_PASSWORD: crescendo
      POSTGRES_DB: crescendo
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]
volumes: { pgdata: {} }
```
Workflow: `docker compose up -d` → `uv sync` → `crescendo discover` → daily `crescendo collect`
→ (after ~45d) `crescendo build-dataset` → `crescendo train` → `crescendo evaluate` → open the
report notebook.

**Free-tier (v0.2):**
- **Postgres → Neon** (swap `DATABASE_URL`).
- **Daily collector → GitHub Actions cron** (`.github/workflows/collect.yml`, `schedule: cron`),
  secrets in repo settings; runs `crescendo collect` against Neon. No server.

**AWS (v2.0, designed-for, not built):** RDS Postgres · EventBridge→Lambda for `collect` ·
Lambda/Fargate for the predict API · CDK for all of it · Secrets Manager for keys.

---

## 11. Future prediction API (v0.3, designed now)

The L2 `predict()` seam becomes an HTTP contract that **both** the game backend and the AI
opponent call (so the AI "plays by the rules it shows"):

```
POST /predict
Request:  { "as_of_date": "2026-09-01",
            "artists": [ { "artist_id": 42, "features": { "growth_7d": 0.08, ... } }, ... ] }
Response: { "as_of_date": "2026-09-01",
            "ranked": [ { "artist_id": 42, "breakout_score": 0.81, "rank": 1,
                          "reasons": ["high 7d accel", "steady consistency"] }, ... ] }
```
- `breakout_score` = model's ranking score; `reasons` = top feature contributions (drives the
  **transparent AI** UX). Served by FastAPI wrapping `model.predict`. Stub lives in `serving/`.

---

## 12. Build order (v0.1 implementation sequence)

1. `db.py` + docker-compose + schema bootstrap → `crescendo status` works.
2. `youtube.py` (client + quota) → `discovery.py` → `crescendo discover`.
3. `collector.py` + `schedule.py` → `crescendo collect` (start accumulating history).
4. `features.py` (+ tests) → `dataset.py` (+ leakage tests) → `crescendo build-dataset`.
5. `model.py` + `evaluate.py` (+ tests) → `crescendo train` / `evaluate`.
6. `notebooks/01_spike_report.ipynb` → the answer to the headline question.

> Note: steps 4–6 need ~45 days of collected history to be meaningful. To iterate sooner,
> `build-dataset` supports a **backfill from any historical snapshots** already present, and
> tests use synthetic fixtures so the pipeline is validated before real data matures.
