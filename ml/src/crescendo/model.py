"""Model artifact + predict seam (C4 -> future, L3 §19).

CrescendoModel is the ONE seam the future game + AI opponent depend on (L2 §5), so its
persisted form is a stable contract even in v0.1. predict() reindexes incoming columns to
the trained feature order so callers can't silently pass a mis-ordered frame.
"""

from __future__ import annotations

from typing import TYPE_CHECKING

import joblib

from . import FEATURES, MODEL_ARTIFACT_VERSION
from .config import Config

if TYPE_CHECKING:
    import pandas as pd


def _new_estimator(model_kind: str, cfg: Config):
    """Regressor on fwd_growth_30d; ranking to top-decile happens at eval time."""
    if model_kind == "lgbm":
        from lightgbm import LGBMRegressor

        return LGBMRegressor(
            n_estimators=300,
            learning_rate=0.05,
            num_leaves=31,
            subsample=0.8,
            colsample_bytree=0.8,
            random_state=42,
            n_jobs=-1,
        )
    if model_kind == "xgb":
        from xgboost import XGBRegressor

        return XGBRegressor(
            n_estimators=300,
            learning_rate=0.05,
            max_depth=6,
            subsample=0.8,
            colsample_bytree=0.8,
            random_state=42,
            n_jobs=-1,
        )
    raise ValueError(f"unknown model_kind: {model_kind!r} (expected 'lgbm' or 'xgb')")


class CrescendoModel:
    """Fitted estimator + metadata; the persisted artifact and predict() seam."""

    def __init__(self, booster, feature_names: list[str], model_kind: str,
                 dataset_version: str, params: dict):
        self._booster = booster
        self.feature_names = feature_names
        self.model_kind = model_kind
        self.dataset_version = dataset_version
        self.params = params

    def predict(self, features: pd.DataFrame) -> pd.Series:
        """breakout_score per row. Reindexes to trained feature order (missing->NaN)."""
        import pandas as pd

        # Unknown extra columns are fine to ignore, but a caller passing NONE of the
        # trained features is a contract violation (protects the future HTTP seam, §11).
        if not set(features.columns) & set(self.feature_names):
            raise ValueError(
                "predict() received no known feature columns; "
                f"expected some of {self.feature_names}"
            )
        aligned = features.reindex(columns=self.feature_names)
        scores = self._booster.predict(aligned)
        return pd.Series(scores, index=features.index, name="breakout_score")

    def feature_importances(self) -> dict[str, float]:
        """Normalized (sum=1.0) importances -> drives the transparent-AI `reasons` (§11)."""
        raw = getattr(self._booster, "feature_importances_", None)
        if raw is None:
            return {}
        total = float(sum(raw)) or 1.0
        return {name: float(v) / total for name, v in zip(self.feature_names, raw)}

    def save(self, path: str) -> None:
        joblib.dump(
            {
                "schema": MODEL_ARTIFACT_VERSION,
                "model_kind": self.model_kind,
                "booster": self._booster,
                "feature_names": self.feature_names,
                "dataset_version": self.dataset_version,
                "params": self.params,
            },
            path,
        )

    @classmethod
    def load(cls, path: str) -> CrescendoModel:
        blob = joblib.load(path)
        if blob.get("schema") != MODEL_ARTIFACT_VERSION:
            raise ValueError(
                f"model artifact schema {blob.get('schema')} != expected {MODEL_ARTIFACT_VERSION}"
            )
        return cls(
            booster=blob["booster"],
            feature_names=blob["feature_names"],
            model_kind=blob["model_kind"],
            dataset_version=blob["dataset_version"],
            params=blob.get("params", {}),
        )


def train(df_train: pd.DataFrame, cfg: Config, model_kind: str) -> CrescendoModel:
    """Fit a regressor on the §3 features against fwd_growth_30d."""
    from .dataset import dataset_version

    est = _new_estimator(model_kind, cfg)
    x = df_train.reindex(columns=FEATURES)
    y = df_train["fwd_growth_30d"]
    est.fit(x, y)
    return CrescendoModel(
        booster=est,
        feature_names=list(FEATURES),
        model_kind=model_kind,
        dataset_version=dataset_version(cfg),
        params=est.get_params(),
    )
