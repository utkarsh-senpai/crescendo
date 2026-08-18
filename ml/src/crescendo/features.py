"""Feature computation (C3, L3 §3).

PURE functions: snapshots -> as-of features. No DB, no I/O, no wall-clock reads, so they
are fully unit-testable and reproducible. Every feature uses ONLY snapshots with
captured_on <= as_of (leakage-safe); forward_growth reads the separate label window.
"""

from __future__ import annotations

import statistics
from datetime import date, timedelta

from .config import Config
from .types import FeatureRow, Snapshot


def _value_asof(history: list[Snapshot], target: date, tolerance_days: int) -> Snapshot | None:
    """Nearest snapshot at/before `target`, if within tolerance of target; else None.

    history must be ascending by captured_on. We take the most recent snapshot whose
    captured_on <= target, and require target - captured_on <= tolerance.
    """
    best: Snapshot | None = None
    for s in history:
        if s.captured_on <= target:
            best = s
        else:
            break
    if best is None:
        return None
    if (target - best.captured_on).days > tolerance_days:
        return None
    return best


def _rel_growth(curr: int, prev: int) -> float:
    return (curr - prev) / max(prev, 1)


def compute_features(history: list[Snapshot], as_of: date, cfg: Config) -> FeatureRow:
    """Compute the §3 as-of feature vector. Missing windows -> None (row dropped downstream)."""
    tol = cfg.snapshot_gap_tolerance_days
    sw, lw, cw = cfg.short_window, cfg.long_window, cfg.consistency_window

    s_now = _value_asof(history, as_of, tol)
    subs_now = s_now.subscribers if s_now else 0

    s_short = _value_asof(history, as_of - timedelta(days=sw), tol)
    s_long = _value_asof(history, as_of - timedelta(days=lw), tol)

    growth_7d = _rel_growth(subs_now, s_short.subscribers) if (s_now and s_short) else None
    growth_30d = _rel_growth(subs_now, s_long.subscribers) if (s_now and s_long) else None

    # accel = growth_7d - (7d growth as of t-7)
    accel = None
    if growth_7d is not None:
        s_prev_now = _value_asof(history, as_of - timedelta(days=sw), tol)
        s_prev_ref = _value_asof(history, as_of - timedelta(days=2 * sw), tol)
        if s_prev_now and s_prev_ref:
            growth_7d_prev = _rel_growth(s_prev_now.subscribers, s_prev_ref.subscribers)
            accel = growth_7d - growth_7d_prev

    # consistency = 1 / (1 + stdev(daily growth over trailing cw days))
    consistency = _consistency(history, as_of, cw, tol)

    # views momentum
    views_growth_7d = None
    if s_now and s_short:
        views_growth_7d = _rel_growth(s_now.total_views, s_short.total_views)

    # upload rate over long window (count delta)
    upload_rate_30d = None
    if s_now and s_long:
        upload_rate_30d = float(s_now.video_count - s_long.video_count)

    inorganic_score = _inorganic_score(
        history, as_of, cfg, growth_7d, views_growth_7d
    )
    suspected = (
        inorganic_score is not None and inorganic_score >= cfg.inorganic_threshold
    )

    return FeatureRow(
        artist_id=history[0].artist_id if history else 0,
        as_of_date=as_of,
        subs=subs_now,
        growth_7d=growth_7d,
        growth_30d=growth_30d,
        accel=accel,
        consistency=consistency,
        views_growth_7d=views_growth_7d,
        upload_rate_30d=upload_rate_30d,
        inorganic_score=inorganic_score,
        suspected_inorganic=suspected,
    )


def _daily_deltas(history: list[Snapshot], as_of: date, window: int, tol: int) -> list[int]:
    """Day-over-day subscriber deltas across the trailing `window` days ending at as_of."""
    deltas: list[int] = []
    for d in range(window):
        day = as_of - timedelta(days=d)
        prev = as_of - timedelta(days=d + 1)
        s_day = _value_asof(history, day, tol)
        s_prev = _value_asof(history, prev, tol)
        if s_day and s_prev and s_day.captured_on != s_prev.captured_on:
            deltas.append(s_day.subscribers - s_prev.subscribers)
    return deltas


def _consistency(history: list[Snapshot], as_of: date, window: int, tol: int) -> float | None:
    deltas = _daily_deltas(history, as_of, window, tol)
    if len(deltas) < 2:
        return None
    return 1.0 / (1.0 + statistics.pstdev(deltas))


def _inorganic_score(
    history: list[Snapshot],
    as_of: date,
    cfg: Config,
    growth_7d: float | None,
    views_growth_7d: float | None,
) -> float | None:
    """C3' inorganic-growth suspicion in [0,1] from same <=as_of snapshots (leakage-safe).

    Blends three cheap anomaly signals, each squashed to [0,1], weighted per config.
    Returns None if there isn't enough history to compute any signal.
    """
    tol = cfg.snapshot_gap_tolerance_days
    deltas = _daily_deltas(history, as_of, cfg.long_window, tol)
    if len(deltas) < 2:
        return None

    # 1) Subs-jump z-score: latest daily delta vs the artist's own trailing mean/stdev.
    recent = _daily_deltas(history, as_of, cfg.short_window, tol)
    latest = recent[0] if recent else (deltas[0] if deltas else 0)
    mean = statistics.fmean(deltas)
    sd = statistics.pstdev(deltas)
    z = (latest - mean) / sd if sd > 0 else 0.0
    jump_sig = _squash(max(z, 0.0) / 3.0)  # ~3 sigma -> saturates

    # 2) Subs-vs-views divergence: subs surge while views stay flat (bought-subs signature).
    if growth_7d is not None and views_growth_7d is not None:
        divergence = max(growth_7d - views_growth_7d, 0.0)
        div_sig = _squash(divergence / 0.10)  # 10pp gap -> strong
    else:
        div_sig = 0.0

    # 3) Step discontinuity: largest single-day gain / total 7d gain.
    week = _daily_deltas(history, as_of, cfg.short_window, tol)
    pos = [d for d in week if d > 0]
    total = sum(pos)
    step_sig = (max(pos) / total) if total > 0 else 0.0

    blend = (
        cfg.w_subs_jump * jump_sig
        + cfg.w_subs_views_divergence * div_sig
        + cfg.w_step_discontinuity * step_sig
    )
    wsum = cfg.w_subs_jump + cfg.w_subs_views_divergence + cfg.w_step_discontinuity
    return blend / wsum if wsum > 0 else 0.0


def _squash(x: float) -> float:
    """Clamp a non-negative ratio into [0,1]."""
    if x <= 0:
        return 0.0
    return min(x, 1.0)


def forward_growth(history: list[Snapshot], as_of: date, cfg: Config) -> float | None:
    """Relative subscriber growth over (as_of, as_of + forward_days].

    Requires a snapshot near as_of and near as_of + forward_days (within tolerance);
    returns None otherwise (never imputed — that would fabricate an outcome).
    """
    tol = cfg.snapshot_gap_tolerance_days
    s_now = _value_asof(history, as_of, tol)
    target = as_of + timedelta(days=cfg.forward_days)
    s_fwd = _value_asof(history, target, tol)
    if s_now is None or s_fwd is None:
        return None
    if s_fwd.captured_on <= as_of:  # no genuine forward snapshot within tolerance
        return None
    return _rel_growth(s_fwd.subscribers, s_now.subscribers)
