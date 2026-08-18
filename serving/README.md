# serving/ — prediction API (v0.3)

FastAPI service that exposes the L2 `predict()` seam over HTTP (L3 §11). Both the future game
backend **and** the transparent AI opponent call this **same** endpoint, so *"the AI plays by
the rules it shows you"* is true by construction: `breakout_score` is the model's ranking score
and `reasons` are derived from `model.feature_importances()` applied to each artist's features.

## Contract

```
POST /predict
Request:  { "as_of_date": "2026-09-01",
            "artists": [ { "artist_id": 42, "features": { "growth_7d": 0.08, "accel": 0.03, ... } } ] }
Response: { "as_of_date": "2026-09-01", "model_kind": "lgbm", "dataset_version": "…",
            "ranked": [ { "artist_id": 42, "breakout_score": 0.81, "rank": 1,
                          "reasons": ["strong 7d subscriber growth", "growth looks organic"] } ] }

GET /health -> { "status": "ok", "model_loaded": true, "model_kind": "lgbm",
                 "dataset_version": "…", "n_features": 8 }
```

- Feature keys are the canonical `crescendo.FEATURES` names. Extra keys are ignored and missing
  ones are reindexed to `NaN` — identical to `CrescendoModel.predict` in-process, so the HTTP
  seam and the Python seam behave the same. A request supplying **no** known feature returns 422.
- `ranked` is sorted by `breakout_score` descending, ties broken by `artist_id` (deterministic).
- **`inorganic_score`** drives an authenticity reason: a flagged pick (`>= inorganic_threshold`,
  read from `ml/config/crescendo.toml`) reads *"discounted: growth looks inorganic"*; a clean
  pick with real momentum reads *"growth looks organic"*. This is the organic-breakout novelty
  surfaced in the API itself — the AI can say **why** it trusts or discounts a pick.

## Run

```bash
cd serving
uv sync --extra dev
# Point at a model artifact produced by `crescendo train` (ml/ package):
MODEL_PATH=../ml/models/<version>_lgbm.joblib uv run crescendo-serve   # -> http://127.0.0.1:8000
```

Env: `MODEL_PATH` (required, path to a `CrescendoModel` .joblib), `CRESCENDO_CONFIG` (optional,
defaults to `ml/config/crescendo.toml` — only its `dataquality.inorganic_threshold` is read),
`HOST`/`PORT`. The model loads once at startup; `GET /health` reports `model_loaded=false`
rather than erroring when `MODEL_PATH` is unset, so the liveness probe never 5xxs.

## Tests

```bash
uv run pytest -q          # reasons (pure) + service ranking + HTTP contract, against a real fitted model
uv run ruff check src tests
```

Deploy target (later): Fly.io / Render free tier ($0), per L3 §9.
