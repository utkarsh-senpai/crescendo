"""Database layer (L3 §2, §14).

Connection + idempotent schema bootstrap + thin repositories. `history_for(..., until)`
is the structural leakage guard: feature code physically cannot receive a snapshot after
`as_of`, because the repository never returns one.
"""

from __future__ import annotations

from datetime import date
from typing import TYPE_CHECKING, Any

import psycopg
from psycopg.rows import dict_row

from .types import Snapshot, TrackedArtist

if TYPE_CHECKING:  # pandas is heavy; only imported for the dataset read path
    import pandas as pd


DDL = """
CREATE TABLE IF NOT EXISTS tracked_artist (
    artist_id       BIGSERIAL PRIMARY KEY,
    channel_id      TEXT NOT NULL UNIQUE,
    title           TEXT NOT NULL,
    genre           TEXT NOT NULL DEFAULT 'electronic',
    discovered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    subs_at_entry   BIGINT NOT NULL,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE,
    source          TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_artist_active ON tracked_artist (is_active);

CREATE TABLE IF NOT EXISTS raw_snapshot (
    artist_id       BIGINT NOT NULL REFERENCES tracked_artist(artist_id),
    captured_on     DATE NOT NULL,
    subscribers     BIGINT NOT NULL,
    total_views     BIGINT NOT NULL,
    video_count     INTEGER NOT NULL,
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (artist_id, captured_on)
);
CREATE INDEX IF NOT EXISTS idx_snapshot_day ON raw_snapshot (captured_on);

CREATE TABLE IF NOT EXISTS dataset (
    artist_id       BIGINT NOT NULL REFERENCES tracked_artist(artist_id),
    as_of_date      DATE NOT NULL,
    subs            BIGINT NOT NULL,
    growth_7d       DOUBLE PRECISION,
    growth_30d      DOUBLE PRECISION,
    accel           DOUBLE PRECISION,
    consistency     DOUBLE PRECISION,
    views_growth_7d DOUBLE PRECISION,
    upload_rate_30d DOUBLE PRECISION,
    inorganic_score DOUBLE PRECISION,
    suspected_inorganic BOOLEAN NOT NULL DEFAULT FALSE,
    fwd_growth_30d  DOUBLE PRECISION NOT NULL,
    is_breakout     BOOLEAN,
    dataset_version TEXT NOT NULL,
    PRIMARY KEY (artist_id, as_of_date, dataset_version)
);
CREATE INDEX IF NOT EXISTS idx_dataset_asof ON dataset (as_of_date);

-- v0.2: audit trail for the unattended daily collector. GitHub Actions logs are
-- ephemeral, so each collect pass persists its own health here — this is how a cold
-- return after weeks of autonomous collection can tell the pipeline stayed healthy.
CREATE TABLE IF NOT EXISTS collect_run (
    run_id          TEXT PRIMARY KEY,
    ran_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    captured_on     DATE NOT NULL,
    n_snapshotted   INTEGER NOT NULL,
    n_deactivated   INTEGER NOT NULL,
    units_spent     INTEGER NOT NULL,
    status          TEXT NOT NULL,   -- ok | empty | quota_partial
    detail          TEXT
);
CREATE INDEX IF NOT EXISTS idx_collect_run_day ON collect_run (captured_on);
"""


