"""Structured JSON logging (L3 §20).

One JSON object per line, fixed envelope: ts, level, event, run_id, + event context.
Kept dependency-light (stdlib logging + json) so it works everywhere the package runs.
Timestamps are stamped from the wall clock at the edge here (never inside pure functions).
"""

from __future__ import annotations

import json
import logging
import sys
import uuid
from datetime import UTC, datetime

_LOGGER = logging.getLogger("crescendo")
_RUN_ID: str | None = None


def new_run_id() -> str:
    """Allocate a fresh run_id for one CLI invocation and make it the ambient id."""
    global _RUN_ID
    _RUN_ID = uuid.uuid4().hex
    return _RUN_ID


def current_run_id() -> str | None:
    """The ambient run_id (so audit rows can be correlated with the JSON log stream)."""
    return _RUN_ID


def configure(level: str = "info") -> None:
    """Attach a single stdout handler that passes the pre-rendered JSON line through."""
    _LOGGER.setLevel(getattr(logging, level.upper(), logging.INFO))
    if _LOGGER.handlers:
        return
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(logging.Formatter("%(message)s"))
    _LOGGER.addHandler(handler)
    _LOGGER.propagate = False


def _emit(level: str, event: str, **context: object) -> None:
    record = {
        "ts": datetime.now(UTC).isoformat(timespec="seconds").replace("+00:00", "Z"),
        "level": level,
        "event": event,
        "run_id": _RUN_ID,
        **context,
    }
    line = json.dumps(record, default=str)
    getattr(_LOGGER, level if level != "warning" else "warning", _LOGGER.info)(line)


def debug(event: str, **ctx: object) -> None:
    _emit("debug", event, **ctx)


def info(event: str, **ctx: object) -> None:
    _emit("info", event, **ctx)


def warning(event: str, **ctx: object) -> None:
    _emit("warning", event, **ctx)


def error(event: str, **ctx: object) -> None:
    _emit("error", event, **ctx)
