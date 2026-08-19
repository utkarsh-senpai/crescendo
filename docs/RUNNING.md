# Running Crescendo — Local, Docker, and Render

A complete, battle-tested guide to running Crescendo in every mode, plus the **exact
mistakes and error patterns** hit while building it (so you don't repeat them).

Crescendo has three deployable pieces:

| Piece | Dir | Language | What it is |
|---|---|---|---|
| **Game** (website + API) | `backend/` | Java 21 / Spring Boot 3.4.2 | Serves the PWA **and** the `/api/*` game endpoints. The frontend is NOT separate — Spring serves `src/main/resources/static/`. |
| **Predict seam** | `serving/` | Python 3.12 / FastAPI | `/predict` — scores artist momentum. A synthetic demo model is baked into the image. |
| **ML pipeline + cron** | `ml/` | Python 3.12 (`uv`) | Daily YouTube collector → Neon Postgres; trains the model. Not needed to run the game. |

The game calls the predict seam over HTTP. Both are `$0` on Render free tier.

```
Browser ─▶ crescendo-game (Java: website + game API) ─HTTP▶ crescendo-predict (Python: scoring)
                    │
                    └─JDBC▶ Postgres (games, leaderboard, feedback)   [H2 local · Neon prod]
```

---

## 0. Prerequisites

- **JDK 21+** (JDK 25 works; see the Byte Buddy note in Pitfalls).
- **Maven** (or use the repo's `maven-settings.xml`, below).
- **Python 3.12** + [`uv`](https://github.com/astral-sh/uv) (`~/.local/bin/uv`) — only for the predict seam / ML.
- **Docker** (only for the container modes).
- **macOS only:** `brew install libomp` — LightGBM/XGBoost need the OpenMP runtime.
- Optional: a **YouTube Data API key** (real-time stats + the ML cron). The game runs fine without it.

---

## 1. THE #1 GOTCHA: Maven build fails on SSL / Artifactory

This machine has a **company Maven mirror + truststore** configured globally, which breaks
building this personal project against Maven Central. **Two rules make every build work:**

1. **Always pass the repo's settings file:** `mvn -s maven-settings.xml ...`
   (`backend/maven-settings.xml` is Central-only, no company mirror, committed.)
2. **Always unset the truststore env var:** `env -u JAVA_TOOL_OPTIONS mvn ...`
   (A global `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=…nscacert…` makes Maven Central
   fail with `NoSuchAlgorithmException` / SSL handshake errors.)

**Canonical build/test command (run from `backend/`):**
```bash
env -u JAVA_TOOL_OPTIONS mvn -s maven-settings.xml test
```

- **Do NOT run `mvn clean`** offline — the `maven-clean-plugin` may not be cached, and offline
  mode can't fetch it. Skip `clean`; just `test` or `package`.
- First build needs network (to fetch deps into `~/.m2`); afterwards it's mostly cached.

---

## 2. Run mode A — Game only (fastest; no Python, no DB setup)

The game works **standalone**: it uses an in-memory **H2** database and, if the predict seam
is unreachable, **degrades to salary-implied ordering** so it's always playable.

```bash
cd backend
env -u JAVA_TOOL_OPTIONS mvn -s maven-settings.xml spring-boot:run
```

Then open **http://localhost:8080/**.

- H2 re-seeds the full artist roster (38 artists across Pop/EDM/Bollywood) on every boot.
- The board is scored by the **live Render predict seam** by default
  (`crescendo.predict.base-url=https://crescendo-predict.onrender.com`) — so the first board
  load may cold-start that service (~30–60s; the button shows "Waking the model up…").
- **macOS runtime flag:** if you see a `libomp`/native-load error, launch with:
  ```bash
  env -u JAVA_TOOL_OPTIONS DYLD_LIBRARY_PATH=/opt/homebrew/opt/libomp/lib \
    mvn -s maven-settings.xml spring-boot:run
  ```
  (In practice the Java backend has NO native deps — it calls the seam over HTTP — but this flag
  is harmless and avoids surprises.)

### Optional env vars (all optional locally)
| Env var | Effect |
|---|---|
| `YOUTUBE_API_KEY` | Enables real-time stats (`/api/live/*`). Without it, live rows are hidden. |
| `CRESCENDO_ADMIN_TOKEN` | Enables `GET /api/feedback` (admin read). Without it → 403 (fail-closed). |
| `CRESCENDO_PREDICT_BASE_URL` | Override the predict seam URL (e.g. point at a local seam). |

Example with everything on:
```bash
env -u JAVA_TOOL_OPTIONS DYLD_LIBRARY_PATH=/opt/homebrew/opt/libomp/lib \
  YOUTUBE_API_KEY="AIza…" CRESCENDO_ADMIN_TOKEN="$(openssl rand -hex 24)" \
  mvn -s maven-settings.xml spring-boot:run
```

---

## 3. Run mode B — Two CLIs (game + local predict seam)

Run the Python predict seam locally and point the game at it — no dependence on Render.

**Terminal 1 — predict seam (`serving/`):**
```bash
cd serving
uv sync
# Bake a synthetic demo model, then serve it. macOS needs libomp on the load path.
uv run python scripts/bake_model.py /tmp/baked.joblib
DYLD_LIBRARY_PATH=/opt/homebrew/opt/libomp/lib \
  MODEL_PATH=/tmp/baked.joblib CRESCENDO_CONFIG=../ml/config/crescendo.toml \
  uv run uvicorn crescendo_serving.app:app --host 0.0.0.0 --port 8000
```
Verify: `curl http://localhost:8000/health` → `model_loaded=true, dataset_version=synthetic-demo`.

**Terminal 2 — game (`backend/`), pointed at the local seam:**
```bash
cd backend
env -u JAVA_TOOL_OPTIONS CRESCENDO_PREDICT_BASE_URL=http://localhost:8000 \
  mvn -s maven-settings.xml spring-boot:run
```
Open http://localhost:8080/ — now fully local, no cold starts.

> **libomp is required for the seam**, not the game. If `bake_model.py` or uvicorn throws a
> `Library not loaded: @rpath/libomp.dylib`, run `brew install libomp` and re-export
> `DYLD_LIBRARY_PATH=/opt/homebrew/opt/libomp/lib`.

---

## 4. Run mode C — Docker

There are **two Dockerfiles** (one per service):

| Image | Dockerfile | Build context |
|---|---|---|
| Game | `backend/Dockerfile` | `backend/` |
| Predict | root `Dockerfile` | repo root (needs both `ml/` + `serving/`) |

**Game image:**
```bash
docker build -t crescendo-game backend/
docker run --rm -p 8080:8080 \
  -e CRESCENDO_PREDICT_BASE_URL=https://crescendo-predict.onrender.com \
  crescendo-game
# open http://localhost:8080/
```
- The game image uses **H2 by default** (no `SPRING_PROFILES_ACTIVE=prod`), so no DB needed.
- The image sets `SPRING_PROFILES_ACTIVE=prod` in the Dockerfile — for a self-contained local
  run **override it** so it doesn't demand a Neon `DATABASE_URL`:
  ```bash
  docker run --rm -p 8080:8080 -e SPRING_PROFILES_ACTIVE=default crescendo-game
  ```

**Predict image (built from repo root — copies `ml/` then `serving/`):**
```bash
docker build -t crescendo-predict .
docker run --rm -p 8000:8000 crescendo-predict
# curl http://localhost:8000/health
```

**Optional local Postgres** (mirrors prod without Neon): `docker-compose.yml` at the repo root
brings up pg16.
```bash
docker compose up -d          # starts Postgres on localhost:5432
```
Then run the game with the `prod` profile pointed at it (JDBC form — see the connection-string
gotcha below).

---

## 5. Deploy on Render (production)

Both services are Render **Blueprints** (`autoDeploy: true` on `main`).

**Game** (`backend/render.yaml` → builds `backend/Dockerfile`, service `crescendo-game`):
- Dashboard → New → Blueprint → repo → `main`.
- Set these secrets (all `sync:false`, dashboard-only):

  | Env var | Value |
  |---|---|
  | `DATABASE_URL` | **JDBC** form: `jdbc:postgresql://<host>-pooler.<region>.aws.neon.tech/<db>?sslmode=require` |
  | `DATABASE_USERNAME` | Neon role |
  | `DATABASE_PASSWORD` | Neon password (rotate if ever pasted anywhere) |
  | `CRESCENDO_ADMIN_TOKEN` | long random string (enables admin feedback read) |
  | `YOUTUBE_API_KEY` | enables real-time stats |

- `SPRING_PROFILES_ACTIVE=prod` and `CRESCENDO_PREDICT_BASE_URL` are already in the blueprint.
- Health check: `/actuator/health`.

**Predict** (root `render.yaml`, service `crescendo-predict`): no secrets — demo model is baked in.

**Deploy order & the double-deploy question:**
1. Merge to `main` → auto-deploys the **code**.
2. **Then** add/change an env var (e.g. `YOUTUBE_API_KEY`) → this **auto-triggers a second
   redeploy** (env changes always restart the service — expected, because the app reads env at
   startup). No manual retrigger needed.
- Don't add the key *before* the code that uses it is on `main`, or you'll just redeploy again.

**Cron** (`.github/workflows/collect.yml`, daily 04:00 UTC): GitHub → Settings → Secrets → add
`YOUTUBE_API_KEY` and `DATABASE_URL` (⚠ **the `postgresql://` form**, not `jdbc:`). Then
Actions → collect → Run workflow. The gh PAT can't dispatch workflows (403) — this is web-UI only.

---

## 6. Verifying it works (any mode)

```bash
curl http://localhost:8080/actuator/health          # {"status":"UP"}
curl http://localhost:8080/api/leagues               # Pop / EDM / Bollywood
curl http://localhost:8080/api/live/enabled          # {"enabled":true} iff YOUTUBE_API_KEY set
# Full flow:
curl -X POST http://localhost:8080/api/games -H 'Content-Type: application/json' \
  -d '{"playerName":"Test","league":"BOLLYWOOD"}'    # -> gameId
curl http://localhost:8080/api/games/<id>/board      # 12 Bollywood artists, seam-scored
```
On prod, the same against `https://crescendo-game.onrender.com`.

---

## 7. COMMON MISTAKES & ERROR PATTERNS (learned the hard way)

### Build / tooling
- **Maven SSL failure** (`NoSuchAlgorithmException`, cert/handshake errors): the global company
  truststore. Fix: `env -u JAVA_TOOL_OPTIONS mvn -s maven-settings.xml …`. **This is the single
  most common failure.**
- **`maven-clean-plugin … could not be resolved` (offline):** don't run `mvn clean`; the plugin
  isn't cached. Use `test`/`package` without `clean`.
- **Mockito / Byte Buddy on JDK 25:** surefire is configured with
  `-Dnet.bytebuddy.experimental=true` (JDK 25 is newer than Byte Buddy's supported ceiling).
  If tests fail to instrument, that flag is why it's there.
- **`libomp.dylib` not loaded (macOS):** `brew install libomp`, then
  `DYLD_LIBRARY_PATH=/opt/homebrew/opt/libomp/lib`. Needed at **runtime** for the Python seam/ML,
  not just build.

### Database / connection strings (the sneakiest)
- **Two different `DATABASE_URL` formats for the same DB:** the **game (Java/JDBC)** needs
  `jdbc:postgresql://…`; the **cron (Python/psycopg)** needs plain `postgresql://…`. Swapping them
  fails both. Same Neon DB, two formats, two places (Render env vs GitHub secret).
- **Use Neon's POOLED endpoint** (host has `-pooler`) + `sslmode=require`. Hikari is tuned for
  Neon's scale-to-zero (min-idle 0, max-lifetime < 5 min, 15s connection-timeout) — first query
  after idle rides out the wake instead of 500-ing.

### The prod-only data bug (v1.4.1) — READ THIS
- **`DataSeeder` seeds only when the table is stale/empty.** Local **H2 re-seeds every boot**, so
  local always looks correct. **Neon persists across deploys**, so a schema/data change (e.g.
  adding `channelId` or new artists) **silently does NOT appear in prod** — the old seed sticks.
- Symptom seen: prod served new code but **old data** — Bollywood showed 9 artists (not 12) and
  **live stats were empty** (old rows had null `channelId`).
- Fix shipped: the seeder now **self-heals** — re-seeds when the row count != expected OR any
  artist has a null `channelId` (`ArtistRepository.countByChannelIdIsNull()`).
- **LESSON: always verify on the REAL prod URL after deploy.** "H2-local passing" ≠ "Neon-prod
  correct" whenever seeding logic or the schema changes.

### Frontend / PWA
- **Service worker serves stale `app.js`/`styles.css` after a rebuild.** The PWA SW is cache-first
  for the shell, so a plain browser reload keeps serving old assets and `getComputedStyle` lies.
  - To verify a static edit: use a **fresh isolated browser context** (loads current files), or
    a **cache-busted fetch** (`fetch('/styles.css?x=1',{cache:'no-store'})`), or `curl` the server
    directly — trust those, not a reloaded tab.
  - Bump `SHELL_VERSION` in `sw.js` on any shell change so real users get the update.
- **Chrome DevTools MCP click races:** firing multiple clicks in one batch races the app's
  re-render → stale element uids / a queued click navigates away. **Drive the UI one action at a
  time**, re-snapshot between clicks.

### Render / deploy
- **Cold start (~1 min)** on free tier after ~15 min idle. The app shows a "Waking the model up…"
  state; `keep-warm.yml` pings both services (windowed 12h/day — the 750 free instance-hours are
  **per workspace, shared across both services**, so 24/7 on both exceeds the quota).
- **Adding an env var triggers a redeploy** — expected, not a bug.
- **`crescendo-predict` does NOT need a redeploy** for game/ML changes — it's built only from
  `serving/` + model bits of `ml/`; the demo model is baked at build time.

### GitHub / accounts
- **Two gh accounts share the keyring.** The active one can silently flip to `utkarsh-zendesk`
  and break personal pushes ("Repository not found"). Always
  `gh auth switch --user utkarsh-senpai` before pushing this repo.
- **The PAT can't set Render secrets or dispatch workflows (403)** — those are dashboard/web-UI
  steps only.

---

## 8. Quick reference

```bash
# Game only (H2, live Render seam)
cd backend && env -u JAVA_TOOL_OPTIONS mvn -s maven-settings.xml spring-boot:run

# Tests
cd backend && env -u JAVA_TOOL_OPTIONS mvn -s maven-settings.xml test

# Local predict seam
cd serving && uv run python scripts/bake_model.py /tmp/m.joblib && \
  DYLD_LIBRARY_PATH=/opt/homebrew/opt/libomp/lib MODEL_PATH=/tmp/m.joblib \
  CRESCENDO_CONFIG=../ml/config/crescendo.toml \
  uv run uvicorn crescendo_serving.app:app --port 8000

# ML pipeline (needs ml/.env with YOUTUBE_API_KEY + DATABASE_URL)
cd ml && uv run crescendo doctor && uv run crescendo seed-genres && uv run crescendo collect

# Docker
docker build -t crescendo-game backend/ && docker run --rm -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=default crescendo-game
```

Live prod: **https://crescendo-game.onrender.com** · predict: **https://crescendo-predict.onrender.com**
