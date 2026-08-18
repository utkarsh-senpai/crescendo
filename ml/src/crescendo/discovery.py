"""Discovery (C1, L3 §15).

Seed + budgeted snowball on cheap calls only (never search.list). Deterministic order so
a re-run with the same seeds/quota yields the same tracked set (reproducibility, L2 §6).
"""

from __future__ import annotations

from pathlib import Path

from . import logging as log
from .config import Config
from .db import Db
from .types import ArtistStats, DiscoverReport, TrackedArtist
from .youtube import QuotaExceeded, YouTubeClient


def _parse_seeds(seed_path: Path) -> tuple[list[str], list[str]]:
    """Return (playlist_ids, channel_ids) from the seed file. Ignores comments/blanks."""
    playlists: list[str] = []
    channels: list[str] = []
    if not seed_path.exists():
        return playlists, channels
    for raw in seed_path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        kind, _, ident = line.partition(":")
        # Strip any trailing inline comment (e.g. "channel:UC...  # Artist (subs)").
        ident = ident.split("#", 1)[0]
        kind, ident = kind.strip().lower(), ident.strip()
        if not ident:
            continue
        if kind == "playlist":
            playlists.append(ident)
        elif kind == "channel":
            channels.append(ident)
    return playlists, channels


def discover(
    cfg: Config, db: Db, yt: YouTubeClient, max_artists: int, snowball: bool
) -> DiscoverReport:
    seen: set[str] = set(db.existing_channel_ids())  # idempotent re-runs
    accepted: list[TrackedArtist] = []
    rejected_band = 0
    rejected_inactive = 0
    n_seed = 0
    n_snowball = 0

    playlists, seed_channels = _parse_seeds(cfg.seed_path)

    def consider(cid: str, source: str) -> ArtistStats | None:
        nonlocal rejected_band, rejected_inactive
        if cid in seen or len(accepted) >= max_artists:
            return None
        seen.add(cid)
        stats = yt.channel_stats(cid)  # batched at the call sites below in practice
        return _accept(stats, source)

    def _accept(stats: ArtistStats, source: str) -> ArtistStats | None:
        nonlocal rejected_band, rejected_inactive, n_seed, n_snowball
        if not (cfg.subs_min <= stats.subscribers <= cfg.subs_max):
            rejected_band += 1
            log.debug("discover.reject", channel_id=stats.channel_id, reason="band",
                      subs=stats.subscribers)
            return None
        if stats.video_count == 0:
            rejected_inactive += 1
            log.debug("discover.reject", channel_id=stats.channel_id, reason="inactive")
            return None
        accepted.append(
            TrackedArtist(
                artist_id=0,  # assigned by DB
                channel_id=stats.channel_id,
                title=stats.title,
                genre=cfg.genre_name,
                subs_at_entry=stats.subscribers,
                source=source,
                discovered_at=stats.fetched_at,
                is_active=True,
            )
        )
        if source == "seed":
            n_seed += 1
        else:
            n_snowball += 1
        log.debug("discover.consider", channel_id=stats.channel_id, source=source,
                  subs=stats.subscribers)
        return stats

    try:
        # ---- Pass 1: seeds ----
        seed_cids: list[str] = list(seed_channels)
        for pid in playlists:
            for cid in yt.playlist_channel_ids(pid):
                if cid not in seed_cids:
                    seed_cids.append(cid)

        seed_roots = _batch_consider(yt, seed_cids, seen, accepted, max_artists, _accept, "seed")

        # ---- Pass 2: bounded snowball (BFS, depth-limited) ----
        if snowball and cfg.snowball_max_depth > 0:
            frontier = [(cid, 0) for cid in seed_roots]
            while frontier and len(accepted) < max_artists:
                cid, depth = frontier.pop(0)
                if depth >= cfg.snowball_max_depth:
                    continue
                neighbors = [n for n in yt.related_channels(cid) if n not in seen]
                new_stats = _batch_consider(
                    yt, neighbors, seen, accepted, max_artists, _accept, "snowball"
                )
                frontier.extend((s.channel_id, depth + 1) for s in new_stats)
    except QuotaExceeded as exc:
        log.warning("discover.quota_stop", op=exc.op, remaining=exc.remaining_units,
                    accepted=len(accepted))

    written = db.upsert_artists(accepted)
    log.info("discover.done", n_seed=n_seed, n_snowball=n_snowball, written=written,
             rejected_band=rejected_band, rejected_inactive=rejected_inactive,
             units_spent=yt._acct.spent)
    return DiscoverReport(
        n_seed=n_seed,
        n_snowball=n_snowball,
        n_rejected_band=rejected_band,
        n_rejected_inactive=rejected_inactive,
        units_spent=yt._acct.spent,
    )


def _batch_consider(yt, cids, seen, accepted, max_artists, accept_fn, source):
    """Resolve candidate channel IDs in batches of 50 (1 unit/50) and accept in order."""
    to_fetch = [c for c in cids if c not in seen]
    accepted_stats = []
    for i in range(0, len(to_fetch), 50):
        if len(accepted) >= max_artists:
            break
        chunk = to_fetch[i : i + 50]
        for c in chunk:
            seen.add(c)
        stats_list = yt.channel_stats_batch(chunk)
        for stats in stats_list:
            if len(accepted) >= max_artists:
                break
            s = accept_fn(stats, source)
            if s is not None:
                accepted_stats.append(s)
    return accepted_stats
