SHELL := /bin/sh

JAVA_HOME ?= $(shell if [ -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]; then printf '%s' /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home; elif command -v /usr/libexec/java_home >/dev/null 2>&1; then /usr/libexec/java_home -v 21 2>/dev/null; fi)
PNPM := pnpm --dir portal

.PHONY: build compose-build compose-config compose-down compose-up frontend-build frontend-install frontend-lint frontend-typecheck openapi-check test verify-fast

build: frontend-build
	JAVA_HOME="$(JAVA_HOME)" ./mvnw package -DskipTests

compose-config:
	docker compose --profile '*' -f docker/docker-compose.yml config --quiet

compose-build:
	docker compose --profile '*' -f docker/docker-compose.yml build

compose-down:
	docker compose --profile '*' -f docker/docker-compose.yml down

compose-up:
	docker compose --profile core --profile compute --profile gateway --profile apps --profile maintenance --profile observability -f docker/docker-compose.yml up -d --wait

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
