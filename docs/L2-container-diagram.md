# Crescendo — L2 Container & Data-Flow Diagram

> Logical components of the MVP spike + the raw→curated data boundary.
> **Solid = MVP. Dashed = future (game / AI opponent). Blue = MVP components, amber = external, grey = later.**

```mermaid
flowchart TB
    subgraph EXT["External"]
        YT["📺 YouTube Data API v3<br/>channels.list · playlistItems<br/>(10k units/day)"]
        SEED["🌱 Seed playlists<br/>(curated electronic/EDM)"]
    end

    subgraph PKG["crescendo — Python package + CLI (single MVP deployable)"]
        direction TB
        C1["C1 · Discovery / Resolver<br/>seed + snowball → channel IDs<br/>filter 1k–100k, active<br/>(cheap calls only)"]
        C2["C2 · Snapshot Collector<br/>daily channel stats<br/>quota-budgeted, immutable writes<br/>GH Actions cron · run-log audit"]
        C3["C3 · Cohort & Feature Builder<br/>leakage-safe as-of features<br/>30d forward relative-growth label<br/>breakout = cohort top-decile"]
        C4["C4 · Modeling & Evaluation<br/>temporal split · LightGBM/XGBoost<br/>ORGANIC precision@k vs base rate"]
    end

    subgraph DB["S1 · Postgres"]
        direction TB
        RAW[("raw_snapshot<br/>append-only, immutable")]
        CUR[("tracked_artist + dataset<br/>curated, reproducible")]
        RUN[("collect_run<br/>per-run health audit · v0.2")]
    end

    OUT["📊 Model artifact + metrics<br/>+ report notebook"]

    subgraph FUT["Future scope"]
        GAME["🎮 Game Backend (Spring)"]
        AI["🤖 AI Opponent"]
        PLAYER["🧑 Player"]
    end

    %% MVP solid flows
    SEED --> C1
    YT -->|"cheap resolve"| C1
    C1 -->|"tracked_artist"| CUR
    YT -->|"daily snapshots"| C2
    CUR -.->|"who to track"| C2
    C2 -->|"append snapshot"| RAW
    C2 -->|"record run health"| RUN
    RAW -->|"reads ≤ as_of + (t,t+30d] label"| C3
    C3 -->|"curated dataset"| CUR
    CUR -->|"dataset"| C4
    C4 --> OUT

    %% Future dashed flows
    PLAYER -.-> GAME
    GAME -.->|"predict(features@t)"| C4
    AI -.->|"same predict contract"| C4
    GAME -.-> CUR

    classDef mvp fill:#e8f0fe,stroke:#1a73e8,stroke-width:2px,color:#0b3d91;
    classDef store fill:#e6f4ea,stroke:#137333,color:#0d652d;
    classDef ext fill:#fef7e0,stroke:#f9ab00,color:#7a5900;
    classDef later fill:#f5f5f5,stroke:#9aa0a6,stroke-dasharray:4 3,color:#5f6368;
    class C1,C2,C3,C4,OUT mvp;
    class RAW,CUR,RUN store;
    class YT,SEED ext;
    class GAME,AI,PLAYER later;
```

## Reading the diagram

1. **C1** seeds from curated playlists + cheap YouTube calls → writes `tracked_artist`.
2. **C2** snapshots those artists daily → appends immutable rows to `raw_snapshot`, **and
   records a per-run health row to `collect_run`** (v0.2) so the unattended GH Actions cron is
   observable after the fact (`crescendo status` surfaces the last runs; `crescendo doctor`
   preflights the run).
3. **C3** reads raw snapshots with strict as-of / forward-window boundaries → writes a
   curated, reproducible `dataset` (this is where leakage is prevented).
4. **C4** does a temporal split, trains, and evaluates **organic** precision@k vs base rate
   (top-decile forward growth AND not `suspected_inorganic`) → report.
5. **Future (dashed):** the game backend and AI opponent both call **C4's prediction
   contract** — the single seam that keeps the model swappable and the AI "honest".
