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
- **Builds on:** [`L1-solution-context.md`](./L1-solution-context.md), [`L2-logical-architecture.md`](./L2-logical-architecture.md), [`research-2026-08.md`](./research-2026-08.md)
- **Date:** 2026-08-04 · **Revised:** 2026-08-10 (research pass — inorganic-growth flag, PWA/$0 delivery)

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
    inorganic_score DOUBLE PRECISION,               -- C3′: continuous suspicion [0,1] (see §3)
    suspected_inorganic BOOLEAN NOT NULL DEFAULT FALSE, -- C3′: inorganic_score >= threshold
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
| `inorganic_score` | see C3′ formula below | **Data-quality signal (new, 2026-08).** How likely this artist's recent growth is *manufactured* (bot/purchased/synthetic) rather than real audience momentum. |

**C3′ inorganic-growth detection (as-of `t`, pure, $0 — no extra API calls):**
Computed from the *same* snapshots ≤ `t`, so it's leakage-safe like every other feature.
Combine cheap anomaly signals into `inorganic_score ∈ [0, 1]`:

| Signal | Definition | Fires when |
|---|---|---|
| Subs-jump z-score | daily `ΔS` vs. mean/stdev of the artist's own trailing-30d daily `ΔS` | a single day's gain is many σ above the artist's own norm (overnight spike) |
| Subs-vs-views divergence | `growth_7d` (subs) − `views_growth_7d` | subs surge while views stay flat (classic bought-subs signature) |
| Step discontinuity | largest single-day `ΔS` / total 7d gain | one day dominates the week's growth (step, not a curve) |

`inorganic_score` = normalized weighted blend (weights in `[dataquality]` config);
`suspected_inorganic` = `inorganic_score ≥ inorganic_threshold` (config, default `0.8`).
**Model usage is config-switchable** (`[dataquality].mode`): `feature` (default — model learns
to discount it), `exclude` (drop suspect rows from train/label cohort), or `both`. Default never
silently drops data — the choice is auditable and logged.

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
search_list_call_ceiling = 90  # NEW: separate ~100/day search.list bucket (since 2026-06-01); we avoid it

[dataquality]                  # NEW (2026-08): C3′ inorganic-growth detection
mode = "feature"               # "feature" | "exclude" | "both" — default never drops data silently
inorganic_threshold = 0.8      # suspected_inorganic = inorganic_score >= this
w_subs_jump = 0.5              # blend weights for inorganic_score
w_subs_views_divergence = 0.3
w_step_discontinuity = 0.2
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

**Free-tier (v0.2) — the standing target, not a fallback (L1 $0 mandate):**
- **Postgres → Neon free tier** (swap `DATABASE_URL`). $0.
- **Daily collector → GitHub Actions cron** (`.github/workflows/collect.yml`, `schedule: cron`),
  secrets in repo settings; runs `crescendo collect` against Neon. No server, free minutes.
- **Predict API (v0.3) → Fly.io / Render free tier** (FastAPI, §11). $0.
- **Consumer client (v1.0) → installable PWA on a free static host** (Vercel / GitHub Pages).

**Delivery: installable PWA — desktop + Android, no iOS/App Store (NEW, 2026-08).**
The consumer surface is a **single web build** made installable via a **web app manifest +
service worker**, so it can be "Add to Home Screen"-installed on **Android** and
"Install app"-installed from **desktop Chrome/Edge**. Rationale (L1 §7/§9):
- **$0:** no Apple Developer fee ($99/yr), no Play Store fee unless we ever publish (side-load /
  PWA install is free), no native build/signing pipeline.
- **One codebase:** the transparent-AI game UI (roster draft, AI picks + `reasons`) is
  web-native; a PWA covers the two platforms that matter for a free personal project.
- **iOS explicitly out of scope:** the Apple fee has no place here and iOS PWA install is
  degraded — a deliberate non-goal, not a gap.
- Frontend stack stays free/standard (e.g. Vite + a JS framework); the PWA bits are just the
  manifest + service worker. Ships in `frontend/` at v1.0; nothing to build for the v0.1 spike.

