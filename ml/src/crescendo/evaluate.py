"""Evaluation (C4, L3 §5, §18).

Operationalizes the single subtlest correctness rule (§4). Two nested guards:
  1. temporal split — no future row ever enters train;
  2. per-fold decile threshold — the breakout label is computed from EACH fold's OWN
     rows, never globally, so no future growth distribution leaks into the label.

Metric helpers are pure and importable so tests can pin the arithmetic and the per-fold
labeling rule without a database.
"""

from __future__ import annotations

import math
from datetime import date, timedelta

import numpy as np

from . import FEATURES
from . import logging as log
from .config import Config
from .db import Db
from .model import _new_estimator
from .types import EvalResult

# ---- pure metric + labeling helpers (unit-tested) -------------------------------------


def fold_labels(fwd_growth: np.ndarray, decile: float) -> np.ndarray:
    """Top-decile breakout labels from THIS array's own distribution (the §4 rule).

    is_breakout = 1 iff fwd_growth >= the (1 - decile) quantile of `fwd_growth`.
    """
    if len(fwd_growth) == 0:
        return np.array([], dtype=int)
    thr = np.quantile(fwd_growth, 1.0 - decile)
    return (fwd_growth >= thr).astype(int)


def resolve_k(k: int | str, y_test: np.ndarray, decile: float) -> int:
    """'auto' -> the fold's positive count (== int(len*decile)); guard k >= 1."""
    if k == "auto":
        return max(1, round(len(y_test) * decile))
    return max(1, int(k))


def precision_at_k(y_true: np.ndarray, scores: np.ndarray, k: int) -> float:
    """Fraction of the top-k scored items that are actual breakouts."""
    if k <= 0 or len(scores) == 0:
        return 0.0
    order = np.argsort(-scores, kind="stable")[:k]
    return float(y_true[order].sum()) / k


def ndcg_at_k(y_true: np.ndarray, scores: np.ndarray, k: int) -> float:
    order = np.argsort(-scores, kind="stable")[:k]
    gains = y_true[order]
    discounts = 1.0 / np.log2(np.arange(2, len(gains) + 2))
    dcg = float((gains * discounts).sum())
    ideal = np.sort(y_true)[::-1][:k]
    idcg = float((ideal * discounts).sum())
    return dcg / idcg if idcg > 0 else 0.0


def _roc_auc(y_true: np.ndarray, scores: np.ndarray) -> float:
    from sklearn.metrics import roc_auc_score

    if len(np.unique(y_true)) < 2:
        return float("nan")  # AUC undefined with a single class
    return float(roc_auc_score(y_true, scores))


# ---- folds ----------------------------------------------------------------------------


def make_folds(df, cutoff: date, cfg: Config, walk_forward: bool):
    """Return list of (train_idx, test_idx). Test window = [c, c+forward_days) so labels
    are fully realized. Expanding-window when walk_forward."""
    fd = timedelta(days=cfg.forward_days)
    cutoffs = (
        [cutoff + j * fd for j in range(cfg.walk_forward_folds)] if walk_forward else [cutoff]
    )
    folds = []
    for c in cutoffs:
        train = df.index[df.as_of_date < c]
        test = df.index[(df.as_of_date >= c) & (df.as_of_date < c + fd)]
        folds.append((c, train, test))
    return folds


# ---- driver ---------------------------------------------------------------------------


def evaluate(
    cfg: Config,
    db: Db,
    cutoff: date,
    k: int | str = "auto",
    walk_forward: bool = False,
    model_kind: str = "lgbm",
) -> list[EvalResult]:
    from .dataset import dataset_version

    df = db.read_dataset(version=dataset_version(cfg))
    if df.empty:
        log.warning("eval.empty", version=dataset_version(cfg))
        return []
    df = df.sort_values("as_of_date").reset_index(drop=True)
    # Ensure as_of_date is comparable to date objects.
    df["as_of_date"] = _to_dates(df["as_of_date"])

    results: list[EvalResult] = []
    for i, (c, train_idx, test_idx) in enumerate(make_folds(df, cutoff, cfg, walk_forward)):
        train, test = df.loc[train_idx], df.loc[test_idx]
        if len(train) == 0 or len(test) == 0:
            log.warning("eval.fold_skip", fold=i, n_train=len(train), n_test=len(test))
            continue

        # --- fold-scoped labels: threshold from THIS fold's rows only (§4) ---
        # We regress on continuous fwd_growth_30d (not the binary label), so only the
        # TEST fold needs binarizing — for precision@k / base_rate. The train label
        # would be dead weight here; keeping the model a ranker on raw growth also avoids
        # importing the (global-vs-fold) decile decision into training.
        y_test = fold_labels(test["fwd_growth_30d"].to_numpy(), cfg.breakout_decile)

        est = _new_estimator(model_kind, cfg)
        est.fit(train.reindex(columns=FEATURES), train["fwd_growth_30d"])
        scores = np.asarray(est.predict(test.reindex(columns=FEATURES)))

        kk = resolve_k(k, y_test, cfg.breakout_decile)
        base_rate = float(y_test.mean())
        p_at_k = precision_at_k(y_test, scores, kk)
        lift = p_at_k / base_rate if base_rate > 0 else float("nan")

        result = EvalResult(
            cutoff=c,
            fold_index=i,
            n_train=len(train),
            n_test=len(test),
            k=kk,
            base_rate=base_rate,
            precision_at_k=p_at_k,
            lift=lift,
            ndcg_at_k=ndcg_at_k(y_test, scores, kk),
            roc_auc=_roc_auc(y_test, scores),
        )
        results.append(result)

        # Baselines on the SAME y_test/kk for an honest bar (§5).
        base_rand = precision_at_k(y_test, _rank_random(len(test)), kk)
        base_mom = precision_at_k(y_test, test["growth_7d"].fillna(-1e9).to_numpy(), kk)
        log.info("eval.fold", fold=i, cutoff=str(c), n_train=len(train), n_test=len(test),
                 k=kk, base_rate=round(base_rate, 4), precision_at_k=round(p_at_k, 4),
                 lift=round(lift, 3) if not math.isnan(lift) else None,
                 baseline_random=round(base_rand, 4), baseline_momentum=round(base_mom, 4),
                 roc_auc=round(result.roc_auc, 4) if not math.isnan(result.roc_auc) else None)
    return results


def _rank_random(n: int) -> np.ndarray:
    # Deterministic pseudo-random baseline (seeded) so eval runs are reproducible.
    rng = np.random.default_rng(42)
    return rng.random(n)


def _to_dates(series):
    import pandas as pd

    return pd.to_datetime(series).dt.date
