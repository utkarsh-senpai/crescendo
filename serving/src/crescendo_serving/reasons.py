"""Turn a scored feature row into human-readable `reasons` (L3 §11, the transparent-AI UX).

PURE functions, no I/O — so the phrasing is unit-testable and reproducible. Reasons are the
project's headline novelty: the AI opponent explains each pick using the SAME model the score
comes from. We rank the row's features by the model's learned `feature_importances()` and emit
a short phrase for each important feature that is actually present, so the explanation reflects
what the model keys on — not a hand-picked narrative.

`inorganic_score` is special: it drives an authenticity reason ("growth looks inorganic" /
"growth looks organic") so the AI can literally say why it trusts (or discounts) a pick.
"""

from __future__ import annotations

import math

# Phrasing per feature: (high_value_phrase, low/negative_value_phrase). The midpoint below
# which we consider a value "low/negative" is feature-specific (see _describe). Ordering of
# emitted reasons is by model importance, computed at call time — not by this dict.
_PHRASES: dict[str, tuple[str, str]] = {
    "growth_7d": ("strong 7d subscriber growth", "soft 7d subscriber growth"),
    "growth_30d": ("strong 30d growth", "soft 30d growth"),
    "accel": ("accelerating momentum", "decelerating momentum"),
    "consistency": ("steady, consistent growth", "erratic growth"),
    "views_growth_7d": ("views climbing with subs", "views lagging subs"),
    "upload_rate_30d": ("active upload cadence", "quiet upload cadence"),
    "subs": ("sizeable existing audience", "small existing audience"),
}

# The threshold at/above which a feature value reads as "high". growth-style features are
# relative (a few percent is already notable); consistency is a 0..1 score; accel centers on 0.
_HIGH_AT: dict[str, float] = {
    "growth_7d": 0.02,
    "growth_30d": 0.05,
    "accel": 0.0,
    "consistency": 0.5,
    "views_growth_7d": 0.02,
    "upload_rate_30d": 1.0,
    "subs": 50_000.0,
}


def _describe(feature: str, value: float) -> str | None:
    """Phrase for a single feature given its value; None if we have no phrasing for it."""
    phrases = _PHRASES.get(feature)
    if phrases is None:
        return None
    high, low = phrases
    return high if value >= _HIGH_AT.get(feature, 0.0) else low


def build_reasons(
    features: dict[str, float | None],
    importances: dict[str, float],
    inorganic_threshold: float,
    max_reasons: int = 3,
) -> list[str]:
    """Top feature contributions as short phrases, most-important first.

    - Features are ranked by the model's `importances` (learned weights). Only features that
      are present (non-None, finite) and have phrasing contribute a reason.
    - The authenticity reason from `inorganic_score` is ALWAYS surfaced when the row is
      flagged (>= threshold), regardless of its rank — the AI must show when it discounts a
      pick — and appended (not counted against max_reasons) otherwise it stays implicit.
    """
    present = {
        name: val
        for name, val in features.items()
        if val is not None and math.isfinite(val)
    }

    ranked_feats = sorted(
        (f for f in present if f in _PHRASES),
        key=lambda f: importances.get(f, 0.0),
        reverse=True,
    )

    reasons: list[str] = []
    for feat in ranked_feats:
        if len(reasons) >= max_reasons:
            break
        phrase = _describe(feat, present[feat])
        if phrase:
            reasons.append(phrase)

    # Authenticity flag: always visible, appended so it never crowds out momentum reasons.
    score = present.get("inorganic_score")
    if score is not None:
        if score >= inorganic_threshold:
            reasons.append("discounted: growth looks inorganic")
        elif reasons:  # only vouch for organic-ness once we have a positive pick to vouch for
            reasons.append("growth looks organic")

    if not reasons:
        reasons.append("insufficient signal")
    return reasons
