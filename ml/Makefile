# Thin wrappers around the crescendo CLI. All commands read config/crescendo.toml + .env.
.PHONY: sync status discover collect build-dataset train evaluate test lint

sync:
	uv sync

status:
	uv run crescendo status

discover:
	uv run crescendo discover --max-artists 300

collect:
	uv run crescendo collect

build-dataset:
	uv run crescendo build-dataset --as-of-start $(START) --as-of-end $(END)

train:
	uv run crescendo train --model lgbm --cutoff $(CUTOFF)

evaluate:
	uv run crescendo evaluate --cutoff $(CUTOFF) --walk-forward

test:
	uv run pytest -q

lint:
	uv run ruff check src tests
