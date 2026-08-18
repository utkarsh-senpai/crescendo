"""Bake a deterministic synthetic CrescendoModel artifact for the demo deployment.

Until real breakout signal matures (~late Sep 2026, per L3 §12), the deployed predict API
serves a model trained on a fixed synthetic dataset — enough to exercise the /predict contract
and demo the transparent reasons end-to-end. This is DEMO data, not real picks: swap for a model
retrained from Neon once the collected time-series is deep enough.

Usage: python bake_model.py [OUT_PATH]   (default: models/baked.joblib)

The synthetic target keys on real momentum (growth_7d, accel) minus an inorganic_score penalty,
so feature_importances() reflect the organic-breakout framing and the reasons read sensibly.
"""

from __future__ import annotations

import sys

import numpy as np
import pandas as pd
from crescendo import FEATURES
from crescendo.model import CrescendoModel, _new_estimator


def build(seed: int = 42, n: int = 400) -> CrescendoModel:
    rng = np.random.default_rng(seed)
    data = {
        "subs": rng.integers(1_000, 100_000, n).astype(float),
        "growth_7d": rng.normal(0.02, 0.03, n),
        "growth_30d": rng.normal(0.08, 0.06, n),
        "accel": rng.normal(0.0, 0.02, n),
        "consistency": rng.uniform(0.0, 1.0, n),
        "views_growth_7d": rng.normal(0.02, 0.03, n),
        "upload_rate_30d": rng.integers(0, 10, n).astype(float),
        "inorganic_score": rng.uniform(0.0, 1.0, n),
    }
    x = pd.DataFrame(data).reindex(columns=FEATURES)
    y = 3.0 * x["growth_7d"] + 1.5 * x["accel"] - 0.5 * x["inorganic_score"] + rng.normal(0, 0.01, n)

    est = _new_estimator("lgbm", cfg=None)  # cfg unused by _new_estimator for lgbm
    est.fit(x, y)
    return CrescendoModel(
        booster=est,
        feature_names=list(FEATURES),
        model_kind="lgbm",
        dataset_version="synthetic-demo",
        params=est.get_params(),
    )


def main() -> None:
    out = sys.argv[1] if len(sys.argv) > 1 else "models/baked.joblib"
    from pathlib import Path

    Path(out).parent.mkdir(parents=True, exist_ok=True)
    build().save(out)
    print(f"baked synthetic demo model -> {out}")


if __name__ == "__main__":
    main()
