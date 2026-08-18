# serving/ — prediction API (v0.3, stub)

Reserved for the FastAPI `predict()` service (L3 §11). Not built in v0.1.

The `POST /predict` contract both the game backend and the transparent AI opponent will
call (so "the AI plays by the rules it shows you" is true by construction):

```
POST /predict
Request:  { "as_of_date": "2026-09-01",
            "artists": [ { "artist_id": 42, "features": { "growth_7d": 0.08, ... } } ] }
Response: { "as_of_date": "2026-09-01",
            "ranked": [ { "artist_id": 42, "breakout_score": 0.81, "rank": 1,
                          "reasons": ["high 7d accel", "steady consistency"] } ] }
```

It wraps `crescendo.model.CrescendoModel.predict`; `reasons` come from
`feature_importances()`. Ships in v0.3.
