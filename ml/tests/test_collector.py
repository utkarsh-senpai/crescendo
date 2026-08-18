"""Collector hardening tests (v0.2): run-status classification + audit recording.

No real DB or API — a fake Db captures writes and a fake YouTube client returns canned
stats — so the status/exit-code discipline is pinned without infrastructure.
"""

from __future__ import annotations

from datetime import date

from crescendo.collector import collect_once
from crescendo.types import ArtistStats, TrackedArtist
from crescendo.youtube import QuotaAccountant, QuotaExceeded


class FakeDb:
    def __init__(self, artists):
        self._artists = artists
        self.snapshots_written = 0
        self.deactivated = []
        self.runs = []

    def active_artists(self):
        return list(self._artists)

    def insert_snapshots(self, rows):
        self.snapshots_written += len(rows)
        return len(rows)

    def deactivate_artist(self, artist_id):
        self.deactivated.append(artist_id)

    def record_run(self, **kw):
        self.runs.append(kw)


class FakeYouTube:
    """Returns stats for a whitelist of channel ids; can simulate a mid-batch quota block."""

    def __init__(self, present_ids, spent=1, raise_after=None):
        self._present = set(present_ids)
        self._spent = spent
        self._raise_after = raise_after
        self._batches = 0

    @property
    def units_spent(self):
        return self._spent

    def channel_stats_batch(self, channel_ids):
        self._batches += 1
        if self._raise_after is not None and self._batches > self._raise_after:
            raise QuotaExceeded("channels.list", 1, 0)
        return [
            ArtistStats(
                channel_id=cid, title=f"c{cid}", subscribers=5000,
                total_views=100000, video_count=10,
                fetched_at=None,  # collector doesn't read this field
            )
            for cid in channel_ids
            if cid in self._present
        ]


def _artist(i):
    return TrackedArtist(
        artist_id=i, channel_id=f"UC{i}", title=f"a{i}", genre="electronic",
        subs_at_entry=5000, source="seed", discovered_at=None, is_active=True,
    )


def _cfg_stub():
    return object()  # collect_once never touches cfg in the happy path


DAY = date(2026, 8, 18)


def test_status_ok_and_records_run():
    artists = [_artist(1), _artist(2)]
    db = FakeDb(artists)
    yt = FakeYouTube(present_ids=["UC1", "UC2"])
    report = collect_once(_cfg_stub(), db, yt, captured_on=DAY)
    assert report.status == "ok"
    assert report.n_snapshotted == 2
    assert report.n_active == 2
    assert db.snapshots_written == 2
    assert len(db.runs) == 1
    assert db.runs[0]["status"] == "ok"
    assert db.runs[0]["captured_on"] == DAY


def test_status_empty_when_active_but_none_collected():
    # Active artists exist but the API returns nothing for them -> silent-failure alarm.
    artists = [_artist(1), _artist(2)]
    db = FakeDb(artists)
    yt = FakeYouTube(present_ids=[])  # all channels omitted -> gone -> deactivated
    report = collect_once(_cfg_stub(), db, yt, captured_on=DAY)
    assert report.status == "empty"
    assert report.n_snapshotted == 0
    assert report.n_deactivated == 2
    assert db.runs[0]["status"] == "empty"


def test_status_quota_partial_on_midrun_block():
    # 120 artists = 3 batches of 50; block after the first batch -> partial.
    artists = [_artist(i) for i in range(120)]
    db = FakeDb(artists)
    yt = FakeYouTube(present_ids=[a.channel_id for a in artists], raise_after=1)
    report = collect_once(_cfg_stub(), db, yt, captured_on=DAY)
    assert report.status == "quota_partial"
    assert report.n_snapshotted == 50  # only the first batch landed
    assert db.runs[0]["status"] == "quota_partial"


def test_dry_run_records_nothing():
    artists = [_artist(1)]
    db = FakeDb(artists)
    yt = FakeYouTube(present_ids=["UC1"])
    report = collect_once(_cfg_stub(), db, yt, captured_on=DAY, dry_run=True)
    assert report.status == "ok"
    assert db.snapshots_written == 0
    assert db.runs == []  # dry-run must not pollute the audit trail


def test_empty_cohort_is_ok_not_empty():
    # No active artists at all -> nothing to do, but not an alarm.
    db = FakeDb([])
    yt = FakeYouTube(present_ids=[])
    report = collect_once(_cfg_stub(), db, yt, captured_on=DAY)
    assert report.status == "ok"
    assert report.n_active == 0


def test_quota_accountant_unused_here():
    # Guard: the public units_spent accessor exists and reads the accountant.
    from crescendo.youtube import YouTubeClient

    acct = QuotaAccountant(daily_ceiling=100, today=DAY)
    acct.charge(3, "channels.list")
    yt = YouTubeClient("key", acct)
    assert yt.units_spent == 3
