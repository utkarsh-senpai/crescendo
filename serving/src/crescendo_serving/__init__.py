"""Crescendo prediction service (v0.3) — the L2 predict() seam exposed over HTTP (L3 §11).

Wraps `crescendo.model.CrescendoModel.predict` behind `POST /predict`. Both the future game
backend and the transparent AI opponent call this SAME endpoint, so "the AI plays by the rules
it shows you" is true by construction: `breakout_score` is the model's ranking score and
`reasons` are derived from `model.feature_importances()` applied to each row's feature values.
"""

__version__ = "0.3.0"
