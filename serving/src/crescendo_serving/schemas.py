"""Request/response schemas for POST /predict (the L3 §11 HTTP contract).

Pydantic models are the wire contract the game backend + AI opponent code against. Feature
values arrive as a free-form dict so callers only send what they have; the service reindexes
to the model's trained feature order (missing -> NaN) exactly like CrescendoModel.predict does
in-process, so the HTTP seam and the Python seam behave identically.
"""

from __future__ import annotations

from datetime import date

from pydantic import BaseModel, Field


class ArtistFeatures(BaseModel):
    """One artist's as-of feature vector. `features` keys are FEATURES names (extras ignored)."""

    artist_id: int
    features: dict[str, float | None] = Field(default_factory=dict)


class PredictRequest(BaseModel):
    as_of_date: date
    artists: list[ArtistFeatures] = Field(min_length=1)


class RankedArtist(BaseModel):
    artist_id: int
    breakout_score: float
    rank: int  # 1-based, descending by breakout_score
    reasons: list[str]
    # v1.5: discovery edge — how much smarter is this pick vs naive cohort momentum
    discovery_edge: float | None = None  # predicted_growth - cohort_baseline_expected_growth
    confidence_tier: str | None = None   # "HIGH" | "MEDIUM" | "LOW" (uncertainty proxy; v1.7 will add proper conformal intervals)


class PredictResponse(BaseModel):
    as_of_date: date
    model_kind: str
    dataset_version: str
    ranked: list[RankedArtist]


class HealthResponse(BaseModel):
    status: str
    version: str
    model_loaded: bool
    model_kind: str | None = None
    dataset_version: str | None = None
    n_features: int | None = None
