"""Dataset assembly (C3, L3 §17).

Assembles the reproducible modeling dataset from raw snapshots: cohort eligibility +
cold-start guard + as-of features + forward label. is_breakout is written NULL on purpose
— the top-decile label is assigned per-fold at eval time, never at build time.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict
from datetime import date, timedelta

from . import DATASET_SCHEMA_VERSION
from . import logging as log
from .config import Config
from .db import Db
from .features import compute_features, forward_growth
from .types import DatasetReport, Snapshot


def dataset_version(cfg: Config) -> str:
    """Short hash binding a dataset to the exact params that made it (§17).

    The label decile is intentionally NOT in the hash — labeling is a fold-time concern.
    """
    payload = json.dumps(
        {
            "genre": cfg.genre_name,
            "subs_min": cfg.subs_min,
            "subs_max": cfg.subs_max,
            "min_history_days": cfg.min_history_days,
            "gap_tol": cfg.snapshot_gap_tolerance_days,
            "short": cfg.short_window,
            "long": cfg.long_window,
            "consistency": cfg.consistency_window,
            "forward_days": cfg.forward_days,
            "dq_mode": cfg.dq_mode,
            "schema": DATASET_SCHEMA_VERSION,
        },
        sort_keys=True,
    )
    return hashlib.sha1(payload.encode()).hexdigest()[:12]


def _daterange(start: date, end: date):
    d = start
    while d <= end:
        yield d
        d += timedelta(days=1)


def _cold_start_ok(history: list[Snapshot], as_of: date, cfg: Config) -> bool:
    """Require at least min_history_days of coverage at/before as_of."""
    older = [s for s in history if s.captured_on <= as_of]
    if len(older) < 2:
        return False
    span = (as_of - older[0].captured_on).days
    return span >= cfg.min_history_days


def build_dataset(cfg: Config, db: Db, as_of_start: date, as_of_end: date) -> DatasetReport:
    version = dataset_version(cfg)
    rows: list[dict] = []
    n_artists = 0
    n_skipped_coldstart = 0
    n_skipped_nolabel = 0
    n_skipped_inorganic = 0
    soft_cap = int(cfg.subs_max * cfg.subs_max_soft_multiplier)

    label_horizon = as_of_end + timedelta(days=cfg.forward_days)

    for artist in db.active_artists():
        # Full history incl. the label window; feature window is sliced per as_of below.
        hist_full = db.history_for(artist.artist_id, until=label_horizon)
        if not hist_full:
            continue
        n_artists += 1

        for as_of in _daterange(as_of_start, as_of_end):
            hist = [s for s in hist_full if s.captured_on <= as_of]  # feature window
            if not _cold_start_ok(hist, as_of, cfg):
                n_skipped_coldstart += 1
                log.debug("dataset.skip", artist_id=artist.artist_id, reason="coldstart",
                          as_of=str(as_of))
                continue

            feats = compute_features(hist, as_of, cfg)  # PURE

            # soft upper guard so runaway breakouts don't dominate
            if feats.subs > soft_cap:
                continue

            label = forward_growth(hist_full, as_of, cfg)  # reads (as_of, +forward_days]
            if label is None:
                n_skipped_nolabel += 1
                log.debug("dataset.skip", artist_id=artist.artist_id, reason="nolabel",
                          as_of=str(as_of))
                continue

            # C3' exclude mode: drop suspected-inorganic rows from the modeling set.
            if cfg.dq_mode in ("exclude", "both") and feats.suspected_inorganic:
                n_skipped_inorganic += 1
                log.debug("dataset.skip", artist_id=artist.artist_id, reason="inorganic",
                          as_of=str(as_of), score=feats.inorganic_score)
                continue

            row = asdict(feats)  # includes all §3 features + suspected_inorganic
            row["fwd_growth_30d"] = label
            row["is_breakout"] = None  # set per-fold at eval time (§4)
            rows.append(row)

    written = db.write_dataset(rows, version)
    log.info("dataset.done", n_rows=written, n_artists=n_artists,
             skipped_coldstart=n_skipped_coldstart, skipped_nolabel=n_skipped_nolabel,
             skipped_inorganic=n_skipped_inorganic, version=version)
    return DatasetReport(
        n_rows=written,
        n_artists=n_artists,
        n_skipped_coldstart=n_skipped_coldstart,
        n_skipped_nolabel=n_skipped_nolabel,
        version=version,
    )
