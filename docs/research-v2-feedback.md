# Crescendo — v2.0 Research Feedback and Findings

> Consolidated reference for all external feedback passes, deep-research findings, and the
> resulting v2.0 feature roadmap. Created 2026-08-20.
>
> Consumers: [`L1-solution-context.md`](./L1-solution-context.md) §12,
> [`L3-detailed-design.md`](./L3-detailed-design.md) Part III.

---

## Section 1: Expert Reviewer Summary (28-point feedback)

The expert reviewer critique was structured around five themes. Key findings per theme:

### 1.1 ML correctness and leakage semantics

1. **Centered rolling windows are temporal leakage.** The most common subtle form found in
   music-prediction codebases: a rolling mean or standard deviation centered on day `t` uses
   data from `t+k` in addition to `t-k`. This is leakage even when the train/test split is
   chronological. Every rolling feature must be strictly trailing (backward-looking from `t`).
   Action: audit `features.py` for any `pd.Series.rolling(center=True)` or equivalent.

2. **Decision-time semantics, not just chronological split.** The correct leakage standard is
   not "training examples are before the test cutoff" — it is "every feature bit was knowable by
   a decision-maker acting at time `t`." This distinction matters for the inorganic detector:
   if the anomaly score uses any future snapshots (even in a trailing window that accidentally
   slips), it violates the contract. C3's structural guard (`history_for(until=as_of)`) is the
   right approach; it must be extended to the Historical Replay path.

3. **Fold-scoped decile label is correct.** The existing per-fold decile threshold rule (§4,
   §18) is confirmed by the reviewer as the right approach. Global decile labeling is a subtle
   form of label leakage — good that it is already pinned.

4. **The organic_breakout target is novel and correct.** Folding the inorganic detector into the
   prediction target (not just as a feature) is confirmed as a clean, unclaimed lane.
   "Everyone detects fraud at the platform level, on audio. You detect it from behavioral
   time-series, no audio, and make it part of the objective."

5. **AUC 0.96 on n=43 warrants a caveat.** With 43 artists and synthetic backfill anchoring the
   evaluation, AUC can be inflated by test set homogeneity. The honest framing is:
   "on this synthetic-plus-real cohort, the model achieves these metrics — real-data signal
   matures ~late September 2026." The docs already include this caveat; keep it prominent.

### 1.2 Evaluation methodology

6. **Brier score + ECE are missing.** The existing evaluation reports precision@k and AUC but
   does not report calibration. Brier score (mean squared error of predicted probabilities) and
   Expected Calibration Error (ECE) are standard for probabilistic classifiers and are required
   if the project claims uncertainty quantification. Action: add to `evaluate.py` and
   `model_registry` (Sprint 2 / v2.0).

7. **Walk-forward folds with n=43 may produce degenerate test folds.** With expanding windows
   and 43 artists, later folds may have very few test rows. Guard `resolve_k(k, y_test)` with
   a minimum fold size check (e.g., skip folds with fewer than 10 test rows).

8. **Report lift confidence intervals, not just point estimates.** Bootstrap the precision@k
   over the test fold to produce a 90% CI on lift. A lift of 1.13 with a CI of [0.9, 1.4] is
   a different story than [1.05, 1.25]. The ablation table (Sprint 5) should include these.

9. **Ablation table is the key deliverable for ML credibility.** Features × baselines × model
   families × loss functions, with statistical significance. This is what separates a portfolio
   project from a homework assignment.

### 1.3 Product framing and differentiation

10. **Historical Replay is the highest-leverage feature.** It is the one thing no competitor
    offers that is immediately demonstrable in an interview or a live demo, and it forces the
    codebase to prove its leakage guarantees at runtime rather than just in unit tests.

11. **"You discovered Artist X N days before their breakout" is the headline.** This is the
    concrete, emotional, shareable moment the product needs. Make it the first thing a new
    player sees after completing a replay session.

