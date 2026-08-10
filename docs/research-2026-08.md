# Crescendo — Research Pass (2026-08-10)

> Companion to the L1/L2/L3 revision of 2026-08-10. A competitive + ML-literature web
> research pass to make Crescendo more novel/unique and to review the existing plan. This
> file records **what was found and the sources**, so the design docs can cite conclusions
> without carrying raw links. Owner directive framing this pass: **learning & uniqueness over
> earning; $0 to build and $0 to run.**

- **Method:** parallel web searches (Researcher MCP). Deep-research runs timed out; findings
  below are from targeted `quick_search` passes, Aug 2026.
- **Consumers:** [`L1-solution-context.md`](./L1-solution-context.md) §11 (positioning),
  [`L2-logical-architecture.md`](./L2-logical-architecture.md) (new signal/QA component),
  [`L3-detailed-design.md`](./L3-detailed-design.md) (inorganic-growth flag, PWA delivery).

---

## 1. Competitive landscape — the field ran toward paid cash prizes

| Player | What it is now (2026) | Why it matters to us |
|---|---|---|
| **FanLabel / FanLabel SongPicks** | Launched **SongPicks**: *paid-entry, skill-based, cash-prize* song-contest app. Self-describes as a *"music gamification and **prediction market** entertainment company."* Modes: *Ranker* (rank songs by streams), *Best of Five*. Holds **5 patents** on music gamification / prediction-market contracts. | The incumbent moved **song-level + paid + prediction-market** — exactly what we avoid. Our **artist-draft, free, relative-growth** design stays clear of both the patents and the paid lane. |
| **SongPicks.com** | "Fantasy sports for music," cash entry from **$1**, **geo-blocked in AZ, IA, LA, MI, SC, WA**. | The state-by-state geo-block is the DFS/gambling regulatory tell. Confirms paid entry = legal + financial cost we refuse. |
| **Vault — "Fantasy Record Label"** | FanDuel founders (2023); weekly **cash prizes**; roster of musicians. | Nearest to the original idea, but cash-prize + went quiet post-acquisition. Earliness scoring is not novel; the *free + AI-opponent* combination is. |
| **Chartmetric / Soundcharts / Viberate / Instrumental** | **B2B A&R analytics** dashboards. | The *consumer, gamified, free* version remains the gap. Not competitors for our audience. |

**Conclusion:** the defensible white space is **free, non-gambling, artist-level drafting on
relative growth, with a transparent AI opponent** — *more* open in 2026 than at first scoping,
and a perfect fit for a $0 learning project. We compete on **the AI and the method, never prizes.**

---

## 2. Transparent AI opponent — precedent exists, not in music

- **Deviation Game** (Steam; also exhibited at Ars Electronica): humans-vs-AI guessing/drawing
  game — proves the human-vs-AI *entertainment* loop.
- **2026 scoping review / meta-analysis** (Ito et al., arXiv) on **player enjoyment competing
  against human vs. AI opponents** — academic backing that the loop is engaging.
- **Explainable-AI game work** (e.g. interpretable churn/decision models, IEEE VIS 2025;
  AAAI 2026 interpretable-reasoning nets) — the "show the model's reasoning" primitive exists.
- **Gap:** none apply a **reason-showing AI opponent to music breakout prediction.** → our
  headline novelty. The AI's `reasons` come from the *same* model that scores the game.

---

## 3. ML frontier — Hit Song Science vs. our angle

- **Hit Song Science** is mature but centers on **pre-release audio features of already-
  released, label-backed commercial songs** (DeepHits, MDPI 2026; APEX multi-task "aesthetic
  quality", arXiv 2605.03395; multiple audio+social RF/GBM studies 2024–2026).
- **New frontier:** the **AI-generated music flood** — Suno/Udio grew ~**12M → 100M users** in
  ~a year, producing tracks with **no traditional reputation/label signals**; a #1 on
  Billboard Country Digital Song Sales was AI-assisted. Research is pivoting to predicting
  success *without* reputation signals.
- **Where Crescendo sits:** **artist-level, no-audio, self-collected YouTube momentum**,
  leakage-safe temporal eval, precision@k vs base rate. Orthogonal to the audio canon →
  defensibly novel *and* free (no audio-feature extraction / no model-hosting cost).
- **New creative thread (adopted):** the AI-music flood is **both**
  (a) a **breakout signal** (synthetic-native artists can spike fast), and
  (b) an **inorganic-growth data-quality risk** (bot/purchased/synthetic spikes pollute the
  momentum signal). Modeling (b) — an *inorganic-growth flag* — is pure analysis, costs $0, and
  adds a distinctive "data integrity in the wild" story. → L2 new component, L3 feature + flag.

---

## 4. YouTube Data API v3 — the wall is real and tightening

- Free; **10,000 units/day** per project; resets **midnight PT**; no paid tier (increases by
  application only, approval not guaranteed).
- `channels.list` (statistics) = **1 unit**; batchable **50 channels / call**. `playlistItems.list`
  = 1 unit/page. `search.list` = **100 units**.
- **New (since 2026-06-01):** `search.list` has a **separate ~100-call/day bucket** on top of
  the 10k pool. → avoiding `search.list` is no longer just cost-saving, it's a capacity wall.
- **Implication:** seed+snowball on 1-unit calls is the *only* free way to scale discovery.
  Our quota accountant + snowball design (L2/L3) is vindicated.

---

## 5. Net effect on the plan

1. **L1** — reframe as **free / learning-first / $0**; elevate transparent-AI to a headline;
   add competitor + API findings as §11; add PWA delivery decision (desktop + Android, no iOS).
2. **L2** — add a small **Signal Enrichment & Data-Quality** concern/component: the inorganic-
   growth flag, and hooks for optional free public signals; note PWA in tech mapping.
3. **L3** — add an `inorganic_growth` feature/flag spec, a `suspected_inorganic` column, and a
   **PWA/free-tier delivery** section; keep everything inside free tiers.

*(No competitor offers free artist-level drafting + a reason-showing AI opponent; that
combination, on a self-collected no-audio pipeline, shipped $0 as a PWA, is the unique claim.)*
