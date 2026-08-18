# Crescendo predict API (serving/, v0.3) — demo deployment image.
#
# Monorepo build: serving/ has a path dependency on ml/ (the `crescendo` package holding the
# CrescendoModel seam), so BOTH dirs are copied and ml/ is installed first. A deterministic
# synthetic model is baked at build time (see serving/scripts/bake_model.py) — DEMO data until
# real breakout signal matures (~late Sep 2026). Swap for a Neon-trained model later.
#
# Build context = repo root:  docker build -t crescendo-serving .
FROM python:3.12-slim

# libgomp1 = OpenMP runtime LightGBM/XGBoost link against (the Linux libomp).
RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Install the ML core first (satisfies serving's `crescendo` dependency), then the service.
# pip doesn't read serving's [tool.uv.sources], so we resolve the path dep explicitly here.
COPY ml/ ./ml/
RUN pip install --no-cache-dir ./ml

COPY serving/ ./serving/
RUN pip install --no-cache-dir ./serving

# Bake the synthetic demo model into the image and point the app at it.
ENV MODEL_PATH=/app/models/baked.joblib
ENV CRESCENDO_CONFIG=/app/ml/config/crescendo.toml
RUN python serving/scripts/bake_model.py "$MODEL_PATH"

# Render provides $PORT; default to 8000 for local `docker run`.
ENV HOST=0.0.0.0
ENV PORT=8000
EXPOSE 8000

# Use the port Render injects at runtime; fall back to 8000.
CMD ["sh", "-c", "uvicorn crescendo_serving.app:app --host 0.0.0.0 --port ${PORT:-8000}"]
