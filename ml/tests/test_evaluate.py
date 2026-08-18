"""Temporal-split + per-fold decile + precision@k tests (L3 §9, §18)."""

from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd

from crescendo.evaluate import (
    fold_labels,
    make_folds,
    ndcg_at_k,
    precision_at_k,
    resolve_k,
    topk_rate,
)


def test_fold_labels_are_top_decile_of_own_distribution():
    vals = np.arange(100, dtype=float)  # 0..99
    y = fold_labels(vals, 0.10)
    # top 10% => values >= 90th percentile (~89.1) -> the top ~10 values
    assert y.sum() == 10
    assert y[-1] == 1 and y[0] == 0


def test_per_fold_threshold_differs_from_global():
    # Crafted regression test for the §4 rule: a global threshold mislabels a fold whose
    # local distribution is entirely below the global one.
    low = np.array([0.0, 0.1, 0.2, 0.3, 0.4])  # a "cold" test fold
    high = np.concatenate([low, np.array([5.0, 6.0, 7.0, 8.0, 9.0])])  # global (future-incl.)

    global_thr = np.quantile(high, 0.9)
    local_thr = np.quantile(low, 0.9)
    assert local_thr < global_thr  # global threshold is inflated by the hot rows

    y_local = fold_labels(low, 0.10)
    y_global = (low >= global_thr).astype(int)
    # Per-fold labeling finds a breakout within the cold fold; global labeling finds none.
    assert y_local.sum() >= 1
    assert y_global.sum() == 0


def test_resolve_k_auto_matches_positive_count():
    y = np.array([0, 0, 0, 0, 0, 0, 0, 0, 0, 1])  # 10 rows, decile -> k=1
    assert resolve_k("auto", y, 0.10) == 1
    assert resolve_k(3, y, 0.10) == 3
    assert resolve_k("auto", np.zeros(3), 0.10) == 1  # guard k>=1


def test_precision_at_k_arithmetic():
    y = np.array([1, 0, 1, 0, 0])
    scores = np.array([0.9, 0.8, 0.7, 0.1, 0.0])  # top-2 = indices 0,1 -> 1 hit
    assert precision_at_k(y, scores, 2) == 0.5
    # perfect ranking
    assert precision_at_k(np.array([1, 1, 0, 0]), np.array([0.9, 0.8, 0.1, 0.0]), 2) == 1.0


def test_topk_rate_counts_flagged_share_of_picks():
    # inorganic_rate@k: of the top-k scored picks, what fraction carry the flag.
    flags = np.array([True, False, True, False, False])
    scores = np.array([0.9, 0.8, 0.7, 0.1, 0.0])  # top-3 = indices 0,1,2 -> 2 flagged
    assert topk_rate(flags, scores, 3) == 2 / 3
    assert topk_rate(np.zeros(5, dtype=bool), scores, 3) == 0.0
    assert topk_rate(flags, scores, 0) == 0.0  # k guard


def test_organic_label_excludes_flagged_breakouts():
    # The reframed target: organic breakout = top-decile growth AND not suspected_inorganic.
    # A pumped channel with the highest forward growth must NOT count as an organic breakout.
    fwd = np.array([0.1, 0.2, 0.9, 0.95])  # indices 2,3 are top growth
    suspected = np.array([False, False, False, True])  # index 3 is pumped
    y = fold_labels(fwd, 0.50)  # top half -> indices 2,3 labeled breakout
    y_org = (y.astype(bool) & ~suspected).astype(int)
    assert y[3] == 1 and y_org[3] == 0  # pumped breakout dropped from organic
    assert y_org[2] == 1  # genuine breakout retained


def test_ndcg_perfect_ranking_is_one():
    y = np.array([1, 1, 0, 0])
    scores = np.array([0.9, 0.8, 0.1, 0.0])
    assert ndcg_at_k(y, scores, 2) == 1.0


def test_temporal_split_never_leaks_future_into_train():
    days = [date(2026, 6, 1) + timedelta(days=i) for i in range(60)]
    df = pd.DataFrame({"as_of_date": days, "fwd_growth_30d": np.random.default_rng(0).random(60)})

    class C:
        forward_days = 30
        walk_forward_folds = 2

    folds = make_folds(df, date(2026, 6, 20), C(), walk_forward=True)
    for c, train_idx, test_idx in folds:
        if len(train_idx) and len(test_idx):
            assert df.loc[train_idx, "as_of_date"].max() < df.loc[test_idx, "as_of_date"].min()
