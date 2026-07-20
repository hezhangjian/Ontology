# Ontology Platform

Ontology Platform 是一个以“数据—本体—应用—AIP”为闭环的通用本体平台，面向单台 Linux 服务器上的 Docker Compose 部署。完整产品、架构和实施范围以 [plan.md](plan.md) 为准。

## 平台基线

- Java 21 + Spring Boot 多模块后端
- React 18 + TypeScript + Vite + Ant Design 前端
- `docs/openapi/openapi.yaml` 作为公共 HTTP API 唯一契约
- Docker Compose 单机部署，不使用 Kubernetes
- Keycloak OIDC + PKCE 默认登录
- APISIX、Flink、Pulsar、HugeGraph、OpenSearch、MinIO 与 SkyWalking Compose 基础设施

## 本地验证

安装 Java 21、Docker、Node.js 22 和 pnpm 后运行：

```bash
pnpm --dir portal install --frozen-lockfile
make verify-fast
```

启动默认认证的平台：

```bash
make compose-build
make compose-up
make e2e-platform-foundation
make e2e-storage
```

浏览器访问 `http://localhost:9080/data/connections`，通过 Keycloak 完成 PKCE 登录。仓库内示例密码仅用于本机验证；部署前必须按 [P01 运行手册](docs/runbooks/p01-compose-foundation.md) 替换全部 secret。投影一致性、DLQ 和索引重建操作见 [P02 运行手册](docs/runbooks/p02-projection-storage.md)。

## 目录

- `backend/`：平台 Java 服务、契约和测试支持
- `docker/`：Compose、镜像、配置和运维脚本
- `docs/`：ADR、架构、OpenAPI、验证证据和运行手册
- `examples/`：可重复生成的无敏感样例
- `portal/`：产品前端

每个 P00—P17 Phase 只产生一个 signed-off Conventional Commit；进度和验证证据记录在 [timeline.md](timeline.md)。
