# 🎵 Crescendo

Crescendo is a non-gambling, non-NFT consumer game where players draft emerging artists under a salary cap and score on the artists' real-world momentum (relative growth) rather than their absolute size, competing head-to-head against a transparent AI opponent whose picks and reasoning are fully visible. Underneath the game sits a leakage-safe breakout-prediction model trained on a self-collected YouTube time-series, turning "who is about to blow up?" into a fair, explainable, skill-based game.

## Design docs

Design is layered L1 → L2 → L3 (context → logical architecture → detailed design). Code
starts after L3.

- [`docs/L1-solution-context.md`](docs/L1-solution-context.md) — L1 solution context
- [`docs/L1-system-context.md`](docs/L1-system-context.md) — L1 system-context diagram
- [`docs/L2-logical-architecture.md`](docs/L2-logical-architecture.md) — L2 logical architecture
- [`docs/L2-container-diagram.md`](docs/L2-container-diagram.md) — L2 container & data-flow diagram

## Roadmap

1. **L1** — solution context ✅
2. **L2** — logical architecture ✅
3. **L3** — detailed design (schemas, feature formulas, CLI, package layout, infra) ⏳
4. **MVP spike** — YouTube collector → time-series → leakage-safe modeling report
5. Game backend + minimal UI + transparent AI opponent