**AWS (v2.0, designed-for, NOT built — and only if it stays inside free tiers or the project
outgrows "$0"):** RDS Postgres · EventBridge→Lambda for `collect` · Lambda/Fargate for the
predict API · CDK · Secrets Manager. *Explicitly deferred* under the $0 mandate — the free-tier
stack above is the real deployment target.

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
  **transparent AI** UX — the project's headline novelty per L1 §3/§11). The AI opponent calls
  the *same* endpoint players' scores derive from, so "the AI plays by the rules it shows you"
  is true by construction. `reasons` are sourced from `model.feature_importances()` applied to
  the row's feature contributions (incl. `inorganic_score` — the AI can literally say
  *"discounted: growth looks inorganic"*). Served by FastAPI wrapping `model.predict`. Stub in `serving/`.

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

---

# Part II — Code-ready detail (v0.1)

> §1–§12 are the buildable blueprint. §13–§22 below are the **implementation contract**:
> exact type signatures, the algorithms behind the prose (discovery snowball, quota
> accounting, walk-forward folds, `dataset_version` hashing), error/retry semantics, the
> structured-log schema, and the model-artifact format. Written so a developer (or the next
> session) can code each module without re-deriving decisions. Python 3.12 type hints throughout.

---

## 13. Shared domain types (`crescendo` package)

Defined once (e.g. in `config.py` / a small `types.py`) and reused across modules so
signatures below are unambiguous. Dataclasses are frozen where they represent immutable facts.

```python
from dataclasses import dataclass
from datetime import date, datetime

@dataclass(frozen=True)
class ArtistStats:
    """One YouTube channels.list(statistics) reading, provider-shaped, pre-persistence."""
    channel_id: str
    title: str
    subscribers: int
    total_views: int
    video_count: int
    fetched_at: datetime          # UTC; when the API returned this

@dataclass(frozen=True)
class TrackedArtist:
    artist_id: int
    channel_id: str
    title: str
    genre: str
    subs_at_entry: int
    source: str                   # 'seed' | 'snowball'
    discovered_at: datetime
    is_active: bool

@dataclass(frozen=True)
class Snapshot:
    artist_id: int
    captured_on: date             # UTC collection day (the raw_snapshot PK date)
    subscribers: int
    total_views: int
    video_count: int

@dataclass(frozen=True)
class FeatureRow:
    """One as-of feature vector; label attached separately (labels may be absent)."""
    artist_id: int
    as_of_date: date
    subs: int
    growth_7d: float | None
    growth_30d: float | None
    accel: float | None
    consistency: float | None
    views_growth_7d: float | None
    upload_rate_30d: float | None
    inorganic_score: float | None      # C3′ data-quality signal (§3)
    suspected_inorganic: bool          # inorganic_score >= config threshold

@dataclass(frozen=True)
class EvalResult:
    cutoff: date
    fold_index: int
    n_train: int
    n_test: int
    k: int
    base_rate: float              # breakout prevalence in the test fold
    precision_at_k: float
    lift: float                   # precision_at_k / base_rate
    ndcg_at_k: float
    roc_auc: float
```

All money-of-the-project invariants (band 1k–100k, gap tolerance, cold-start days) live in
`Config`, not in these types — the types are dumb carriers.

---

## 14. Module interface reference (public signatures)

The exact public surface each `src/crescendo/*.py` module exposes. Private helpers omitted.
These are the contracts the tests in §9 pin.

