"""Service-level tests: real CrescendoModel.predict, ranking + tiebreak (no HTTP)."""

from __future__ import annotations

from datetime import date

from crescendo_serving.schemas import ArtistFeatures


def test_ranks_descending_and_assigns_1_based_rank(service):
    artists = [
        ArtistFeatures(artist_id=1, features={"growth_7d": 0.01, "accel": 0.0}),
        ArtistFeatures(artist_id=2, features={"growth_7d": 0.15, "accel": 0.05}),  # strongest
        ArtistFeatures(artist_id=3, features={"growth_7d": -0.05, "accel": -0.02}),
    ]
    resp = service.predict(date(2026, 9, 1), artists)

    assert [r.rank for r in resp.ranked] == [1, 2, 3]
    scores = [r.breakout_score for r in resp.ranked]
    assert scores == sorted(scores, reverse=True)
    # The high-momentum artist should top the board.
    assert resp.ranked[0].artist_id == 2
    assert resp.model_kind == "lgbm"
    assert resp.dataset_version == "test-fixture"


def test_deterministic_tiebreak_on_artist_id(service):
    # Identical features -> identical scores -> ties broken by ascending artist_id.
    feats = {"growth_7d": 0.05, "accel": 0.01}
    artists = [
        ArtistFeatures(artist_id=30, features=dict(feats)),
        ArtistFeatures(artist_id=10, features=dict(feats)),
        ArtistFeatures(artist_id=20, features=dict(feats)),
    ]
    resp = service.predict(date(2026, 9, 1), artists)
    assert [r.artist_id for r in resp.ranked] == [10, 20, 30]


def test_every_pick_has_reasons(service):
    artists = [ArtistFeatures(artist_id=1, features={"growth_7d": 0.12, "inorganic_score": 0.1})]
    resp = service.predict(date(2026, 9, 1), artists)
    assert resp.ranked[0].reasons  # non-empty
    assert "growth looks organic" in resp.ranked[0].reasons


def test_inorganic_pick_is_flagged_in_reasons(service):
    artists = [ArtistFeatures(artist_id=1, features={"growth_7d": 0.20, "inorganic_score": 0.95})]
    resp = service.predict(date(2026, 9, 1), artists)
    assert "discounted: growth looks inorganic" in resp.ranked[0].reasons
