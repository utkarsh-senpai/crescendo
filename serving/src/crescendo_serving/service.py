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

        # v1.7: Cross-sectional conformal prediction intervals (W-TQA / leave-one-out calibration).
        # For each artist i, the calibration set is all OTHER artists at the same time step.
        # calibration_scores[j] = |score_j - cohort_median| for all j != i
        # interval_width_i = quantile(calibration_scores excl. i, 0.80)  -> 80% nominal coverage
        # This is the TQA/W-TQA approach: cross-sectional rather than held-out time split,
        # which is appropriate for n=43–55 panel artists.
        n = len(scores_arr)
        nonconf_scores = np.abs(scores_arr - cohort_median)  # |score - median| for all artists

        interval_widths = []
        for i in range(n):
            # Leave-one-out: calibrate using all other artists
            cal = np.concatenate([nonconf_scores[:i], nonconf_scores[i + 1:]])
            if len(cal) == 0:
                width = float(nonconf_scores[i])
            else:
                width = float(np.quantile(cal, 0.80))
            interval_widths.append(width)

        widths_arr = np.array(interval_widths)
        # Confidence tier from interval width relative to panel median width:
        # tight interval (below median) = HIGH confidence; above 75th = LOW
        median_width = float(np.median(widths_arr)) if n > 0 else 1.0
        p75_width = float(np.percentile(widths_arr, 75)) if n > 0 else 1.0

        def _confidence_tier_from_width(width: float) -> str:
            if width < median_width:
                return "HIGH"
            elif width < p75_width:
                return "MEDIUM"
            return "LOW"

        importances = self.model.feature_importances()
        scored = [
            (
                artists[i].artist_id,
                float(scores.iloc[i]),
                build_reasons(artists[i].features, importances, self.inorganic_threshold),
                round(float(scores.iloc[i]) - cohort_median, 4),  # discovery_edge
                _confidence_tier_from_width(interval_widths[i]),
                round(float(scores.iloc[i]) - interval_widths[i], 4),  # prediction_interval_lo
                round(float(scores.iloc[i]) + interval_widths[i], 4),  # prediction_interval_hi
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
                prediction_interval_lo=lo,
                prediction_interval_hi=hi,
            )
            for idx, (aid, score, reasons, edge, tier, lo, hi) in enumerate(scored)
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
