"""FastAPI app exposing the L3 §11 contract: POST /predict and GET /health.

The model is loaded once at startup (lazily, so the module imports without a model present —
tests inject their own service via `app.state`). Both the game backend and the transparent AI
opponent call POST /predict; there is deliberately no separate "AI" endpoint (L2 §5).
"""

from __future__ import annotations

import os

from fastapi import FastAPI, HTTPException, Request

from . import __version__
from .schemas import HealthResponse, PredictRequest, PredictResponse
from .service import PredictService

app = FastAPI(
    title="Crescendo Prediction Service",
    version=__version__,
    summary="Leakage-safe organic-breakout scoring with transparent reasons (L3 §11).",
)


def _get_service(request: Request) -> PredictService:
    """Return the process-wide service, loading it on first use.

    Lazy load keeps import side-effect-free (tests set app.state.service directly) while a real
    deployment still loads the artifact exactly once.
    """
    service = getattr(request.app.state, "service", None)
    if service is None:
        try:
            service = PredictService.from_env()
        except Exception as exc:  # surface any load failure as a clean 503
            raise HTTPException(status_code=503, detail=f"model unavailable: {exc}") from exc
        request.app.state.service = service
    return service


@app.get("/health", response_model=HealthResponse)
def health(request: Request) -> HealthResponse:
    """Liveness + model metadata. Never 5xx — reports model_loaded=false instead."""
    service = getattr(request.app.state, "service", None)
    if service is None and os.environ.get("MODEL_PATH"):
        # Try an on-demand load so /health reflects reality, but don't fail the probe if it can't.
        try:
            service = PredictService.from_env()
            request.app.state.service = service
        except Exception:  # noqa: BLE001 — health must stay 200 even with no model
            service = None
    if service is None:
        return HealthResponse(status="ok", version=__version__, model_loaded=False)
    return HealthResponse(
        status="ok",
        version=__version__,
        model_loaded=True,
        model_kind=service.model.model_kind,
        dataset_version=service.model.dataset_version,
        n_features=len(service.model.feature_names),
    )


@app.post("/predict", response_model=PredictResponse)
def predict(req: PredictRequest, request: Request) -> PredictResponse:
    service = _get_service(request)
    try:
        return service.predict(req.as_of_date, req.artists)
    except ValueError as exc:
        # e.g. CrescendoModel.predict raises when NO known feature columns are supplied.
        raise HTTPException(status_code=422, detail=str(exc)) from exc


def main() -> None:
    """`crescendo-serve` entrypoint — run the ASGI app with uvicorn."""
    import uvicorn

    uvicorn.run(
        "crescendo_serving.app:app",
        host=os.environ.get("HOST", "127.0.0.1"),
        port=int(os.environ.get("PORT", "8000")),
        reload=False,
    )


if __name__ == "__main__":
    main()