class Db:
    def __init__(self, database_url: str):
        if not database_url:
            raise ValueError("DATABASE_URL is empty; set it in .env")
        self._url = database_url

    def _connect(self) -> psycopg.Connection:
        return psycopg.connect(self._url, row_factory=dict_row)

    def bootstrap(self) -> None:
        """Run the §2 DDL idempotently (IF NOT EXISTS). Safe on fresh or existing DBs."""
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(DDL)
            conn.commit()

    def upsert_artists(self, rows: list[TrackedArtist]) -> int:
        """Insert new artists; dedupe on channel_id (UNIQUE). Returns rows written."""
        if not rows:
            return 0
        sql = """
            INSERT INTO tracked_artist
                (channel_id, title, genre, subs_at_entry, source, is_active)
            VALUES (%(channel_id)s, %(title)s, %(genre)s, %(subs_at_entry)s,
                    %(source)s, %(is_active)s)
            ON CONFLICT (channel_id) DO NOTHING
        """
        params = [
            {
                "channel_id": a.channel_id,
                "title": a.title,
                "genre": a.genre,
                "subs_at_entry": a.subs_at_entry,
                "source": a.source,
                "is_active": a.is_active,
            }
            for a in rows
        ]
        with self._connect() as conn, conn.cursor() as cur:
            cur.executemany(sql, params)
            conn.commit()
            return cur.rowcount

    def insert_snapshots(self, rows: list[Snapshot]) -> int:
        """Append daily snapshots; ON CONFLICT DO NOTHING makes re-runs idempotent."""
        if not rows:
            return 0
        sql = """
            INSERT INTO raw_snapshot
                (artist_id, captured_on, subscribers, total_views, video_count)
            VALUES (%(artist_id)s, %(captured_on)s, %(subscribers)s,
                    %(total_views)s, %(video_count)s)
            ON CONFLICT (artist_id, captured_on) DO NOTHING
        """
        params = [
            {
                "artist_id": s.artist_id,
                "captured_on": s.captured_on,
                "subscribers": s.subscribers,
                "total_views": s.total_views,
                "video_count": s.video_count,
            }
            for s in rows
        ]
        with self._connect() as conn, conn.cursor() as cur:
            cur.executemany(sql, params)
            conn.commit()
            return cur.rowcount

    def deactivate_artist(self, artist_id: int) -> None:
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE tracked_artist SET is_active = FALSE WHERE artist_id = %s",
                (artist_id,),
            )
            conn.commit()

    def existing_channel_ids(self) -> set[str]:
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT channel_id FROM tracked_artist")
            return {r["channel_id"] for r in cur.fetchall()}

    def active_artists(self) -> list[TrackedArtist]:
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT artist_id, channel_id, title, genre, subs_at_entry,
                       source, discovered_at, is_active
                FROM tracked_artist WHERE is_active = TRUE
                ORDER BY artist_id
                """
            )
            return [
                TrackedArtist(
                    artist_id=r["artist_id"],
                    channel_id=r["channel_id"],
                    title=r["title"],
                    genre=r["genre"],
                    subs_at_entry=r["subs_at_entry"],
                    source=r["source"],
                    discovered_at=r["discovered_at"],
                    is_active=r["is_active"],
                )
                for r in cur.fetchall()
            ]

    def history_for(self, artist_id: int, until: date) -> list[Snapshot]:
        """All snapshots for an artist with captured_on <= until, ascending.

        The `until` bound is the leakage guard: callers computing as-of features pass
        `until=as_of`, so no future snapshot can ever reach feature code.
        """
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT artist_id, captured_on, subscribers, total_views, video_count
                FROM raw_snapshot
                WHERE artist_id = %s AND captured_on <= %s
                ORDER BY captured_on
                """,
                (artist_id, until),
            )
            return [
                Snapshot(
                    artist_id=r["artist_id"],
                    captured_on=r["captured_on"],
                    subscribers=r["subscribers"],
                    total_views=r["total_views"],
                    video_count=r["video_count"],
                )
                for r in cur.fetchall()
            ]

    def write_dataset(self, rows: list[dict[str, Any]], version: str) -> int:
        """UPSERT dataset rows on PK (artist_id, as_of_date, dataset_version)."""
        if not rows:
            return 0
        sql = """
            INSERT INTO dataset (
                artist_id, as_of_date, subs, growth_7d, growth_30d, accel, consistency,
                views_growth_7d, upload_rate_30d, inorganic_score, suspected_inorganic,
                fwd_growth_30d, is_breakout, dataset_version
            ) VALUES (
                %(artist_id)s, %(as_of_date)s, %(subs)s, %(growth_7d)s, %(growth_30d)s,
                %(accel)s, %(consistency)s, %(views_growth_7d)s, %(upload_rate_30d)s,
                %(inorganic_score)s, %(suspected_inorganic)s, %(fwd_growth_30d)s,
                %(is_breakout)s, %(dataset_version)s
            )
            ON CONFLICT (artist_id, as_of_date, dataset_version) DO UPDATE SET
                subs = EXCLUDED.subs,
                growth_7d = EXCLUDED.growth_7d,
                growth_30d = EXCLUDED.growth_30d,
                accel = EXCLUDED.accel,
                consistency = EXCLUDED.consistency,
                views_growth_7d = EXCLUDED.views_growth_7d,
                upload_rate_30d = EXCLUDED.upload_rate_30d,
                inorganic_score = EXCLUDED.inorganic_score,
                suspected_inorganic = EXCLUDED.suspected_inorganic,
                fwd_growth_30d = EXCLUDED.fwd_growth_30d,
                is_breakout = EXCLUDED.is_breakout
        """
        params = [{**r, "dataset_version": version} for r in rows]
        with self._connect() as conn, conn.cursor() as cur:
            cur.executemany(sql, params)
            conn.commit()
            return len(params)

    def read_dataset(self, version: str) -> pd.DataFrame:
        # Build the frame from dict_row cursor results directly. pandas.read_sql does not
        # officially support a raw psycopg3 connection and mis-parses the result here, so
        # we materialize rows ourselves — fully in our control and driver-agnostic.
        import pandas as pd

        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT * FROM dataset WHERE dataset_version = %(v)s ORDER BY as_of_date",
                {"v": version},
            )
            rows = cur.fetchall()  # list[dict] via dict_row factory
            cols = [d.name for d in cur.description] if cur.description else []
        return pd.DataFrame(rows, columns=cols)

    def stats(self) -> dict[str, Any]:
        """Powers `crescendo status`."""
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute("SELECT count(*) AS n FROM tracked_artist WHERE is_active = TRUE")
            active = cur.fetchone()["n"]
            cur.execute("SELECT count(*) AS n FROM tracked_artist")
            total = cur.fetchone()["n"]
            cur.execute("SELECT count(*) AS n FROM raw_snapshot")
            snaps = cur.fetchone()["n"]
            cur.execute(
                "SELECT min(captured_on) AS lo, max(captured_on) AS hi FROM raw_snapshot"
            )
            span = cur.fetchone()
            cur.execute("SELECT count(*) AS n FROM raw_snapshot WHERE captured_on = CURRENT_DATE")
            today = cur.fetchone()["n"]
        return {
            "artists_active": active,
            "artists_total": total,
            "snapshots_total": snaps,
            "snapshots_today": today,
            "history_from": span["lo"],
            "history_to": span["hi"],
        }

    def record_run(
        self,
        run_id: str,
        captured_on: date,
        n_snapshotted: int,
        n_deactivated: int,
        units_spent: int,
        status: str,
        detail: str | None = None,
    ) -> None:
        """Persist one collect pass to the audit trail (idempotent on run_id)."""
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO collect_run
                    (run_id, captured_on, n_snapshotted, n_deactivated,
                     units_spent, status, detail)
                VALUES (%(run_id)s, %(captured_on)s, %(n_snapshotted)s,
                        %(n_deactivated)s, %(units_spent)s, %(status)s, %(detail)s)
                ON CONFLICT (run_id) DO NOTHING
                """,
                {
                    "run_id": run_id,
                    "captured_on": captured_on,
                    "n_snapshotted": n_snapshotted,
                    "n_deactivated": n_deactivated,
                    "units_spent": units_spent,
                    "status": status,
                    "detail": detail,
                },
            )
            conn.commit()

    def recent_runs(self, limit: int = 5) -> list[dict[str, Any]]:
        """The last `limit` collect passes, newest first (powers `status`)."""
        with self._connect() as conn, conn.cursor() as cur:
            cur.execute(
                """
                SELECT run_id, ran_at, captured_on, n_snapshotted, n_deactivated,
                       units_spent, status, detail
                FROM collect_run ORDER BY ran_at DESC LIMIT %s
                """,
                (limit,),
            )
            return list(cur.fetchall())
