"""Config loading + validation (L3 §7).

Reads config/crescendo.toml + .env, validates ranges, and fails fast (ConfigError)
before any API/DB work. The returned Config is a frozen carrier consumed everywhere.
"""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass
from datetime import date
from pathlib import Path


class ConfigError(Exception):
    """Raised on any invalid/missing configuration. Fatal at startup."""


@dataclass(frozen=True)
class Config:
    # [genre]
    genre_name: str
    seed_file: str
    snowball_max_depth: int
    # [cohort]
    subs_min: int
    subs_max: int
    min_history_days: int
    snapshot_gap_tolerance_days: int
    subs_max_soft_multiplier: float
    # [features]
    short_window: int
    long_window: int
    consistency_window: int
    # [label]
    forward_days: int
    breakout_decile: float
    # [eval]
    cutoff: date
    k: int | str
    walk_forward_folds: int
    # [quota]
    daily_unit_ceiling: int
    search_list_call_ceiling: int
    # [dataquality]
    dq_mode: str
    inorganic_threshold: float
    w_subs_jump: float
    w_subs_views_divergence: float
    w_step_discontinuity: float
    # secrets (from .env / environment)
    youtube_api_key: str
    database_url: str
    # resolved paths
    config_dir: Path

    @property
    def seed_path(self) -> Path:
        """seed_file resolved relative to the config dir (or absolute if given so)."""
        p = Path(self.seed_file)
        return p if p.is_absolute() else self.config_dir / p


def _load_dotenv(path: Path) -> None:
    """Minimal .env loader (no dependency). Does not overwrite already-set env vars."""
    if not path.exists():
        return
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        key, val = key.strip(), val.strip().strip('"').strip("'")
        os.environ.setdefault(key, val)


def load_config(path: str = "config/crescendo.toml") -> Config:
    cfg_path = Path(path)
    if not cfg_path.exists():
        raise ConfigError(f"config file not found: {cfg_path}")

    # Load .env from the current working dir (project root) if present.
    _load_dotenv(Path(".env"))

    with cfg_path.open("rb") as fh:
        raw = tomllib.load(fh)

    def section(name: str) -> dict:
        if name not in raw:
            raise ConfigError(f"missing [{name}] section in {cfg_path}")
        return raw[name]

    genre = section("genre")
    cohort = section("cohort")
    feats = section("features")
    label = section("label")
    ev = section("eval")
    quota = section("quota")
    dq = section("dataquality")

    api_key = os.environ.get("YOUTUBE_API_KEY", "")
    db_url = os.environ.get("DATABASE_URL", "")

    cfg = Config(
        genre_name=genre["name"],
        seed_file=genre["seed_file"],
        snowball_max_depth=int(genre.get("snowball_max_depth", 1)),
        subs_min=int(cohort["subs_min"]),
        subs_max=int(cohort["subs_max"]),
        min_history_days=int(cohort["min_history_days"]),
        snapshot_gap_tolerance_days=int(cohort["snapshot_gap_tolerance_days"]),
        subs_max_soft_multiplier=float(cohort.get("subs_max_soft_multiplier", 1.5)),
        short_window=int(feats["short_window"]),
        long_window=int(feats["long_window"]),
        consistency_window=int(feats["consistency_window"]),
        forward_days=int(label["forward_days"]),
        breakout_decile=float(label["breakout_decile"]),
        cutoff=_as_date(ev["cutoff"], "eval.cutoff"),
        k=ev.get("k", "auto"),
        walk_forward_folds=int(ev.get("walk_forward_folds", 1)),
        daily_unit_ceiling=int(quota["daily_unit_ceiling"]),
        search_list_call_ceiling=int(quota.get("search_list_call_ceiling", 90)),
        dq_mode=str(dq.get("mode", "feature")),
        inorganic_threshold=float(dq.get("inorganic_threshold", 0.8)),
        w_subs_jump=float(dq.get("w_subs_jump", 0.5)),
        w_subs_views_divergence=float(dq.get("w_subs_views_divergence", 0.3)),
        w_step_discontinuity=float(dq.get("w_step_discontinuity", 0.2)),
        youtube_api_key=api_key,
        database_url=db_url,
        config_dir=cfg_path.resolve().parent,
    )
    _validate(cfg)
    return cfg


def _as_date(value: object, field: str) -> date:
    if isinstance(value, date):
        return value
    if isinstance(value, str):
        try:
            return date.fromisoformat(value)
        except ValueError as exc:
            raise ConfigError(f"{field}: invalid date {value!r}") from exc
    raise ConfigError(f"{field}: expected a date, got {type(value).__name__}")


def _validate(c: Config) -> None:
    errs: list[str] = []
    if not (0 < c.subs_min < c.subs_max):
        errs.append(f"cohort: require 0 < subs_min ({c.subs_min}) < subs_max ({c.subs_max})")
    for name, w in (
        ("short_window", c.short_window),
        ("long_window", c.long_window),
        ("consistency_window", c.consistency_window),
        ("forward_days", c.forward_days),
        ("min_history_days", c.min_history_days),
    ):
        if w <= 0:
            errs.append(f"{name} must be > 0 (got {w})")
    if not (0.0 < c.breakout_decile < 1.0):
        errs.append(f"label.breakout_decile must be in (0,1) (got {c.breakout_decile})")
    if not (0 < c.daily_unit_ceiling <= 10000):
        errs.append(f"quota.daily_unit_ceiling must be in (0,10000] (got {c.daily_unit_ceiling})")
    if c.snowball_max_depth < 0:
        errs.append(f"genre.snowball_max_depth must be >= 0 (got {c.snowball_max_depth})")
    if c.dq_mode not in ("feature", "exclude", "both"):
        errs.append(f"dataquality.mode must be feature|exclude|both (got {c.dq_mode!r})")
    if not (0.0 <= c.inorganic_threshold <= 1.0):
        errs.append(f"dataquality.inorganic_threshold must be in [0,1] (got {c.inorganic_threshold})")
    if isinstance(c.k, str) and c.k != "auto":
        errs.append(f"eval.k must be an int or 'auto' (got {c.k!r})")
    if isinstance(c.k, int) and c.k < 1:
        errs.append(f"eval.k must be >= 1 (got {c.k})")
    if errs:
        raise ConfigError("invalid configuration:\n  - " + "\n  - ".join(errs))
