"""Crescendo — leakage-safe emerging-artist ORGANIC-breakout prediction (modeling spike)."""

__version__ = "0.2.0"

# Schema version constants. Bump when the row / artifact shape changes so that
# dataset_version() (see dataset.py) forces a rebuild instead of mixing incompatible rows.
DATASET_SCHEMA_VERSION = 1
MODEL_ARTIFACT_VERSION = 1

# Canonical, fixed-order feature list. model.predict() reindexes to this; the dataset
# table and evaluate.py both consume it, so it lives in one place.
FEATURES = [
    "subs",
    "growth_7d",
    "growth_30d",
    "accel",
    "consistency",
    "views_growth_7d",
    "upload_rate_30d",
    "inorganic_score",
]
