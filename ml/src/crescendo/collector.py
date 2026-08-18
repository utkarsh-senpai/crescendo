"""Collector (C2, L3 §21).

One daily snapshot pass over active artists, with per-artist failure isolation: one bad
channel never fails the batch. Writes are idempotent (ON CONFLICT DO NOTHING) so partial
re-runs on the same day are safe.
"""

from __future__ import annotations

from datetime import date

from . import logging as log
from .config import Config
from .db import Db
from .types import ArtistStats, CollectReport, Snapshot
from .youtube import QuotaExceeded, YouTubeClient

_BATCH = 50


def collect_once(
    cfg: Config,
    db: Db,
    yt: YouTubeClient,
    captured_on: date,
    limit: int | None = None,
    dry_run: bool = False,
) -> CollectReport:
    artists = db.active_artists()
    if limit is not None:
        artists = artists[:limit]

    by_channel = {a.channel_id: a for a in artists}
    channel_ids = list(by_channel.keys())

    n_active = len(channel_ids)
    snapshots: list[Snapshot] = []
    n_failed = 0
    n_deactivated = 0
    quota_partial = False

    try:
        for i in range(0, len(channel_ids), _BATCH):
            chunk = channel_ids[i : i + _BATCH]
            stats_list = yt.channel_stats_batch(chunk)  # charges 1 unit/batch, retries inside
            returned = {s.channel_id: s for s in stats_list}

            # Channels the API omitted from the batch response are gone (404-equiv) -> deactivate.
            for cid in chunk:
                artist = by_channel[cid]
                stats = returned.get(cid)
                if stats is None:
                    n_deactivated += 1
                    if not dry_run:
                        db.deactivate_artist(artist.artist_id)
                    log.info("collect.artist", artist_id=artist.artist_id, ok=False,
                             reason="gone")
                    continue
                snapshots.append(_to_snapshot(artist.artist_id, captured_on, stats))
    except QuotaExceeded as exc:
        quota_partial = True
        log.warning("quota.block", op=exc.op, remaining=exc.remaining_units,
                    collected=len(snapshots))

    written = 0 if dry_run else db.insert_snapshots(snapshots)

    # Status classifies the pass so the CLI can exit non-zero and the audit row is queryable.
    # `empty` = there were active artists but we snapshotted none (a silent-failure alarm).
    if quota_partial:
        status = "quota_partial"
    elif n_active > 0 and len(snapshots) == 0:
        status = "empty"
    else:
        status = "ok"

    report = CollectReport(
        captured_on=captured_on,
        n_snapshotted=len(snapshots),
        n_failed=n_failed,
        n_deactivated=n_deactivated,
        units_spent=yt.units_spent,
        status=status,
        n_active=n_active,
    )

    log.info("collect.done", captured_on=str(captured_on), snapshotted=len(snapshots),
             written=written, failed=n_failed, deactivated=n_deactivated,
             active=n_active, status=status, units_spent=yt.units_spent, dry_run=dry_run)

    if not dry_run:
        run_id = log.current_run_id() or captured_on.isoformat()
        db.record_run(
            run_id=run_id,
            captured_on=captured_on,
            n_snapshotted=len(snapshots),
            n_deactivated=n_deactivated,
            units_spent=yt.units_spent,
            status=status,
            detail=f"active={n_active} written={written}",
        )
    return report


def _to_snapshot(artist_id: int, captured_on: date, stats: ArtistStats) -> Snapshot:
    return Snapshot(
        artist_id=artist_id,
        captured_on=captured_on,
        subscribers=stats.subscribers,
        total_views=stats.total_views,
        video_count=stats.video_count,
    )
