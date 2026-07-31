SHELL := /bin/sh

COMPOSE := docker compose --env-file docker/.env -f docker/docker-compose.yml

.PHONY: build compose-config compose-down compose-up deploy deploy-down flink-package frontend-build frontend-install test verify-fast

build: frontend-build
	./mvnw package -DskipTests
	./mvnw -Pflink-job package -DskipTests

compose-config:
	$(COMPOSE) config --quiet

compose-down:
	$(COMPOSE) down

compose-up:
	$(COMPOSE) up -d

deploy:
	$(COMPOSE) --profile application up -d --build

deploy-down:
	$(COMPOSE) --profile application down

flink-package:
	./mvnw -Pflink-job package

frontend-build:
	pnpm build

frontend-install:
	pnpm install --frozen-lockfile

test:
	./mvnw test
	./mvnw -Pflink-job test

verify-fast: test frontend-build compose-config
