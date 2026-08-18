"""Shared domain types (L3 §13).

Frozen dataclasses that carry immutable facts across modules. They are dumb carriers:
all project invariants (band 1k-100k, gap tolerance, cold-start days) live in Config,
not here.
"""

from __future__ import annotations

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
    fetched_at: datetime  # UTC; when the API returned this


@dataclass(frozen=True)
class TrackedArtist:
    artist_id: int
    channel_id: str
    title: str
    genre: str
    subs_at_entry: int
    source: str  # 'seed' | 'snowball'
    discovered_at: datetime
    is_active: bool


@dataclass(frozen=True)
class Snapshot:
    artist_id: int
    captured_on: date  # UTC collection day (the raw_snapshot PK date)
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
    inorganic_score: float | None  # C3' data-quality signal (§3)
    suspected_inorganic: bool  # inorganic_score >= config threshold


@dataclass(frozen=True)
class EvalResult:
    cutoff: date
    fold_index: int
    n_train: int
    n_test: int
    k: int
    base_rate: float  # breakout prevalence in the test fold
    precision_at_k: float
    lift: float  # precision_at_k / base_rate
    ndcg_at_k: float
    roc_auc: float


# ---- Operation reports (returned by the C1/C2/C3 entrypoints; power the CLI + logs) ----


@dataclass(frozen=True)
class DiscoverReport:
    n_seed: int
    n_snowball: int
    n_rejected_band: int
    n_rejected_inactive: int
    units_spent: int


@dataclass(frozen=True)
class CollectReport:
    captured_on: date
    n_snapshotted: int
    n_failed: int
    n_deactivated: int
    units_spent: int


@dataclass(frozen=True)
class DatasetReport:
    n_rows: int
    n_artists: int
    n_skipped_coldstart: int
    n_skipped_nolabel: int
    version: str