12. **The AI archetypes add interview depth.** "Four agents, same model, different utility
    functions" is a clean explanation of preference learning and multi-objective optimization
    that lands in an ML-engineer interview. Prioritize the Contrarian archetype (Discovery Edge
    utility) as the most distinctive.

13. **The transparent AI opponent needs a counterfactual, not just reasons.** "I picked X
    because momentum is high" is a reason. "I passed on Y because their inorganic_score would
    have been 0.85 — growth looks purchased" is a counterfactual. The latter is far more
    memorable and demonstrates model interpretability, not just feature importance.

14. **Free/non-gambling framing is a competitive advantage, not just a constraint.** The
    reviewer noted that in 2026, DFS-style skill games are under regulatory scrutiny in 8+ US
    states. The fact that Crescendo has no paid entry and no cash prizes is not just a $0 budget
    decision — it is a legal and competitive moat.

### 1.4 Engineering completeness

15. **The predict API contract is the right abstraction boundary.** Everything downstream of
    `/predict` (game, AI opponent, archetypes, replay) must read from the same response object.
    The v2.0 additions (interval fields, discovery_edge, risk_adj_score) extend the contract
    without breaking it.

16. **Model Registry is a portfolio differentiator.** Most portfolio projects train a model and
    show metrics. Tracking every artifact with its training window, feature set, and eval
    metrics — plus drift detection — demonstrates production ML engineering thinking.

17. **`crescendo reproduce` closes the reproducibility story.** The ability to replay an exact
    experiment from its hash is rare in portfolio projects and directly demonstrates MLOps
    thinking. Keep the implementation simple (load config hash → rebuild dataset → train with
    fixed seed → assert match).

18. **The quota accountant is a genuinely good resume story.** "I designed a resource budgeting
    system for a scarce external API" is concrete and defensible. The `charge-before-call`
    pattern (reserve units before the API call, never after) is a detail that shows systems
    thinking.

19. **Neon DB password rotation is an open security TODO.** The Neon connection string was
    exposed in a chat session. The production password should be rotated before the project is
    shared publicly. See `docs/RUNNING.md` for the procedure. Do not commit the Neon connection
    string to the repository.

20. **The monorepo layout (ml/ + serving/ + backend/ + frontend/) is correct.** It signals
    full-stack thinking while keeping the ML core isolated. The only addition needed for v2.0
    is a `ml/src/crescendo/uncertainty.py` module and a `ml/src/crescendo/replay.py` module.

### 1.5 What to NOT build

21. **LLM-based pick determination is wrong.** If the AI archetype's pick logic involves an LLM
    deciding which artist to draft, the "plays by the rules it shows you" guarantee breaks down.
    The pick logic must be deterministic. An LLM can format the display text; it cannot make
    the decision.

22. **Cross-platform signals (TikTok/Spotify) are blocked.** TikTok's API is effectively closed
    to individuals; Spotify's extended API requires org-level access. Do not design around
    these. The YouTube-only constraint is a feature of the story: "we proved predictability from
    a single free signal."

23. **Artist influence graph is not in scope.** It requires collaboration data (featured-artist
    credits) that is not available from the YouTube Data API v3.

24. **Real-time multiplayer is out of scope.** WebSockets add infrastructure cost and complexity
    with no portfolio value relative to the ML story. The AI opponent and replay mode are both
    turn-based.

25. **iOS/App Store is explicitly out of scope.** The $99/year Apple developer fee has no place
    in a $0 project. PWA install on iOS is degraded. Desktop + Android PWA is the correct
    delivery target.

26. **Do not fabricate inorganic accusations against real artists.** The `inorganic_score` is
    a model posterior derived from behavioral time-series. It must never be pre-assigned in the
    seed file or documentation. All real artists are seeded with `inorganic_score = NULL`.

27. **Do not commit unverified channel IDs.** Expansion artists (Fred Again, AP Dhillon, etc.)
    must have their YouTube channel IDs verified live via `channels.list` before the IDs are
    committed to `ml/seeds/genre_artists.txt`.

