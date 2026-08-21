"""Bake a deterministic synthetic CrescendoModel artifact for the demo deployment.

v2.0.1: Training distribution updated to match the 4-archetype demo seed data so the
model produces clearly differentiated scores per archetype:
  ROCKET    (g7=0.18-0.25, accel=0.12-0.20, inorganic<0.05) → score ~0.70-0.90
  CLIMBER   (g7=0.08-0.13, accel=0.04-0.09)                 → score ~0.35-0.55
  PLATEAU   (g7=0.02-0.05, accel<0.02)                       → score ~0.05-0.18
  INORGANIC (g7=0.20-0.25, inorganic=0.84-0.93)             → score ~0.05-0.12

Until real breakout signal matures (~late Sep 2026), the deployed predict API serves
this model. Swap for a model retrained from Neon once the collected time-series is deep
enough.

Usage: python bake_model.py [OUT_PATH]   (default: models/baked.joblib)
"""

from __future__ import annotations

import sys

import numpy as np
import pandas as pd
from crescendo import FEATURES
from crescendo.model import CrescendoModel, _new_estimator


def build(seed: int = 42, n: int = 800) -> CrescendoModel:
    rng = np.random.default_rng(seed)

    # Build training data across 4 archetypes so the model learns to discriminate
    n_each = n // 4

    def _archetype(n_rows, g7_range, accel_range, inorganic_range, subs_range, noise=0.01):
        return {
            "subs":             rng.uniform(*subs_range, n_rows),
            "growth_7d":        rng.uniform(*g7_range, n_rows) + rng.normal(0, noise, n_rows),
            "growth_30d":       rng.uniform(g7_range[0]*3, g7_range[1]*4, n_rows),
            "accel":            rng.uniform(*accel_range, n_rows) + rng.normal(0, noise*0.5, n_rows),
            "consistency":      rng.uniform(0.85, 0.95, n_rows) if g7_range[0] > 0.1 else rng.uniform(0.45, 0.75, n_rows),
            "views_growth_7d":  rng.uniform(g7_range[0]*0.8, g7_range[1]*1.2, n_rows),
            "upload_rate_30d":  rng.uniform(4.0, 8.0, n_rows) if g7_range[0] > 0.1 else rng.uniform(0.3, 2.0, n_rows),
            "inorganic_score":  rng.uniform(*inorganic_range, n_rows),
        }

    rockets    = _archetype(n_each, (0.18, 0.26), (0.12, 0.21), (0.00, 0.05), (5_000, 2_000_000))
    climbers   = _archetype(n_each, (0.08, 0.14), (0.04, 0.10), (0.00, 0.08), (500_000, 80_000_000))
    plateau    = _archetype(n_each, (0.01, 0.05), (-0.01, 0.02),(0.00, 0.07), (5_000_000, 350_000_000))
    inorganic  = _archetype(n_each, (0.19, 0.26), (0.15, 0.22), (0.82, 0.95), (500_000, 100_000_000))

    combined = {k: np.concatenate([rockets[k], climbers[k], plateau[k], inorganic[k]]) for k in rockets}
    x = pd.DataFrame(combined).reindex(columns=FEATURES)

    # Target: strong positive weight on momentum + acceleration, heavy inorganic penalty
    # This gives rockets ~0.7-0.9, climbers ~0.35-0.55, plateau ~0.05-0.18, inorganic ~0.05-0.12
    y = (
        4.5 * x["growth_7d"]
        + 3.0 * x["accel"]
        + 1.5 * x["consistency"]
        + 1.0 * x["views_growth_7d"]
        - 3.5 * x["inorganic_score"]   # heavy penalty flips inorganic below plateau despite high g7
        + rng.normal(0, 0.02, len(x))
    )
    # Clip to [0,1] range for interpretable output
    y = y.clip(lower=0)

    est = _new_estimator("lgbm", cfg=None)
    est.fit(x, y)
    return CrescendoModel(
        booster=est,
        feature_names=list(FEATURES),
        model_kind="lgbm",
        dataset_version="synthetic-demo-v2",
        params=est.get_params(),
    )


def main() -> None:
    out = sys.argv[1] if len(sys.argv) > 1 else "models/baked.joblib"
    from pathlib import Path

    Path(out).parent.mkdir(parents=True, exist_ok=True)
    model = build()
    model.save(out)
    print(f"baked synthetic demo model v2 -> {out}")


if __name__ == "__main__":
    main()
