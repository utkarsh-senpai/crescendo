"""Unit tests for reason phrasing (pure, no model)."""

from __future__ import annotations

from crescendo_serving.reasons import build_reasons

# Importances that make growth_7d the dominant driver, then accel, then consistency.
IMPORTANCES = {
    "growth_7d": 0.5,
    "accel": 0.25,
    "consistency": 0.15,
    "inorganic_score": 0.1,
}


def test_reasons_ordered_by_importance():
    feats = {"growth_7d": 0.08, "accel": 0.03, "consistency": 0.9}
    reasons = build_reasons(feats, IMPORTANCES, inorganic_threshold=0.8)
    # Most-important present feature comes first.
    assert reasons[0] == "strong 7d subscriber growth"
    assert "accelerating momentum" in reasons


def test_high_vs_low_phrasing():
    high = build_reasons({"growth_7d": 0.10}, IMPORTANCES, 0.8)
    low = build_reasons({"growth_7d": -0.05}, IMPORTANCES, 0.8)
    assert high[0] == "strong 7d subscriber growth"
    assert low[0] == "soft 7d subscriber growth"


def test_max_reasons_capped():
    feats = {
        "growth_7d": 0.08,
        "accel": 0.03,
        "consistency": 0.9,
        "growth_30d": 0.2,
        "views_growth_7d": 0.05,
        "inorganic_score": 0.1,  # low + present -> organic vouch, appended past the cap
    }
    reasons = build_reasons(feats, IMPORTANCES, 0.8, max_reasons=2)
    # 2 momentum reasons (capped) + the appended organic vouch (not counted against the cap).
    assert len(reasons) == 3
    assert reasons[-1] == "growth looks organic"


def test_inorganic_flag_always_surfaced_even_when_low_importance():
    feats = {"growth_7d": 0.08, "inorganic_score": 0.95}
    reasons = build_reasons(feats, IMPORTANCES, inorganic_threshold=0.8)
    assert "discounted: growth looks inorganic" in reasons


def test_organic_vouch_only_with_a_positive_pick():
    # inorganic_score present + low, but no phraseable momentum feature -> no vouch, no crash.
    reasons = build_reasons({"inorganic_score": 0.1}, IMPORTANCES, 0.8)
    assert reasons == ["insufficient signal"]


def test_none_and_nan_features_ignored():
    feats = {"growth_7d": None, "accel": float("nan"), "consistency": 0.9}
    reasons = build_reasons(feats, IMPORTANCES, 0.8)
    assert reasons == ["steady, consistent growth"]


def test_empty_features_insufficient_signal():
    assert build_reasons({}, IMPORTANCES, 0.8) == ["insufficient signal"]