28. **Don't overclaim the live-data results.** The n=43 cohort with synthetic backfill is a
    proof of concept. The eval numbers (+17% organic precision, -33% inorganic rate, AUC 0.96)
    are correct for what they measure; headline them with the caveat that real-signal maturity
    is ~late September 2026.

---

## Section 2: Hiring Manager Assessment (4-year YOE bar)

The hiring manager reviewed the project as a portfolio artifact for a Machine Learning Engineer
role at the senior/staff level (approximately 4 years of experience bar).

### 2.1 Strengths

- **End-to-end ownership is clear.** The project demonstrates the full ML lifecycle:
  data collection, feature engineering, model training, evaluation, serving, and a product
  wrapper. Most candidates show one or two stages.

- **Leakage-safety as a first-class engineering concern.** The per-fold decile label, the
  `history_for(until=as_of)` structural guard, and the documentation of why random k-fold is
  wrong — these signal that the candidate understands the failure modes of ML evaluation in
  production.

- **The organic-breakout reframe is the portfolio differentiator.** "I trained a model to
  predict organic breakout, folded a fraud detector into the target, and proved the picks
  aren't pumped" is a one-sentence story that is memorable, novel, and demonstrates domain
  thinking.

- **The $0 constraint is a strength, not a weakness.** Every design decision made under the
  free-tier constraint (quota accountant, seed+snowball, Neon, GitHub Actions cron) is a
  concrete story about trade-off reasoning.

- **Two-language system (Python + Spring Boot) is a differentiator.** Most ML portfolios are
  pure Python. A working Spring Boot game backend demonstrates full-stack thinking and Java
  fluency.

### 2.2 Current limitations (addressed by v2.0)

- **Point predictions only; no uncertainty.** A senior ML engineer is expected to think about
  calibration and intervals. The prediction service returning only a score without confidence
  bounds is a gap. Sprint 2 (Uncertainty Engine) directly addresses this.

- **Single AI archetype.** The current AI opponent plays one strategy. Multiple archetypes with
  different utility functions would demonstrate understanding of multi-objective optimization
  and preference modeling.

- **No model versioning or drift tracking.** The absence of a model registry is a visible gap
  for a production ML role. Sprint 7 (Model Registry + Drift Detection) closes this.

- **Evaluation report is sparse on statistical significance.** Bootstrap confidence intervals
  on precision@k lift are expected for any ML paper or production report. Sprint 5 ablation
  table addresses this.

### 2.3 High-impact enhancements (the v2.0 priority list)

1. **Historical Replay** (Sprint 1) — immediately demonstrable, forces leakage proof at
   runtime, memorable product moment.
2. **Uncertainty / Prediction Intervals** (Sprint 2) — closes the calibration gap; adds Brier
   and ECE to the evaluation report.
3. **Discovery Edge metric** (Sprint 3) — novel, academically grounded, enables the
   Contrarian archetype.
4. **Artist Roster Expansion** (Sprint 4, parallelizable) — broader coverage increases
   generalization story credibility.
5. **ML Core Upgrades: Isolation Forest + LambdaRank** (Sprint 5) — converts the inorganic
   detector from a heuristic to a defensible ML contribution.
6. **AI Archetypes + TreeSHAP counterfactuals** (Sprint 6) — deepens the interview story.
7. **Model Registry + `crescendo reproduce`** (Sprint 7) — closes the MLOps gap.

---

## Section 3: Deep-Research Workflow Findings (2026-08-20)

The deep-research workflow ran 107 parallel agents with adversarial verification on the
following research questions. Findings are organized as confirmed claims, killed claims, and
open questions.

### 3.1 Confirmed claims

**Conformal prediction is feasible at n=43–50 with correction.**
StatsForecast (open-source, cross-validation windows, LightGBM as black box) is a practical
on-ramp. SSBC correction or the cross-validation conformal variant is required. Without
correction, raw split-conformal at n~50 has a ~40% violation rate — the intervals are
dishonest. TQA (NeurIPS 2022, arXiv:2205.09940) and W-TQA (Stanford May 2026,
arXiv:2605.17705) are the research-grade upgrade path.

