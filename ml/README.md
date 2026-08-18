# Crescendo — ML modeling spike (`ml/`, v0.1)

The leakage-safe breakout-prediction core: discover emerging electronic artists on
YouTube, collect a daily channel-level time series, engineer as-of momentum features, and
evaluate whether **30-day forward relative growth** is predictable with a **temporal,
per-fold** protocol that beats a base-rate baseline.

See [`../docs/L3-detailed-design.md`](../docs/L3-detailed-design.md) for the full blueprint.

## Layout

```
src/crescendo/
  config.py     # load + validate config/crescendo.toml + .env (fail fast)
  types.py      # frozen domain types (ArtistStats, Snapshot, FeatureRow, EvalResult, ...)
  logging.py    # structured JSON-line logging (one object/line, run_id envelope)
  db.py         # Postgres: idempotent DDL bootstrap + repositories (leakage guard)
  youtube.py    # YouTube Data API v3 client + quota accountant (reserve-before-call)
  discovery.py  # C1: seed + bounded snowball -> tracked_artist
  collector.py  # C2: daily snapshot pass (per-artist isolation, idempotent)
  schedule.py   # local APScheduler daily runner
  features.py   # C3: PURE as-of feature math (the unit-tested core) + C3' inorganic score
  dataset.py    # C3: cohort + cold-start + forward label -> dataset (is_breakout NULL)
  model.py      # C4: LightGBM/XGBoost artifact + predict() seam
  evaluate.py   # C4: temporal walk-forward folds, per-fold decile labeling, precision@k
  cli.py        # Typer CLI: discover/collect/build-dataset/train/evaluate/status
tests/          # feature math, leakage/cold-start, fold-decile, quota
```

## Quick start (local)

```bash
brew install libomp                  # macOS: OpenMP runtime for LightGBM/XGBoost
docker compose up -d                 # Postgres:16 (from repo root)
cd ml && uv sync                     # install pinned deps (Python 3.12)
cp .env.example .env && $EDITOR .env # YOUTUBE_API_KEY, DATABASE_URL
uv run crescendo status              # bootstraps DDL, prints empty counts
uv run crescendo discover --max-artists 300
uv run crescendo collect             # repeat daily (or use schedule.py / GH Actions cron)
# ...after ~45 days of history (or backfill)...
uv run crescendo build-dataset --as-of-start 2026-06-25 --as-of-end 2026-07-25
uv run crescendo train --model lgbm --cutoff 2026-07-10
uv run crescendo evaluate --cutoff 2026-07-10 --walk-forward
```

## Tests (the correctness gate)

```bash
uv run pytest -q
```

The leakage/label/fold tests run on synthetic fixtures — **no DB or API key required** —
so the pipeline's honesty is validated before any real data matures.

## The one correctness rule

`is_breakout` = top decile of forward growth **within each temporal fold**, computed from
that fold's own rows only — never globally. Computing it globally would leak the future
growth distribution into the label. Enforced in `evaluate.py`, regression-tested in
`tests/test_evaluate.py`.
