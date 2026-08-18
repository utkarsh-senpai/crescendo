"""Crescendo CLI (L3 §6).

Single Typer entrypoint. Every command loads config + .env, allocates a run_id, and logs
structured JSON lines. Non-zero exit on failure; QuotaExceeded partials exit 0 (§21).
"""

from __future__ import annotations

from datetime import UTC, date, datetime
from pathlib import Path

import typer

from . import __version__
from . import logging as log
from .config import ConfigError, load_config

app = typer.Typer(add_completion=False, help="Crescendo — breakout prediction modeling spike.")

_CONFIG_OPT = typer.Option("config/crescendo.toml", "--config", help="Path to crescendo.toml")


def _boot(config_path: str):
    """Common startup: configure logging, allocate run_id, load+validate config."""
    log.configure()
    log.new_run_id()
    try:
        return load_config(config_path)
    except ConfigError as exc:
        log.error("config.error", detail=str(exc))
        raise typer.Exit(code=2) from exc


def _today_utc() -> date:
    return datetime.now(UTC).date()


@app.command()
def status(config: str = _CONFIG_OPT):
    """Artists tracked, snapshots collected, history span (bootstraps the DB if needed)."""
    cfg = _boot(config)
    from .db import Db

    db = Db(cfg.database_url)
    db.bootstrap()
    s = db.stats()
    log.info("status", **s, quota_ceiling=cfg.daily_unit_ceiling, version=__version__)
    typer.echo(
        f"crescendo v{__version__}\n"
        f"  artists: {s['artists_active']} active / {s['artists_total']} total\n"
        f"  snapshots: {s['snapshots_total']} total, {s['snapshots_today']} today\n"
        f"  history: {s['history_from']} → {s['history_to']}\n"
        f"  quota ceiling: {cfg.daily_unit_ceiling} units/day"
    )


@app.command()
def discover(
    config: str = _CONFIG_OPT,
    seeds: str = typer.Option(None, "--seeds", help="Override seed file path"),
    max_artists: int = typer.Option(300, "--max-artists"),
    snowball: bool = typer.Option(True, "--snowball/--no-snowball"),
):
    """C1: seed + snowball discovery → populate tracked_artist."""
    cfg = _boot(config)
    if seeds:
        object.__setattr__(cfg, "seed_file", seeds)
    from .db import Db
    from .discovery import discover as run_discover
    from .youtube import QuotaAccountant, YouTubeClient

    db = Db(cfg.database_url)
    db.bootstrap()
    acct = QuotaAccountant(cfg.daily_unit_ceiling, _today_utc())
    yt = YouTubeClient(cfg.youtube_api_key, acct)
    report = run_discover(cfg, db, yt, max_artists=max_artists, snowball=snowball)
    typer.echo(
        f"discovered: {report.n_seed} seed + {report.n_snowball} snowball; "
        f"rejected {report.n_rejected_band} band / {report.n_rejected_inactive} inactive; "
        f"{report.units_spent} units spent"
    )


@app.command()
def collect(
    config: str = _CONFIG_OPT,
    dry_run: bool = typer.Option(False, "--dry-run"),
    limit: int = typer.Option(None, "--limit"),
):
    """C2: one daily snapshot pass over active artists (quota-aware, idempotent)."""
    cfg = _boot(config)
    from .collector import collect_once
    from .db import Db
    from .youtube import QuotaAccountant, YouTubeClient

    db = Db(cfg.database_url)
    db.bootstrap()
    acct = QuotaAccountant(cfg.daily_unit_ceiling, _today_utc())
    yt = YouTubeClient(cfg.youtube_api_key, acct)
    report = collect_once(cfg, db, yt, captured_on=_today_utc(), limit=limit, dry_run=dry_run)
    typer.echo(
        f"collected {report.n_snapshotted} snapshots "
        f"({report.n_deactivated} deactivated); {report.units_spent} units spent"
        + (" [dry-run]" if dry_run else "")
    )