**Discovery Edge is a novel framing in the music domain.**
No music-prediction paper or competitor surfaces a metric analogous to
`predicted_growth − cohort_momentum_baseline`. The Bayesian surprise framing from recommender
systems (arXiv:2308.06368, ACM 2023) provides academic grounding: a pick is "surprising" when
the model rates an artist higher than cohort momentum alone would predict.

**Historical Replay is the highest-leverage v2.0 feature.**
It is the single feature that (a) is immediately demonstrable in a portfolio review or live
demo, (b) forces the codebase to prove its leakage guarantees at runtime, and (c) creates the
memorable "you discovered X N days before breakout" product moment. No competitor offers it.

**Decision-time semantics are the correct leakage standard, not just chronological split.**
A chronological train/test split is necessary but not sufficient. Centered rolling statistics
(window centered on `t` using `t+k` data) pass a naive chronological split check but are still
leakage. The standard is: every feature bit must be knowable by a decision-maker acting at
time `t`. The `Db.history_for(until=as_of)` structural guard is the correct implementation;
it must be extended to the Historical Replay path.

**Isolation Forest + LOF are appropriate for the inorganic detector upgrade.**
On the feature set (subs, views, video_count, growth_7d, growth_30d, views_growth_7d,
upload_rate_30d), Isolation Forest and LOF are standard unsupervised anomaly detection methods
with well-understood behavior. The v2.0 upgrade should report AUC against the synthetic
pumped-channel ground truth as a standalone contribution.

**LambdaRank directly optimizing NDCG@k is the right objective for a ranking problem.**
The current regression→threshold approach is a proxy. LambdaRank is the standard choice for
learning-to-rank problems where the downstream metric is NDCG or precision@k. LightGBM
supports it natively (`objective = "lambdarank"`).

**Multi-horizon (7d/14d/30d) adds interpretable signal.**
The 7d vs 30d confidence delta ("short sprint vs sustained momentum") is a concrete,
player-understandable output of the multi-task model. It also provides a natural counterfactual
(high 7d confidence + low 30d confidence = the artist is heating up but may not sustain).

### 3.2 Killed claims

**TreeSHAP is SHAP applied to tree models; it is not a new algorithm.**
"TreeSHAP counterfactuals" does not mean a new model — it means using LightGBM's native SHAP
values (which TreeSHAP computes efficiently) to generate the `reasons` field. The
implementation is `shap.TreeExplainer(model.booster).shap_values(feature_row)`, not a
separate model or service.

**W-TQA requires more history than is available in v0.1.**
W-TQA's panel structure requires sufficient history to estimate temporal distributional shift.
At n=43 artists and ~45 days of data, W-TQA is not yet applicable. StatsForecast cross-
validation conformal is the correct Phase 1 approach; W-TQA is the upgrade path for ~late
September 2026 onward.

**The strategy leaderboard does not require live head-to-head play.**
"Model vs baselines vs human avg vs each AI archetype" can be computed entirely from stored
session outcomes. No real-time multiplayer infrastructure is needed.

### 3.3 Open questions

- **What is the correct λ for risk-adjusted scoring?** The three presets (conservative=1.5,
  balanced=0.75, aggressive=0.25) are estimates. The right approach is to learn λ from player
  behavior (revealed preferences) over time. This is a v3.0 concern.

- **How many artists are needed before W-TQA is reliable?** The original W-TQA paper
  (arXiv:2605.17705) targets panel sizes of 50–200. With 50+ artists (Sprint 4 target),
  W-TQA becomes applicable. Empirical validation needed when the expanded roster is in place.

- **Will the cohort momentum baseline be stable with n=50 artists?** With 10 momentum
  percentile buckets and 50 artists, each bucket has ~5 artists — which may be too small for
  a reliable median. Consider 5 buckets (quintiles) at n=50, moving to 10 (deciles) at n=100+.

