"""Prediction service: wraps CrescendoModel.predict into the ranked+explained response.

Holds a loaded model artifact and the inorganic threshold (for authenticity reasons). The
FastAPI layer (app.py) owns one PredictService for the process lifetime; this module has no
FastAPI imports so the ranking logic is testable without HTTP.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

from crescendo.model import CrescendoModel

from .reasons import build_reasons
from .schemas import ArtistFeatures, PredictResponse, RankedArtist

# Repo layout: serving/src/crescendo_serving/service.py -> repo root is parents[3].
_REPO_ROOT = Path(__file__).resolve().parents[3]
_DEFAULT_CONFIG = _REPO_ROOT / "ml" / "config" / "crescendo.toml"


@dataclass
class PredictService:
    model: CrescendoModel
    inorganic_threshold: float

    @classmethod
    def from_env(cls) -> PredictService:
        """Build from MODEL_PATH + CRESCENDO_CONFIG env vars (used by the FastAPI app)."""
        model_path = os.environ.get("MODEL_PATH")
        if not model_path:
            raise RuntimeError(
                "MODEL_PATH is not set — point it at a CrescendoModel .joblib artifact "
                "(produced by `crescendo train`)."
            )
        config_path = os.environ.get("CRESCENDO_CONFIG", str(_DEFAULT_CONFIG))
        return cls.from_paths(model_path, config_path)

    @classmethod
    def from_paths(cls, model_path: str, config_path: str) -> PredictService:
        model = CrescendoModel.load(model_path)
        threshold = _load_inorganic_threshold(config_path)
        return cls(model=model, inorganic_threshold=threshold)

    def predict(self, as_of_date, artists: list[ArtistFeatures]) -> PredictResponse:
        """Score + rank + explain. Ties broken by artist_id for a deterministic ordering."""
        import pandas as pd
        import numpy as np

        # Build a frame whose columns are every feature any caller sent; predict() reindexes
        # to the model's trained feature order (missing -> NaN), so extra/absent keys are safe.
        rows = [a.features for a in artists]
        frame = pd.DataFrame(rows)
        scores = self.model.predict(frame)  # pd.Series aligned to frame.index

        # Discovery Edge (v1.5): how much smarter is this pick vs naive cohort momentum?
        # Cohort baseline = expected breakout score for an artist at this momentum level.
        # Approximated as the cohort median score (no artist is unfairly penalised for being
        # in a hot cohort — edge measures how far above the average this pick is).
        scores_arr = scores.values.astype(float)
        cohort_median = float(np.median(scores_arr)) if len(scores_arr) > 0 else 0.0
        cohort_p25 = float(np.percentile(scores_arr, 25)) if len(scores_arr) > 0 else 0.0
        cohort_p75 = float(np.percentile(scores_arr, 75)) if len(scores_arr) > 0 else 1.0

        def _confidence_tier(score: float) -> str:
            # Proxy for uncertainty using position in cohort distribution.
            # v1.7 will replace this with proper conformal prediction intervals (W-TQA).
            # Artists far above the median are high-confidence picks; near-median are uncertain.
            if score >= cohort_p75:
                return "HIGH"
            elif score >= cohort_median:
                return "MEDIUM"
            return "LOW"

        importances = self.model.feature_importances()
        scored = [
            (
                artists[i].artist_id,
                float(scores.iloc[i]),
                build_reasons(artists[i].features, importances, self.inorganic_threshold),
                round(float(scores.iloc[i]) - cohort_median, 4),  # discovery_edge
                _confidence_tier(float(scores.iloc[i])),
            )
            for i in range(len(artists))
        ]
        # Descending by score; stable tiebreak on artist_id so equal scores rank reproducibly.
        scored.sort(key=lambda t: (-t[1], t[0]))

        ranked = [
            RankedArtist(
                artist_id=aid,
                breakout_score=score,
                rank=idx + 1,
                reasons=reasons,
                discovery_edge=edge,
                confidence_tier=tier,
            )
            for idx, (aid, score, reasons, edge, tier) in enumerate(scored)
        ]
        return PredictResponse(
            as_of_date=as_of_date,
            model_kind=self.model.model_kind,
            dataset_version=self.model.dataset_version,
            ranked=ranked,
        )


def _load_inorganic_threshold(config_path: str) -> float:
    """Read dataquality.inorganic_threshold from the toml (default 0.8 if absent).

    We read the single value directly rather than load_config() so the service needs no DB
    URL / API key / seed file just to phrase authenticity reasons.
    """
    import tomllib

    p = Path(config_path)
    if not p.exists():
        return 0.8
    with p.open("rb") as fh:
        raw = tomllib.load(fh)
    return float(raw.get("dataquality", {}).get("inorganic_threshold", 0.8))
