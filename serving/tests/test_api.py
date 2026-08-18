"""HTTP contract tests for POST /predict and GET /health (FastAPI TestClient)."""

from __future__ import annotations


def test_health_reports_loaded_model(client):
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "ok"
    assert body["model_loaded"] is True
    assert body["model_kind"] == "lgbm"
    assert body["n_features"] == 8


def test_predict_contract_shape(client):
    payload = {
        "as_of_date": "2026-09-01",
        "artists": [
            {"artist_id": 42, "features": {"growth_7d": 0.08, "accel": 0.03, "consistency": 0.9}},
            {"artist_id": 7, "features": {"growth_7d": -0.02, "accel": -0.01}},
        ],
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    body = resp.json()
    assert body["as_of_date"] == "2026-09-01"
    assert body["model_kind"] == "lgbm"
    assert len(body["ranked"]) == 2
    top = body["ranked"][0]
    assert set(top) == {"artist_id", "breakout_score", "rank", "reasons"}
    assert top["rank"] == 1
    assert isinstance(top["reasons"], list) and top["reasons"]
    # ranks are 1..n and scores descending
    assert [r["rank"] for r in body["ranked"]] == [1, 2]
    assert body["ranked"][0]["breakout_score"] >= body["ranked"][1]["breakout_score"]


def test_predict_rejects_empty_artists(client):
    resp = client.post("/predict", json={"as_of_date": "2026-09-01", "artists": []})
    assert resp.status_code == 422  # pydantic min_length


def test_predict_no_known_features_is_422(client):
    # Caller sends only unknown feature keys -> CrescendoModel.predict raises ValueError -> 422.
    payload = {
        "as_of_date": "2026-09-01",
        "artists": [{"artist_id": 1, "features": {"totally_unknown": 1.0}}],
    }
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 422


def test_predict_tolerates_partial_features(client):
    # Only one known feature supplied — service reindexes the rest to NaN, still scores.
    payload = {"as_of_date": "2026-09-01", "artists": [{"artist_id": 1, "features": {"growth_7d": 0.05}}]}
    resp = client.post("/predict", json=payload)
    assert resp.status_code == 200
    assert resp.json()["ranked"][0]["artist_id"] == 1