```python
# config.py -----------------------------------------------------------------
def load_config(path: str = "config/crescendo.toml") -> Config: ...
    # reads TOML + .env, validates ranges (subs_min < subs_max, windows > 0,
    # 0 < breakout_decile < 1, quota ceiling <= 10000), raises ConfigError on any violation.

# youtube.py ----------------------------------------------------------------
class QuotaExceeded(Exception): ...
class YouTubeClient:
    def __init__(self, api_key: str, accountant: "QuotaAccountant"): ...
    def channel_stats(self, channel_id: str) -> ArtistStats: ...        # 1 unit
    def channel_stats_batch(self, channel_ids: list[str]) -> list[ArtistStats]: ...  # 1 unit / 50 ids
    def playlist_channel_ids(self, playlist_id: str) -> list[str]: ...  # 1 unit / page
    def related_channels(self, channel_id: str) -> list[str]: ...       # snowball edge; cheap calls only

# discovery.py --------------------------------------------------------------
def discover(cfg: Config, db: "Db", yt: YouTubeClient,
             max_artists: int, snowball: bool) -> "DiscoverReport": ...

# collector.py --------------------------------------------------------------
def collect_once(cfg: Config, db: "Db", yt: YouTubeClient,
                 captured_on: date, limit: int | None = None,
                 dry_run: bool = False) -> "CollectReport": ...

# features.py (PURE — no DB, no I/O; the unit-test core) --------------------
def compute_features(history: list[Snapshot], as_of: date, cfg: Config) -> FeatureRow: ...
def forward_growth(history: list[Snapshot], as_of: date, cfg: Config) -> float | None: ...
    # relative growth over (as_of, as_of + forward_days]; None if no in-tolerance snapshot.

# dataset.py ----------------------------------------------------------------
def build_dataset(cfg: Config, db: "Db",
                  as_of_start: date, as_of_end: date) -> "DatasetReport": ...
def dataset_version(cfg: Config) -> str: ...        # see §17

# model.py ------------------------------------------------------------------
def train(df_train: "pd.DataFrame", cfg: Config, model_kind: str) -> "CrescendoModel": ...
class CrescendoModel:                               # the persisted artifact + predict seam
    def predict(self, features: "pd.DataFrame") -> "pd.Series": ...   # breakout_score per row
    def feature_importances(self) -> dict[str, float]: ...
    def save(self, path: str) -> None: ...
    @classmethod
    def load(cls, path: str) -> "CrescendoModel": ...

# evaluate.py ---------------------------------------------------------------
def evaluate(cfg: Config, db: "Db", cutoff: date,
             k: int | str = "auto", walk_forward: bool = False,
             model_kind: str = "lgbm") -> list[EvalResult]: ...

# db.py ---------------------------------------------------------------------
class Db:
    def __init__(self, database_url: str): ...
    def bootstrap(self) -> None: ...                # runs §2 DDL, idempotent (IF NOT EXISTS)
    def upsert_artists(self, rows: list[TrackedArtist]) -> int: ...
    def insert_snapshots(self, rows: list[Snapshot]) -> int: ...   # ON CONFLICT DO NOTHING
    def active_artists(self) -> list[TrackedArtist]: ...
    def history_for(self, artist_id: int, until: date) -> list[Snapshot]: ...  # captured_on <= until
    def write_dataset(self, rows: list[dict], version: str) -> int: ...
    def read_dataset(self, version: str) -> "pd.DataFrame": ...
    def stats(self) -> dict: ...                    # powers `crescendo status`
```

`Db.history_for(..., until=as_of)` is the **structural leakage guard at the data-access
layer**: feature code physically cannot receive a snapshot after `as_of`, because the
repository never returns one. `forward_growth` reads a separate window via its own bounded
query. This is defense-in-depth on top of the §3/§4 rules.

---

## 15. Discovery algorithm (C1, `discovery.py`)

Seed + budgeted snowball, all on **cheap calls only** (never `search.list`).

```
discover(cfg, db, yt, max_artists, snowball):
    seen        = set(existing channel_ids in tracked_artist)   # idempotent re-runs
    frontier    = parse seed_file -> playlist/channel IDs
    accepted    = []

    # ---- Pass 1: seeds ----
    for pid in seed playlists:
        for cid in yt.playlist_channel_ids(pid):
            consider(cid, source='seed')

    # ---- Pass 2: bounded snowball (BFS, depth-limited) ----
    if snowball:
        queue = accepted channel_ids (depth 0)
        while queue and len(accepted) < max_artists and quota_ok():
            cid = queue.pop()
            if depth(cid) >= cfg.snowball_max_depth: continue
            for nb in yt.related_channels(cid):
                consider(nb, source='snowball', depth=depth(cid)+1, enqueue=queue)

    db.upsert_artists(accepted)          # dedupe on channel_id (UNIQUE)
    return DiscoverReport(n_seed, n_snowball, n_rejected_band, n_rejected_inactive, units_spent)

consider(cid, source, ...):
    if cid in seen or len(accepted) >= max_artists: return
    seen.add(cid)
    stats = yt.channel_stats(cid)                 # 1 unit (batched in practice, §14)
    if not (cfg.subs_min <= stats.subscribers <= cfg.subs_max): reject('band'); return
    if inactive(stats): reject('inactive'); return   # e.g. video_count == 0
    accepted.append(TrackedArtist(... source=source, subs_at_entry=stats.subscribers ...))
```

- **Batching:** resolve channel IDs in groups of 50 via `channel_stats_batch` (1 unit /50) to
  stay far under quota; `consider` is written against the batched result.
