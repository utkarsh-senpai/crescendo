"""YouTube Data API v3 client + quota accountant (L3 §14, §16, §21).

The quota accountant is the "budgeted a scarce resource" story made concrete: reserve
units BEFORE the call so we never overspend, even on a partial failure. Every client
method charges its per-op cost as its first line.
"""

from __future__ import annotations

import random
import time
from datetime import UTC, date, datetime

import requests

from . import logging as log
from .types import ArtistStats

_API_BASE = "https://www.googleapis.com/youtube/v3"
_MAX_RETRIES = 3
_BATCH_SIZE = 50  # channels.list accepts up to 50 ids for 1 unit


class QuotaExceeded(Exception):
    """Raised (client-side) before a call that would breach the daily unit ceiling."""

    def __init__(self, op: str, want: int, remaining: int):
        super().__init__(f"quota would be exceeded by {op}: want {want}, {remaining} remaining")
        self.op = op
        self.want = want
        self.remaining_units = remaining


class QuotaAccountant:
    def __init__(self, daily_ceiling: int, today: date):
        self.daily_ceiling = daily_ceiling
        self.today = today
        self.spent = 0

    def charge(self, units: int, op: str) -> None:
        """Reserve units BEFORE the call. Raises QuotaExceeded if it would breach ceiling."""
        if self.spent + units > self.daily_ceiling:
            log.warning(
                "quota.block", op=op, want=units, spent=self.spent, ceiling=self.daily_ceiling
            )
            raise QuotaExceeded(op, units, self.remaining())
        self.spent += units

    def remaining(self) -> int:
        return self.daily_ceiling - self.spent


class YouTubeClient:
    def __init__(self, api_key: str, accountant: QuotaAccountant, session: requests.Session | None = None):
        self._key = api_key
        self._acct = accountant
        self._session = session or requests.Session()

    # ---- public API (per-op quota cost noted; §14) ----

    def channel_stats(self, channel_id: str) -> ArtistStats:
        """1 unit."""
        return self.channel_stats_batch([channel_id])[0]

    def channel_stats_batch(self, channel_ids: list[str]) -> list[ArtistStats]:
        """1 unit per <=50 ids. Malformed items are skipped (logged), never partial-written."""
        results: list[ArtistStats] = []
        for i in range(0, len(channel_ids), _BATCH_SIZE):
            chunk = channel_ids[i : i + _BATCH_SIZE]
            self._acct.charge(1, "channels.list")
            data = self._get(
                "channels",
                {"part": "snippet,statistics", "id": ",".join(chunk), "maxResults": _BATCH_SIZE},
            )
            for item in data.get("items", []):
                parsed = _parse_channel(item)
                if parsed is not None:
                    results.append(parsed)
        return results

    def playlist_channel_ids(self, playlist_id: str) -> list[str]:
        """1 unit per page. Returns the channel IDs owning the playlist's items (deduped, ordered)."""
        seen: set[str] = set()
        ordered: list[str] = []
        page_token: str | None = None
        while True:
            self._acct.charge(1, "playlistItems.list")
            params = {"part": "snippet", "playlistId": playlist_id, "maxResults": 50}
            if page_token:
                params["pageToken"] = page_token
            data = self._get("playlistItems", params)
            for item in data.get("items", []):
                cid = item.get("snippet", {}).get("videoOwnerChannelId")
                if cid and cid not in seen:
                    seen.add(cid)
                    ordered.append(cid)
            page_token = data.get("nextPageToken")
            if not page_token:
                break
        return ordered

    def related_channels(self, channel_id: str) -> list[str]:
        """Snowball edge via the channel's featured/related channels (cheap; no search.list).

        channels.list(part=brandingSettings) exposes featuredChannelsUrls at 1 unit — we
        never touch search.list (100 units) so the snowball stays quota-cheap and bounded.
        """
        self._acct.charge(1, "channels.list.branding")
        data = self._get("channels", {"part": "brandingSettings", "id": channel_id})
        items = data.get("items", [])
        if not items:
            return []
        branding = items[0].get("brandingSettings", {}).get("channel", {})
        featured = branding.get("featuredChannelsUrls", []) or []
        # Deterministic order (no set iteration) for reproducible snowball (§15).
        out: list[str] = []
        seen: set[str] = set()
        for cid in featured:
            if cid not in seen:
                seen.add(cid)
                out.append(cid)
        return out

    # ---- transport with retry/backoff (§21) ----

    def _get(self, endpoint: str, params: dict) -> dict:
        url = f"{_API_BASE}/{endpoint}"
        params = {**params, "key": self._key}
        last_exc: Exception | None = None
        for attempt in range(_MAX_RETRIES):
            try:
                resp = self._session.get(url, params=params, timeout=30)
            except requests.RequestException as exc:  # network/timeout
                last_exc = exc
                self._sleep_backoff(attempt)
                continue
            if resp.status_code == 200:
                return resp.json()
            if resp.status_code == 403 and "quota" in resp.text.lower():
                # Server-side quota guard; trust it even though our accountant should prevent it.
                raise QuotaExceeded(endpoint, 0, self._acct.remaining())
            if 500 <= resp.status_code < 600:
                last_exc = RuntimeError(f"{endpoint} HTTP {resp.status_code}")
                self._sleep_backoff(attempt)
                continue
            # 4xx (other than quota) — not retryable.
            resp.raise_for_status()
        raise RuntimeError(f"{endpoint} failed after {_MAX_RETRIES} retries: {last_exc}")

    @staticmethod
    def _sleep_backoff(attempt: int) -> None:
        # Exponential backoff + jitter. random() is fine here (transport, not modeling).
        time.sleep((2**attempt) * 0.5 + random.uniform(0, 0.25))


def _parse_channel(item: dict) -> ArtistStats | None:
    """Provider item -> ArtistStats. Returns None on missing fields (caller skips + logs)."""
    try:
        stats = item["statistics"]
        return ArtistStats(
            channel_id=item["id"],
            title=item["snippet"]["title"],
            subscribers=int(stats["subscriberCount"]),
            total_views=int(stats["viewCount"]),
            video_count=int(stats["videoCount"]),
            fetched_at=datetime.now(UTC),
        )
    except (KeyError, ValueError, TypeError):
        log.warning("youtube.parse_skip", channel_id=item.get("id"))
        return None
