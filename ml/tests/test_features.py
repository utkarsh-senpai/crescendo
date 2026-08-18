"""Feature-math unit tests (L3 §9): each feature matches the §3 formula; NULL on gaps."""

from __future__ import annotations

from datetime import date, timedelta

import pytest
from conftest import make_history

from crescendo.features import compute_features, forward_growth


def test_growth_matches_formula(cfg, steady_history):
    as_of = date(2026, 7, 15)
    row = compute_features(steady_history, as_of, cfg)

    # steady +100 subs/day from start_subs=10000
    def subs_on(d: date) -> int:
        return 10_000 + 100 * (d - date(2026, 6, 1)).days

    s_now = subs_on(as_of)
    s_7 = subs_on(as_of - timedelta(days=7))
    s_30 = subs_on(as_of - timedelta(days=30))
    assert row.subs == s_now
    assert row.growth_7d == pytest.approx((s_now - s_7) / s_7)
    assert row.growth_30d == pytest.approx((s_now - s_30) / s_30)


def test_upload_rate_is_video_count_delta(cfg, steady_history):
    as_of = date(2026, 7, 15)
    row = compute_features(steady_history, as_of, cfg)
    # a new video every 10 days -> ~3 over 30 days
    assert row.upload_rate_30d == pytest.approx(3.0, abs=1.0)


def test_accel_zero_on_linear_growth(cfg, steady_history):
    # Linear subscriber growth => 7d growth is (slightly) decreasing but accel is small/neg,
    # never positive. On a strictly linear series the second-order term is <= 0.
    row = compute_features(steady_history, date(2026, 7, 15), cfg)
    assert row.accel is not None
    assert row.accel <= 1e-9


def test_consistency_high_for_steady_series(cfg, steady_history):
    row = compute_features(steady_history, date(2026, 7, 15), cfg)
    # zero variance in daily gains -> stdev 0 -> consistency == 1.0
    assert row.consistency == pytest.approx(1.0)


def test_null_when_gap_exceeds_tolerance(cfg):
    # Build history missing the t-7 neighborhood beyond tolerance.
    start = date(2026, 6, 1)
    hist = make_history(1, start, days=40)
    as_of = date(2026, 7, 10)
    # Drop snapshots around t-7 (July 3) wide enough that the nearest earlier snapshot is
    # > tolerance (3d) away: remove [t-11, t-4] so the closest remaining is t-12 (gap 5 > 3).
    lo, hi = as_of - timedelta(days=11), as_of - timedelta(days=4)
    hist = [s for s in hist if not (lo <= s.captured_on <= hi)]
    row = compute_features(hist, as_of, cfg)
    assert row.growth_7d is None


def test_forward_growth_matches_and_nulls_without_future(cfg):
    start = date(2026, 6, 1)
    hist = make_history(1, start, days=70)
    as_of = date(2026, 6, 20)
    fg = forward_growth(hist, as_of, cfg)
    s_now = 10_000 + 100 * 19
    s_fwd = 10_000 + 100 * 49
    assert fg == pytest.approx((s_fwd - s_now) / s_now)

    # No snapshot near as_of+30 -> None (never imputed).
    short = make_history(1, start, days=25)
    assert forward_growth(short, as_of, cfg) is None


def test_inorganic_score_high_on_overnight_spike(cfg):
    # Steady series, then one enormous single-day subscriber jump with flat views.
    start = date(2026, 6, 1)
    hist = make_history(1, start, days=40, daily_sub_gain=50)
    spike_day = date(2026, 7, 5)
    bumped = []
    for s in hist:
        if s.captured_on >= spike_day:
            bumped.append(
                type(s)(s.artist_id, s.captured_on, s.subscribers + 20_000,
                        s.total_views, s.video_count)
            )
        else:
            bumped.append(s)
    row = compute_features(bumped, spike_day, cfg)
    assert row.inorganic_score is not None
    assert row.inorganic_score > 0.5
