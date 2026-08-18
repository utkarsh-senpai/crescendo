"""Shared fixtures: a tiny real CrescendoModel + a serving app wired to it.

We build the model artifact directly (fit a LightGBM regressor, wrap in CrescendoModel) rather
than going through crescendo.model.train — that keeps the fixture free of a DB/Config while
still exercising the REAL predict() + feature_importances() seam the service depends on.
"""

from __future__ import annotations

import numpy as np
import pytest
from crescendo import FEATURES
from crescendo.model import CrescendoModel, _new_estimator

from crescendo_serving.service import PredictService


@pytest.fixture(scope="session")
def trained_model() -> CrescendoModel:
    rng = np.random.default_rng(42)
    n = 400
    # Synthetic features roughly on the scale the real pipeline produces.
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
    import pandas as pd

    x = pd.DataFrame(data).reindex(columns=FEATURES)
    # Target keyed on real momentum so growth_7d/accel earn importance (organic signal),
    # minus a penalty for inorganic_score so the model doesn't reward inflated growth.
    y = 3.0 * x["growth_7d"] + 1.5 * x["accel"] - 0.5 * x["inorganic_score"] + rng.normal(0, 0.01, n)

    est = _new_estimator("lgbm", cfg=None)  # cfg unused by _new_estimator for lgbm
    est.fit(x, y)
    return CrescendoModel(
        booster=est,
        feature_names=list(FEATURES),
        model_kind="lgbm",
        dataset_version="test-fixture",
        params=est.get_params(),
    )


@pytest.fixture(scope="session")
def service(trained_model: CrescendoModel) -> PredictService:
    return PredictService(model=trained_model, inorganic_threshold=0.8)


@pytest.fixture()
def client(service: PredictService):
    from fastapi.testclient import TestClient

    from crescendo_serving.app import app

    app.state.service = service
    with TestClient(app) as c:
        yield c
    app.state.service = None
