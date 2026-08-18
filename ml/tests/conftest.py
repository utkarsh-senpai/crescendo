"""Shared test fixtures: a Config built in-memory and synthetic snapshot time-series."""

from __future__ import annotations

from datetime import date, timedelta

import pytest

from crescendo.config import Config
from crescendo.types import Snapshot


@pytest.fixture
def cfg() -> Config:
    return Config(
        genre_name="electronic",
        seed_file="seeds/electronic_playlists.txt",
        snowball_max_depth=1,
        subs_min=1000,
        subs_max=100000,
        min_history_days=45,
        snapshot_gap_tolerance_days=3,
        subs_max_soft_multiplier=1.5,
        short_window=7,
        long_window=30,
        consistency_window=14,
        forward_days=30,
        breakout_decile=0.10,
        cutoff=date(2026, 9, 15),
        k="auto",
        walk_forward_folds=3,
        daily_unit_ceiling=9500,
        search_list_call_ceiling=90,
        dq_mode="feature",
        inorganic_threshold=0.8,
        w_subs_jump=0.5,
        w_subs_views_divergence=0.3,
        w_step_discontinuity=0.2,
        youtube_api_key="test",
        database_url="postgresql://test",
        config_dir=".",  # type: ignore[arg-type]
    )


def make_history(
    artist_id: int,
    start: date,
    days: int,
    daily_sub_gain: int = 100,
    daily_view_gain: int = 5000,
    start_subs: int = 10_000,
    start_views: int = 500_000,
    start_videos: int = 20,
    video_every: int = 10,
) -> list[Snapshot]:
    """Steady, gap-free daily series — the well-behaved baseline for feature tests."""
    out = []
    for d in range(days):
        day = start + timedelta(days=d)
        out.append(
            Snapshot(
                artist_id=artist_id,
                captured_on=day,
                subscribers=start_subs + daily_sub_gain * d,
                total_views=start_views + daily_view_gain * d,
                video_count=start_videos + d // video_every,
            )
        )
    return out


@pytest.fixture
def steady_history():
    return make_history(artist_id=1, start=date(2026, 6, 1), days=70)
