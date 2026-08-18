# Crescendo — Research Pass (2026-08-18): the "organic breakout" reframing

> Second research pass, run **after the first genuine live-data pull** (43 real electronic/EDM
> channels discovered + snapshotted via the YouTube Data API v3). Companion to
> [`research-2026-08.md`](./research-2026-08.md). Records what the web research + the live data
> jointly implied, and the small idea-tweak they justified. Owner directive unchanged:
> **learning & uniqueness over earning; $0 to build, $0 to run.**

- **Method:** targeted `quick_search` passes (Researcher MCP), Aug 2026 (deep-research runs
  timed out again). Findings cross-checked against the live 43-artist cohort.

---

## 1. What the live data revealed

Characterizing the 43 real channels (2026-08-18 snapshot):

- subs: min 1,060 / median 4,840 / max 80,700 — cohort sits low in the 1k–100k band (good;
  that's where breakout signal lives).
- **15 of 43 channels are aggregator / compilation / reposter channels** ("No Copyright
  Music", "NCS Nightcore", "Artistic Maniacs" @ 3,167 videos) rather than individual emerging
  artists. The cohort has an **authenticity/purity problem** in miniature.
- `featuredChannelsUrls` is **empty on every hub tested** → YouTube deprecated it → the
  planned snowball is inert. Discovery is seed-driven for now (documented in the seed file).

## 2. What the web research said (2024–2026)

| Finding | Source signal | Implication for us |
|---|---|---|
| SOTA popularity models keep **adding audio features** (Hit Song Science; DHB-ILSTM/Wu 2024; arXiv 2505.07280) | music-popularity ML literature | Our **no-audio, artist-level momentum** lane stays genuinely differentiated. |
| Discovery in 2025–26 is "**cultural first, commercial second… tied to consistency, not momentary reach**" | MIDiA / Reprtoir 2025-26 outlooks | Validates `accel` + `consistency` as leading indicators over raw level. |
| **Leakage-safe temporal eval is mandatory**; random k-fold overstates by 20%+ RMSE | arXiv 2512.06932; ML-mastery; PLOS 2025 | Confirms our walk-forward + **per-fold decile labeling**. Keep it. |
| **AI-music / inorganic-growth fraud is THE 2025–26 music story**: Deezer tags ~75k AI tracks/day; **up to 85% of AI-track streams were fraudulent**; ~€4bn creator revenue at risk by 2028 | Deezer / Music Week / Forbes / HUMAN Security 2025-26 | Huge, current. But **everyone detects it at the platform level with audio fingerprinting**. |
| Gamers dislike **generative** AI in games (Quantic Foundry survey, 1.75M, Dec-2025); good game AI is "theater… interesting isn't optimal" | game-AI commentary 2025 | A **transparent, calibrated, beatable forecaster** is the *right* kind of AI — the opposite of "AI slop." Keep the transparent-opponent framing; never make it generative. |

## 3. The tweak — predict **organic** breakout, and prove the picks are clean

The gap the research leaves open: fraud detection is done **at the platform level, on audio**.
Nobody is doing **unsupervised inorganic-growth detection on public channel time-series, no
audio**, and *folding it into the prediction target*. Combined with the live-data authenticity
problem, that points to a small but sharpening reframe:

> **Old target:** who will break out (top-decile 30-day forward growth)?
> **New target:** who will break out **organically** — real momentum, not bought/inflated —
> and *demonstrate* our headline picks aren't pumped.

Concretely (all leakage-safe, all $0, no new data):

- **`organic_breakout` label** = top-decile forward growth **AND NOT** `suspected_inorganic`
  (the C3′ detector, computed from ≤as_of snapshots only).
- **New headline metrics:** `organic_precision@k`, `organic_lift`, and **`inorganic_rate@k`**
  (what share of the model's top-k picks are flagged — a leaderboard we'd surface wants this
  LOW).
- The **transparent AI opponent** now says not just *"I picked X because momentum + accel are
  high"* but *"…and X's inorganic_score is low, so I believe this growth is real."* Authenticity
  becomes a **reason the AI shows** — unclaimed in music prediction.

This is a reframe, not a rebuild: `inorganic_score` / `suspected_inorganic` already existed as a
feature; we promote authenticity from a side-signal to **part of the objective and the metric**.

## 4. Retest (leakage-safe, synthetic backfill anchored on real counts)

Backfill built 3 cohorts anchored on the real 08-18 subs (deterministic, seed=42): organic
breakouts (subs+views rise together), **pumped** breakouts (weekly bought-sub bursts with flat
views — the real bought-subs signature), and steady. The detector fired correctly: **28
dataset rows flagged (avg inorganic_score 0.93)**, and those rows carry **much higher raw
forward growth (0.45 vs 0.15)** — i.e. exactly the trap a naive growth-chaser falls into.

Temporal split @ 2026-07-10 (train `as_of < cutoff`, test in the next 30d), LightGBM:

| Model | precision@k | **organic_precision@k** | **inorganic_rate@k** |
|---|---|---|---|
| Crescendo (inorganic_score as feature) | 0.453 | **0.432** | **0.084** |
| Momentum baseline (`growth_7d`) | 0.400 | 0.368 | 0.126 |

**The model beats naive momentum on the authenticity axis: +17% organic precision and −33%
inorganic contamination among its headline picks.** It surfaces authentic breakouts *and*
resists the pumped channels that fool a momentum chaser.

**Honest null result:** training with `dq_mode=exclude` (dropping flagged rows from training)
was ≈ a wash vs keeping them — because `inorganic_score` is already a model **feature**, so the
model learns to discount pumps without discarding data. Cheaper design wins; recorded rather
than overclaimed.

## 5. Net effect on the plan

- **v0.1 target/metric** reframed to organic breakout (code + tests landed this pass).
- **L1/L2/L3** to be updated in the v0.2 pass: headline novelty becomes "leakage-safe
  **organic**-breakout prediction with an authenticity-showing transparent AI opponent."
- **Interview one-liner** sharpens to: *"Is **organic** emerging-artist breakout predictable
  from no-audio momentum on self-collected data — proven leakage-safe, and proven not to be
  chasing inflated growth?"*