- **Determinism:** seed file order + BFS pop order fixed (no `set` iteration for ordering) so a
  re-run with the same seeds/quota yields the same tracked set — reproducibility (L2 §6).
- **`snowball_max_depth`** is a new config key (default `1`) added under `[genre]`/`[cohort]`.

---

## 16. Quota accountant (C2/C1 cross-cutting, `youtube.py`)

The resume-worthy "budgeted a scarce resource" story, made concrete.

```python
class QuotaAccountant:
    def __init__(self, daily_ceiling: int, today: date): ...
    def charge(self, units: int, op: str) -> None:
        """Reserve units BEFORE the call. Raises QuotaExceeded if it would breach ceiling."""
        if self.spent + units > self.daily_ceiling:
            log.warning("quota.block", op=op, want=units, spent=self.spent,
                        ceiling=self.daily_ceiling)
            raise QuotaExceeded(op, units, self.remaining())
        self.spent += units
    def remaining(self) -> int: return self.daily_ceiling - self.spent
```

- **Reserve-before-call**, never after → we never overspend even on a partial failure.
- `YouTubeClient` calls `accountant.charge(cost, op)` as its first line in every method; the
  per-op costs are the `# N unit` annotations in §14.
- **Reset semantics:** a fresh accountant is constructed per process run keyed to `today`
  (UTC). The 10k budget is a **daily** quota; the collector's cron/APScheduler gives one run
  per day, so per-process reset == per-day reset. `crescendo status` reports `spent/ceiling`.
- **Callers catch `QuotaExceeded`** and stop gracefully, logging dropped work (no silent
  truncation — L2 §6). `collect_once` catches it at the loop level; `discover` at the frontier.

---

## 17. Dataset assembly & `dataset_version` (C3, `dataset.py`)

