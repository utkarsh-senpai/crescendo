# Crescendo — L1 System Context Diagram

> Renders natively on GitHub (Mermaid in a fenced code block).
> **Solid arrows = MVP (modeling spike). Dashed arrows = later scope (game / AI opponent).**

```mermaid
flowchart LR
    subgraph ACTORS["Actors"]
        MOD["🧑‍💻 Modeler<br/>(you)"]
        PLAYER["🧑 Player<br/>(later)"]
        AI["🤖 AI Opponent<br/>(later)"]
    end

    subgraph CORE["Crescendo Core System"]
        direction TB
        INGEST["📥 Data Ingestion<br/>(Python, cron)<br/>YouTube collector + quota budget"]
        DB[("🗄️ Postgres<br/>artist time-series<br/>+ game data (later)")]
        ML["🧠 ML Prediction Service<br/>(Python)<br/>momentum features · relative-growth target<br/>temporal split · precision@k"]
        GAME["🎮 Game Backend<br/>(Spring Boot, later)<br/>auth · salary-cap draft<br/>scoring · leaderboard"]
    end

    subgraph EXT["External"]
        YT["📺 YouTube Data API v3<br/>(10k units/day)"]
    end

    %% MVP flows (solid)
    YT -->|"pulls snapshots"| INGEST
    INGEST -->|"writes time-series"| DB
    DB -->|"reads features"| ML
    MOD -->|"runs spike, reads report"| ML

    %% Later flows (dashed)
    PLAYER -.->|"drafts / views scores"| GAME
    AI -.->|"transparent picks"| GAME
    GAME -.->|"asks for predictions"| ML
    ML -.->|"same model powers AI"| AI
    GAME -.->|"reads/writes game data"| DB

    classDef mvp fill:#e8f0fe,stroke:#1a73e8,stroke-width:2px,color:#0b3d91;
    classDef later fill:#f5f5f5,stroke:#9aa0a6,stroke-dasharray:4 3,color:#5f6368;
    classDef ext fill:#fef7e0,stroke:#f9ab00,color:#7a5900;
    class INGEST,DB,ML,MOD mvp;
    class GAME,PLAYER,AI later;
    class YT ext;
```
