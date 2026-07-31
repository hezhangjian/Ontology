SHELL := /bin/sh

COMPOSE := docker compose --env-file docker/.env -f docker/docker-compose.yml

.PHONY: build compose-config compose-down compose-up deploy deploy-down flink-package frontend-build frontend-install test verify-fast

build: frontend-build
	mvn package -DskipTests
	mvn -f pom-flink.xml package -DskipTests

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
	mvn -f pom-flink.xml package

frontend-build:
	pnpm build

frontend-install:
	pnpm install --frozen-lockfile

test:
	mvn test
	mvn -f pom-flink.xml test

verify-fast: test frontend-build compose-config