**Cohort eligibility** for an `(artist, as_of)` pair — all must hold, else the row is skipped:
1. `subs_at_entry` was in band at discovery (guaranteed by C1) **and** `S(as_of)` still ≤
   `subs_max × 1.5` (soft upper guard so runaway breakouts don't dominate — configurable).
2. **Cold-start:** at least `min_history_days` of snapshots at/before `as_of` (feature lookback).
3. A forward snapshot exists within tolerance of `as_of + forward_days` (else **no label** → excluded).

```
build_dataset(cfg, db, as_of_start, as_of_end):
    version = dataset_version(cfg)
    rows = []
    for artist in db.active_artists():
        hist_full = db.history_for(artist.artist_id, until=as_of_end + forward_days)  # incl. label window
        for as_of in daterange(as_of_start, as_of_end):
            hist = [s for s in hist_full if s.captured_on <= as_of]     # feature window
            if not cold_start_ok(hist, as_of, cfg): continue
            feats = compute_features(hist, as_of, cfg)                  # PURE (§14)
            label = forward_growth(hist_full, as_of, cfg)               # reads (as_of, +30d]
            if label is None: continue                                  # never impute
            rows.append({**asdict(feats), "fwd_growth_30d": label,
                         "is_breakout": None, "dataset_version": version})
    db.write_dataset(rows, version)     # UPSERT on PK (artist_id, as_of_date, dataset_version)
    return DatasetReport(n_rows, n_artists, n_skipped_coldstart, n_skipped_nolabel, version)
```

- **`is_breakout` is written as NULL here on purpose** (§2 note): the top-decile label is
  assigned **per fold** at eval time, not at build time. `dataset.py` never sets it.
- **`dataset_version(cfg)`** = short hash binding a dataset to the exact params that made it:

  ```python
  def dataset_version(cfg: Config) -> str:
      payload = json.dumps({
          "genre": cfg.genre_name,
          "subs_min": cfg.subs_min, "subs_max": cfg.subs_max,
          "min_history_days": cfg.min_history_days,
          "gap_tol": cfg.snapshot_gap_tolerance_days,
          "short": cfg.short_window, "long": cfg.long_window,
          "consistency": cfg.consistency_window,
          "forward_days": cfg.forward_days,
          "schema": DATASET_SCHEMA_VERSION,      # bump when the row shape changes
      }, sort_keys=True)
      return hashlib.sha1(payload.encode()).hexdigest()[:12]
  ```
  Different params → different `dataset_version` → rows never collide in the `dataset` table
  (PK includes it). The label decile is **not** in the hash — labeling is a fold-time concern.

---

## 18. Walk-forward folds & fold-scoped labeling (C4, `evaluate.py`)

This is the section that operationalizes the single subtlest correctness rule (§4). Two nested
guards: temporal split (no future in train) **and** per-fold decile threshold (no global label leak).

```
evaluate(cfg, db, cutoff, k, walk_forward, model_kind):
    df = db.read_dataset(version = dataset_version(cfg))     # is_breakout still NULL
    df = df.sort_values("as_of_date")
    folds = make_folds(df, cutoff, cfg, walk_forward)         # see below
    results = []
    for i, (train_idx, test_idx) in enumerate(folds):
        train, test = df.loc[train_idx], df.loc[test_idx]

        # --- fold-scoped labels: threshold from THIS fold's rows only ---
        thr_train = quantile(train.fwd_growth_30d, 1 - cfg.breakout_decile)
        y_train   = (train.fwd_growth_30d >= thr_train).astype(int)
        thr_test  = quantile(test.fwd_growth_30d,  1 - cfg.breakout_decile)   # test's OWN threshold
        y_test    = (test.fwd_growth_30d  >= thr_test ).astype(int)

        model  = train_model(train[FEATURES], y_train, model_kind, cfg)
        scores = model.predict(test[FEATURES])
        kk     = resolve_k(k, y_test)                          # 'auto' -> int(len(test)*decile)
        results.append(score_fold(i, cutoff_i, y_test, scores, kk))
    return results

make_folds(df, cutoff, cfg, walk_forward):
    if not walk_forward:
        train = df[df.as_of_date <  cutoff].index
        test  = df[(df.as_of_date >= cutoff) &
                   (df.as_of_date <  cutoff + forward_days)].index   # labels fully realized
        return [(train, test)]
    # expanding-window: N folds, each cutoff steps forward by forward_days
    cutoffs = [cutoff + j*forward_days for j in range(cfg.walk_forward_folds)]
    return [ (df[df.as_of_date < c].index,
              df[(df.as_of_date >= c) & (df.as_of_date < c + forward_days)].index)
             for c in cutoffs ]
```

- **Why `thr_test` uses the test fold's own distribution:** the breakout base rate we compare
  against must be the *test period's* prevalence, computed from *only* test-period outcomes.
  Using `thr_train` (or a global threshold) on the test set would import the training/global
  distribution into the evaluation — the exact leakage §4 forbids. `precision@k` and `base_rate`
  are therefore both measured within the fold, making `lift` honest.
- **`resolve_k('auto', y_test)`** = `int(len(y_test) * breakout_decile)`, so `k` == the fold's
  positive count → `precision@k` is directly comparable to `base_rate` (~decile). Guard `k ≥ 1`.
- **Baselines** (§5) are scored on the *same* `y_test`/`kk`: random ranking and `growth_7d`-rank,
  each producing its own `precision@k`; the report shows model-vs-both.
- `test_evaluate.py` asserts: (a) `max(train.as_of_date) < min(test.as_of_date)` every fold;
  (b) the decile threshold differs when computed per-fold vs globally on a crafted fixture
  (regression test for the rule); (c) `resolve_k` and `precision@k` arithmetic.

---

## 19. Model artifact & predict seam (C4→future, `model.py`)

`CrescendoModel` is the **one seam** the future game + AI opponent depend on (L2 §5), so its
persisted form is a stable contract even in v0.1.

```
artifact (single file, e.g. models/<dataset_version>_<model_kind>.joblib):
    {
      "schema": MODEL_ARTIFACT_VERSION,
      "model_kind": "lgbm" | "xgb",
      "booster": <fitted estimator>,
      "feature_names": [...FEATURES in fixed order...],   # predict() reindexes to this
      "dataset_version": "<12-char hash>",                # which data trained it
      "trained_at": "<UTC iso>",                          # stamped by caller, not in-script
      "params": {...hyperparams...}
    }
```

- `predict(features_df)` **reindexes** incoming columns to `feature_names` (fills missing with
  NaN, errors on unknown) so callers can't silently pass a mis-ordered frame — this protects the
  future HTTP contract in §11 where the game sends a features dict.
- `feature_importances()` feeds the `reasons` field of the §11 predict response → the
  transparent-AI UX. For LightGBM use gain-based importance; expose normalized to sum 1.0.
- Persistence via `joblib` (handles the sklearn/LightGBM estimator); the wrapper dict is plain
  JSON-able metadata so the artifact is inspectable.

---

## 20. Structured logging schema (cross-cutting)

Every command emits **one JSON object per line** (L2 §6 observability). Fixed envelope so logs
are greppable and the `status`/report tooling can parse them.

```json
{"ts":"2026-08-10T04:00:01Z","level":"info","event":"collect.artist",
 "run_id":"<uuid-per-invocation>","artist_id":42,"units_spent":37,"ok":true}
```

| Field | Always | Meaning |
|---|---|---|
| `ts` | ✓ | UTC ISO-8601 (caller-stamped). |
| `level` | ✓ | `debug`/`info`/`warning`/`error`. |
| `event` | ✓ | Dotted name: `discover.consider`, `collect.artist`, `collect.done`, `quota.block`, `dataset.skip`, `eval.fold`. |
| `run_id` | ✓ | One UUID per CLI invocation → correlate all lines of a run. |
| context | — | Event-specific: `artist_id`, `units_spent`, `n_rows`, `fold`, `reason`, etc. |

Key required events (asserted lightly in tests): `quota.block` (dropped work), `collect.done`
(totals: snapshotted / failed / units), `dataset.skip` (with `reason` ∈ coldstart|nolabel|band),
`eval.fold` (metrics per fold). Use `structlog` or stdlib `logging` with a JSON formatter.

---

## 21. Error taxonomy & retry semantics

| Failure | Where | Handling |
|---|---|---|
| `QuotaExceeded` | any YouTube call | Caller stops its loop, logs `quota.block` + `*.done` with partials, exits **0** (partial success is normal, not an error). |
| HTTP 5xx / timeout | `YouTubeClient` | Retry ≤ 3× with exponential backoff + jitter; **quota is charged once** (before first try) so retries don't double-spend. Give up → per-item failure. |
| HTTP 403 `quotaExceeded` (server-side) | `YouTubeClient` | Treat as `QuotaExceeded` (our accountant should prevent it, but trust the server too). |
| HTTP 404 / channel gone | `collector.py` | Isolate: mark artist `is_active=False`, log `collect.artist ok=false reason=gone`, continue. |
| Malformed / missing stats field | `youtube.py` | Skip that item, log `warning`; never write a partial `Snapshot`. |
| DB unique conflict on snapshot | `db.insert_snapshots` | `ON CONFLICT DO NOTHING` → idempotent, counts as no-op (re-run safe). |
| `ConfigError` | `config.load_config` | **Fail fast** at startup, non-zero exit, before any API/DB work. |

**Per-item isolation is the rule for C1/C2:** one bad channel never fails the batch (L2 §4).
**Fail-fast is the rule for config/DB-connect:** don't start a run that can't possibly complete.

---

## 22. Bootstrap & first-run sequence (concrete)

```bash
docker compose up -d                      # Postgres:16 (§10)
cd ml && uv sync                          # install pinned deps (Python 3.12)
cp .env.example .env && edit .env         # YOUTUBE_API_KEY, DATABASE_URL
uv run crescendo status                   # triggers Db.bootstrap() (idempotent DDL), prints empty counts
uv run crescendo discover --max-artists 300   # C1: populate tracked_artist
uv run crescendo collect                  # C2: first snapshot (repeat daily, or schedule.py)
# ...accumulate ~45 days (or backfill)...
uv run crescendo build-dataset --as-of-start 2026-06-25 --as-of-end 2026-07-25
uv run crescendo train --model lgbm --cutoff 2026-07-10
uv run crescendo evaluate --cutoff 2026-07-10 --walk-forward
# open notebooks/01_spike_report.ipynb -> the headline answer
```

- `Db.bootstrap()` runs the §2 DDL with `CREATE TABLE IF NOT EXISTS` + `CREATE INDEX IF NOT
  EXISTS`, so `status` (or any command) is safe to run against a fresh or existing DB.
- `schema` version constants (`DATASET_SCHEMA_VERSION`, `MODEL_ARTIFACT_VERSION`) are bumped
  when row/artifact shapes change; `dataset_version` (§17) picks up the dataset bump
  automatically, forcing a rebuild rather than mixing incompatible rows.

> **Determinism caveat (matches the harness constraint):** `trained_at` / log `ts` are stamped
> from the wall clock at the edge (CLI entry), never inside pure functions — so `features.py`,
> `dataset.py`, and `evaluate.py` stay pure and reproducible given the immutable raw layer.
