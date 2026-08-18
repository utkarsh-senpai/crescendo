"""Quota accountant tests (L3 §9, §16): never exceed the ceiling; reserve-before-call."""

from __future__ import annotations

from datetime import date

import pytest

from crescendo.youtube import QuotaAccountant, QuotaExceeded


def test_charge_accumulates_and_reports_remaining():
    acct = QuotaAccountant(daily_ceiling=100, today=date(2026, 8, 18))
    acct.charge(30, "channels.list")
    acct.charge(20, "playlistItems.list")
    assert acct.spent == 50
    assert acct.remaining() == 50


def test_charge_blocks_before_exceeding_ceiling():
    acct = QuotaAccountant(daily_ceiling=100, today=date(2026, 8, 18))
    acct.charge(90, "channels.list")
    with pytest.raises(QuotaExceeded):
        acct.charge(20, "channels.list")  # would reach 110 > 100
    # Blocked call must NOT have been charged (reserve-before-call semantics).
    assert acct.spent == 90


def test_exact_ceiling_is_allowed():
    acct = QuotaAccountant(daily_ceiling=100, today=date(2026, 8, 18))
    acct.charge(100, "channels.list")
    assert acct.spent == 100
    with pytest.raises(QuotaExceeded):
        acct.charge(1, "channels.list")
