# Ontology Platform

Ontology Platform 是一个以“数据—本体—应用—AIP”为闭环的通用本体平台，面向单台 Linux 服务器上的 Docker Compose 部署。完整产品、架构和实施范围以 [实施计划](docs/plan.md) 为准。

## 平台基线

- Java 21 + Spring Boot 多模块后端
- React 18 + TypeScript + Vite + Ant Design 前端
- `docs/openapi/openapi.yaml` 作为公共 HTTP API 唯一契约
- Docker Compose 单机部署，不使用 Kubernetes
- 当前阶段免登录，使用固定本地管理员身份
- APISIX、Flink、Pulsar、HugeGraph、OpenSearch、MinIO 与 SkyWalking Compose 基础设施

## 本地验证

安装 Java 21、Docker、Node.js 22 和 pnpm 后运行：

```bash
pnpm --dir portal install --frozen-lockfile
make verify-fast
```

启动平台：

```bash
make compose-build
make compose-up
make e2e-platform-foundation
make e2e-storage
```

浏览器访问 `http://localhost:9080/data/connections`，无需登录。当前 Compose 会在 `${BIND_ADDRESS:-0.0.0.0}` 暴露 Portal、Flink、Pulsar、MinIO、OpenSearch、HugeGraph、PostgreSQL 和 SkyWalking 管理端口；部署到公网服务器前请使用防火墙或将 `BIND_ADDRESS` 设为内网地址。

## 目录

- `backend/`：平台 Java 服务、契约和测试支持
- `deploy/`：部署配置、验证数据、Secret 示例和运维脚本
- `docker/`：唯一的 Compose 入口 `docker-compose.yml`
- `docs/`：ADR、架构、OpenAPI、验证证据和运行手册
- `examples/`：可重复生成的无敏感样例
- `portal/`：产品前端

每个 P00—P17 Phase 只产生一个 signed-off Conventional Commit；进度和验证证据记录在 [timeline.md](timeline.md)。
