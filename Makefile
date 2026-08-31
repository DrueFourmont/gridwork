# Gridwork. The five commands in CLAUDE.md, and nothing else.
#
# make up    starts the dependencies only. The API runs from make api, so a
#            restart is a keystroke rather than a container rebuild.

.PHONY: up api web test down help
.DEFAULT_GOAL := help

help:
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-8s %s\n", $$1, $$2}'

up: ## start postgres, redis, and localstack
	docker compose up -d postgres redis localstack

api: ## run the api on :8080 with the local profile
	./gradlew :api:bootRun --args='--spring.profiles.active=local'

web: ## run the vite dev server on :5173
	cd web && npm run dev

test: ## run every test: jvm unit and integration, then web unit
	./gradlew build
	cd web && npm run typecheck && npm run lint && npm run test

down: ## stop everything and remove the volumes
	docker compose down -v
