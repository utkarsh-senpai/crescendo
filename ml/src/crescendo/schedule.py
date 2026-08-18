"""Local daily scheduler (C2, L3 §8).

Wraps `collect_once` in an APScheduler blocking job for local runs. In free-tier
deployment (v0.2) the GitHub Actions cron replaces this — the collector logic is identical.
"""

from __future__ import annotations

from datetime import UTC, datetime

from apscheduler.schedulers.blocking import BlockingScheduler
from apscheduler.triggers.cron import CronTrigger

from . import logging as log
from .collector import collect_once
from .config import Config
from .db import Db
from .youtube import QuotaAccountant, YouTubeClient


def run_scheduler(cfg: Config, hour: int = 4, minute: int = 0) -> None:
    """Block, running one collect pass per day at the given UTC time."""
    scheduler = BlockingScheduler(timezone="UTC")

    def job() -> None:
        today = datetime.now(UTC).date()
        acct = QuotaAccountant(cfg.daily_unit_ceiling, today)
        yt = YouTubeClient(cfg.youtube_api_key, acct)
        db = Db(cfg.database_url)
        log.info("schedule.tick", captured_on=str(today))
        collect_once(cfg, db, yt, captured_on=today)

    scheduler.add_job(job, CronTrigger(hour=hour, minute=minute))
    log.info("schedule.start", hour=hour, minute=minute)
    scheduler.start()
