# Gridwork. The five commands in CLAUDE.md, plus the workflow lint that make
# test runs for you.
#
# make up    starts the dependencies only. The API runs from make api, so a
#            restart is a keystroke rather than a container rebuild.

.PHONY: up api web test lint-ci down help
.DEFAULT_GOAL := help

help:
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-8s %s\n", $$1, $$2}'

up: ## start postgres, redis, and localstack
	docker compose up -d postgres redis localstack

api: ## run the api on :8080 with the local profile
	./gradlew :api:bootRun --args='--spring.profiles.active=local'

web: ## run the vite dev server on :5173
	cd web && npm run dev

lint-ci: ## lint the github actions workflows
# A workflow file that does not parse is rejected by GitHub before any job is
# scheduled, so the run fails in zero seconds with no step log and nothing to
# read. CI cannot catch that about itself. This is the only place it gets
# caught, which is why it runs first in make test.
	@command -v actionlint >/dev/null 2>&1 || { \
		echo "actionlint not found. install it with: brew install actionlint"; \
		echo "it catches workflow yaml that GitHub rejects before any job starts."; \
		exit 1; }
	actionlint

test: ## run everything: workflow lint, jvm build and tests, web checks
	$(MAKE) lint-ci
	./gradlew build
	cd web && npm run typecheck && npm run lint && npm run test

down: ## stop everything and remove the volumes
	docker compose down -v