---

## Section 4: Prioritized v2.0 Feature Roadmap

### P0 — Highest priority (blocking the strongest portfolio claims)

| Feature | Why P0 | Sprint |
|---|---|---|
| Historical Replay | Most demonstrable, forces leakage proof at runtime, memorable | S1 |
| Strictly trailing window audit + fix | Correctness: centered windows are leakage even under chronological split | S1 prereq |
| Brier score + ECE in evaluation | Required for any calibration/uncertainty claim | S2 |
| Conformal prediction intervals (SSBC-corrected) | Closes the "point predictions only" gap; requires calibration metrics first | S2 |

### P1 — High priority (strongly differentiate the portfolio)

| Feature | Why P1 | Sprint |
|---|---|---|
| Discovery Edge metric | Novel, academically grounded, enables Contrarian archetype | S3 |
| Artist Roster Expansion (50+ artists) | Generalization story credibility; more data for intervals | S4 |
| AI Archetypes + TreeSHAP counterfactuals | Interview depth; demonstrates multi-objective optimization | S6 |
| Model Registry + drift detection | Closes MLOps gap visible to hiring managers | S7 |

### P2 — Medium priority (nice-to-have depth)

| Feature | Why P2 | Sprint |
|---|---|---|
| Isolation Forest + LOF inorganic detector | Upgrades heuristic to ML; AUC as standalone contribution | S5 |
| Multi-horizon (7d/14d/30d) | Adds interpretable player-facing signal | S5 |
| LambdaRank objective | More principled ranking loss; requires ablation table | S5 |
| `crescendo reproduce` command | Demonstrates reproducibility; lower urgency than drift detection | S7 |

---

## Section 5: Academic References

| Reference | One-line summary | Relevant to |
|---|---|---|
| TQA — "Temporal Quantile Adjustments" (NeurIPS 2022, arXiv:2205.09940) | Adaptive conformal prediction intervals for time series using temporal quantile forests; produces per-instance coverage | C5 Uncertainty Engine — Phase 2 interval method |
| W-TQA — "Weighted Temporal Quantile Adjustments" (Stanford, May 2026, arXiv:2605.17705) | Panel extension of TQA that accounts for distributional shift over time; requires panel size ~50+ | C5 Uncertainty Engine — Phase 3 interval method |
| Bayesian Surprise for Recommender Systems (arXiv:2308.06368, ACM 2023) | Defines "surprise" as how much a recommendation deviates from the user's prior expectation, grounding novelty-seeking in information theory | C6 Discovery Edge — academic grounding for the cohort-baseline subtraction |
| Temporal leakage in time-series ML (arXiv:2512.06932; PLOS 2025) | Demonstrates that random k-fold overstates performance by 20%+ RMSE vs walk-forward; confirms per-fold labeling as mandatory | C3/C4 leakage guards — confirms existing design |
| LambdaRank / LambdaMART (Burges et al.; LightGBM docs) | Learning-to-rank with NDCG@k as the optimized objective; standard for ranking problems | C4 ML upgrade — Sprint 5 objective function |
| SHAP / TreeSHAP (Lundberg & Lee, NeurIPS 2017; SHAP library) | Fast exact SHAP values for tree models; interpretable feature attribution | AI Archetype reasons + counterfactuals (Sprint 6) |
| Deviation Game (Steam; Ars Electronica) | Human-vs-AI entertainment game; proves the human-vs-AI loop; no music application | L1 §11 — transparent AI opponent precedent |
| Deezer AI fraud detection (Music Week / Forbes 2025–26) | ~75k AI tracks tagged per day; ~85% of AI-track streams fraudulent; platform-level audio fingerprinting | L1 §11 — confirms the "no-audio behavioral detection" gap |
| Hit Song Science / APEX (arXiv:2605.03395; MDPI 2026) | State of the art: pre-release audio features of label-backed songs; orthogonal to our no-audio, artist-level approach | L1 §11 — confirms the differentiation from audio-based ML |