@app.command("build-dataset")
def build_dataset_cmd(
    config: str = _CONFIG_OPT,
    as_of_start: str = typer.Option(..., "--as-of-start", help="YYYY-MM-DD"),
    as_of_end: str = typer.Option(..., "--as-of-end", help="YYYY-MM-DD"),
):
    """C3: assemble features + forward labels → dataset table."""
    cfg = _boot(config)
    from .dataset import build_dataset
    from .db import Db

    db = Db(cfg.database_url)
    db.bootstrap()
    report = build_dataset(cfg, db, date.fromisoformat(as_of_start), date.fromisoformat(as_of_end))
    typer.echo(
        f"dataset v{report.version}: {report.n_rows} rows from {report.n_artists} artists "
        f"(skipped {report.n_skipped_coldstart} coldstart / {report.n_skipped_nolabel} nolabel)"
    )


@app.command()
def train(
    config: str = _CONFIG_OPT,
    model: str = typer.Option("lgbm", "--model", help="lgbm|xgb"),
    cutoff: str = typer.Option(None, "--cutoff", help="Train on as_of_date < cutoff"),
    out: str = typer.Option(None, "--out", help="Artifact path (default models/<ver>_<kind>.joblib)"),
):
    """C4: fit the model on the train fold and persist the artifact."""
    cfg = _boot(config)
    from .dataset import dataset_version
    from .db import Db
    from .model import train as run_train

    db = Db(cfg.database_url)
    df = db.read_dataset(version=dataset_version(cfg))
    if df.empty:
        log.error("train.no_data", version=dataset_version(cfg))
        raise typer.Exit(code=1)
    if cutoff:
        import pandas as pd

        cut = date.fromisoformat(cutoff)
        df = df[pd.to_datetime(df["as_of_date"]).dt.date < cut]
    m = run_train(df, cfg, model)
    out_path = out or f"models/{m.dataset_version}_{model}.joblib"
    Path(out_path).parent.mkdir(parents=True, exist_ok=True)
    m.save(out_path)
    log.info("train.done", model=model, rows=len(df), artifact=out_path,
             trained_at=datetime.now(UTC).isoformat())
    typer.echo(f"trained {model} on {len(df)} rows → {out_path}")


@app.command()
def evaluate(
    config: str = _CONFIG_OPT,
    cutoff: str = typer.Option(None, "--cutoff", help="Temporal split date YYYY-MM-DD"),
    k: str = typer.Option("auto", "--k"),
    walk_forward: bool = typer.Option(False, "--walk-forward"),
    model: str = typer.Option("lgbm", "--model"),
):
    """C4: temporal split, per-fold decile labeling, precision@k vs baselines."""
    cfg = _boot(config)
    from .db import Db
    from .evaluate import evaluate as run_eval

    db = Db(cfg.database_url)
    cut = date.fromisoformat(cutoff) if cutoff else cfg.cutoff
    k_val: int | str = "auto" if k == "auto" else int(k)
    results = run_eval(cfg, db, cutoff=cut, k=k_val, walk_forward=walk_forward, model_kind=model)
    if not results:
        typer.echo("no eval results (empty dataset or empty folds)")
        raise typer.Exit(code=1)
    for r in results:
        typer.echo(
            f"fold {r.fold_index} @ {r.cutoff}: "
            f"P@{r.k}={r.precision_at_k:.3f} base={r.base_rate:.3f} "
            f"lift={r.lift:.2f} auc={r.roc_auc:.3f} (n_test={r.n_test})\n"
            f"    organic: P@{r.k}={r.organic_precision_at_k:.3f} "
            f"base={r.organic_base_rate:.3f} lift={r.organic_lift:.2f} "
            f"| inorganic@{r.k}={r.inorganic_rate_at_k:.3f}"
        )


def main() -> None:
    app()


if __name__ == "__main__":
    main()
