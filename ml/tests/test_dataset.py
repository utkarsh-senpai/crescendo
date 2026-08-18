"""Leakage + cold-start + label tests (L3 §9).

The correctness gate: no feature may use a snapshot after as_of, and rows without a
realized forward label are excluded (never imputed). We assert this structurally by
spying on which snapshots feature code is allowed to see.
"""

from __future__ import annotations

from datetime import date, timedelta

from conftest import make_history

from crescendo.dataset import _cold_start_ok, dataset_version
from crescendo.features import compute_features, forward_growth


def test_no_feature_uses_future_snapshot(cfg):
    # If features only ever read snapshots <= as_of, then truncating the history at as_of
    # must not change the computed feature row. This is the leakage invariant.
    hist = make_history(1, date(2026, 6, 1), days=70)
    as_of = date(2026, 7, 10)
    full = compute_features(hist, as_of, cfg)
    truncated = compute_features([s for s in hist if s.captured_on <= as_of], as_of, cfg)
    assert full == truncated


def test_label_uses_only_forward_window(cfg):
    # forward_growth must change if the t+30 snapshot changes, and must NOT depend on
    # snapshots after t+30.
    hist = make_history(1, date(2026, 6, 1), days=70)
    as_of = date(2026, 6, 15)
    base = forward_growth(hist, as_of, cfg)
    # mutate a snapshot far beyond the label window -> label unchanged
    tail = []
    for s in hist:
        if s.captured_on > as_of + timedelta(days=cfg.forward_days):
            tail.append(type(s)(s.artist_id, s.captured_on, s.subscribers + 999_999,
                                s.total_views, s.video_count))
        else:
            tail.append(s)
    assert forward_growth(tail, as_of, cfg) == base


def test_cold_start_excludes_short_history(cfg):
    short = make_history(1, date(2026, 6, 1), days=10)
    assert _cold_start_ok(short, date(2026, 6, 9), cfg) is False
    long = make_history(1, date(2026, 6, 1), days=60)
    assert _cold_start_ok(long, date(2026, 7, 25), cfg) is True


def test_dataset_version_changes_with_params(cfg):
    v1 = dataset_version(cfg)
    v2 = dataset_version(type(cfg)(**{**cfg.__dict__, "short_window": 5}))
    assert v1 != v2
    # decile is a fold-time concern -> NOT in the hash
    v3 = dataset_version(type(cfg)(**{**cfg.__dict__, "breakout_decile": 0.2}))
    assert v1 == v3
