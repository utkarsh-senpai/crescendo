# Crescendo Game Backend (v1.1)

Spring Boot backend for the Crescendo salary-cap draft game, plus the installable PWA it serves.
Create a game, draft a salary-capped roster of emerging artists off a board scored by the deployed
**/predict** seam, then go head-to-head with a **transparent AI opponent** and score both rosters
on realised relative growth, ranked on a leaderboard.

This is the first consumer of the v0.3 prediction service
(`https://crescendo-predict.onrender.com`) — the Python + Java two-language seam.

## What's new in v1.1

- **Transparent AI opponent** (`OpponentDrafter`): one greedy-value bot that drafts off the same
  seam-scored board as the player (best organic breakout score per `$` under the cap, with a
  feasibility guard so it always fields a legal roster). It surfaces **why** — a per-pick
  rationale ("best organic value: 0.19 score at $22") and its top **snubs** ("passed: weaker
  value per $" / "passed: growth looks inorganic"). This is the headline novelty: a reasons-showing
  opponent, unclaimed in music.
- **Installable PWA** served from `src/main/resources/static/` (single deployable, `$0`): vanilla
  HTML/CSS/JS single-page flow (home → board → reveal → score → leaderboard), a web app manifest,
  and a cache-first service worker (offline shell, network-only `/api`). Lighthouse 100 across
  Accessibility / Best Practices / SEO on mobile.
- `GameService.draft()` now drafts the bot off the same `rankAll()` board; `score()` scores both
  rosters and sets the `outcome` (`PLAYER_WINS` / `AI_WINS` / `TIE`). The bot draft is wrapped so
  it can never fail the player's draft.

## Stack

- Java 21 (built with JDK 25 via `--release 21`), Spring Boot 3.4.2
- Spring Web + Data JPA + Validation, H2 in-memory (create-drop), springdoc OpenAPI/Swagger UI
- Calls the `/predict` seam with `RestClient`; degrades to salary-implied ordering if the seam
  is unreachable (Render free tier cold-starts), so the game is always playable.

## Build & run

This is a **personal-account** project, so the build must **not** use the machine-global
`~/.m2/settings.xml` (it mirrors everything through Zendesk's internal Artifactory with a
company account). Always pass the repo-local, Maven-Central-only settings:

```bash
mvn -s maven-settings.xml test          # run tests (18 green)
mvn -s maven-settings.xml spring-boot:run
```

Then open the PWA at http://localhost:8080/ or Swagger UI at http://localhost:8080/swagger-ui.html

> JDK 25 note: Mockito's Byte Buddy doesn't yet support Java 25, so surefire sets
> `-Dnet.bytebuddy.experimental=true` (see `pom.xml`).

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/games` | Create a game (`{ "playerName": "..." }`) |
| `GET` | `/api/games/{id}` | Game + roster view |
| `GET` | `/api/games/{id}/board` | Draft board, scored + reason-annotated by the seam |
| `POST` | `/api/games/{id}/draft` | Draft a roster (`{ "artistIds": [...] }`), enforces cap + size |
| `POST` | `/api/games/{id}/score` | Score on realised relative growth (`{ "scoreAsOfDate": "..." }`) |
| `GET` | `/api/leaderboard` | Scored games ranked by player score |

## Game rules (`crescendo.game.*` in `application.yml`)

- Salary cap `100`, roster size `5`.
- Draft date `2026-07-01` (as-of date sent to the seam), score date `2026-08-01`.
- Player score = mean realised `growth_30d` across the roster (relative growth, size-neutral).

## Demo data

`DataSeeder` seeds 10 electronic artists with draft + score feature snapshots. The cohort is
mixed: strong-organic artists that grow, plus two (`Bot Bloom`, `Phantom Streams`) with high raw
growth but a high `inorganic_score` — the seam discounts them ("growth looks inorganic") and
they under-deliver at score time, demonstrating the organic-breakout thesis. The transparent AI
opponent avoids both (surfacing them as snubs), so a player who drafts an inorganic artist tends
to lose to the bot — the thesis made visible in one head-to-head.
