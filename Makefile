SHELL := /bin/sh

JAVA_HOME ?= $(shell if [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then printf '%s' /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; elif command -v /usr/libexec/java_home >/dev/null 2>&1; then /usr/libexec/java_home -v 21 2>/dev/null; fi)
PNPM := pnpm --dir portal

.PHONY: build compose-build compose-config compose-down compose-up e2e-platform-foundation frontend-build frontend-install frontend-lint frontend-typecheck openapi-check test verify-fast

build: frontend-build
	JAVA_HOME="$(JAVA_HOME)" ./mvnw package -DskipTests

compose-config:
	docker compose --env-file docker/versions.env --env-file docker/env/.env.example --profile '*' -f docker/docker-compose.yml config --quiet

compose-build:
	docker/scripts/compose.sh --profile '*' build

compose-down:
	docker/scripts/compose.sh --profile '*' down

compose-up:
	docker/scripts/compose.sh --profile core --profile compute --profile gateway --profile apps --profile maintenance --profile auth --profile observability up -d --wait

e2e-platform-foundation:
	docker/scripts/e2e-verify.sh

frontend-build:
	$(PNPM) build

frontend-install:
	$(PNPM) install --frozen-lockfile

frontend-lint:
	$(PNPM) lint

frontend-typecheck:
	$(PNPM) typecheck

openapi-check:
	$(PNPM) openapi:lint

test:
	JAVA_HOME="$(JAVA_HOME)" ./mvnw test

verify-fast: test frontend-lint frontend-typecheck frontend-build openapi-check compose-config
