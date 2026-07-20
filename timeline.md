# Ontology implementation timeline

> 目标：严格按 `plan.md` 的 P00—P17 顺序交付整个系统。每个 Phase 只对应一个 signed-off Conventional Commit；涉及前端的 Phase 必须在 Codex 内置浏览器中完成真实点击测试。

## 恢复点

- 当前状态：P01 已完成并准备提交。
- 下一阶段：P02 `feat(storage): add ontology projection foundation`。
- 继续规则：确认 P01 提交存在且工作树干净，再只领取 P02；不得提前实现 P03 页面 Phase。

## Phase 状态

| Phase | 状态 | Commit | 验证摘要 |
|---|---|---|---|
| P00 | 已完成 | `build(platform): establish project baseline`（本记录与 Phase 同一提交） | J/F/O/C + 内置浏览器点击通过 |
| P01 | 已完成 | `feat(platform): add authenticated compose foundation`（本记录与 Phase 同一提交） | J/F/O/C + `E:platform-foundation` + 内置浏览器 PKCE 点击通过 |
| P02 | 待实施 | `feat(storage): add ontology projection foundation` | `E:storage-e2e` |
| P03 | 待实施 | `feat(connections): deliver data connections page` | `E:connections-page` |
| P04 | 待实施 | `feat(pipelines): deliver pipeline builder page` | `E:pipelines-page` |
| P05 | 待实施 | `feat(quality): deliver data quality page` | `E:quality-page` |
| P06 | 待实施 | `feat(lineage): deliver data lineage page` | `E:lineage-page` |
| P07 | 待实施 | `feat(modeling): deliver ontology management page` | `E:modeling-page` |
| P08 | 待实施 | `feat(explorer): deliver object exploration page` | `E:explorer-page` |
| P09 | 待实施 | `feat(dashboards): deliver analytics dashboard page` | `E:dashboards-page` |
| P10 | 待实施 | `feat(applications): deliver business applications page` | `E:applications-page` |
| P11 | 待实施 | `feat(approvals): deliver action and approval center` | `E:approvals-page` |
| P12 | 待实施 | `feat(automations): deliver automation page` | `E:automations-page` |
| P13 | 待实施 | `feat(aip): deliver agent studio` | `E:agent-studio-page` |
| P14 | 待实施 | `feat(aip): deliver conversations and assistant` | `E:conversations-page` |
| P15 | 待实施 | `feat(admin): deliver control panel` | `E:control-panel-page` |
| P16 | 待实施 | `test(platform): harden security and resilience` | `E:security-resilience` |
| P17 | 待实施 | `chore(release): verify production delivery` | `E:all` |

## P00 — 项目基线

- 完成时间：2026-07-20 14:48 CST
- Commit：`build(platform): establish project baseline`
- 范围：
  - 纳入最新 `plan.md` 并建立本 timeline。
  - 建立 Java 21 / Spring Boot 3.5.3 Maven 聚合与 8 个边界模块，固定 Maven Wrapper 3.9.9。
  - 建立 React 18、TypeScript、Vite、Ant Design 与 pnpm 锁文件的中文产品外壳。
  - 建立唯一 OpenAPI 基线、ADR、架构/证据/runbook 文档入口和仓库说明。
  - 建立 Makefile、GitHub Actions、Compose 空服务基线和正式顶层目录。
  - 更新 `AGENTS.md`，删除根 `src/` 业务源码约定并冻结包管理与验证规则。
- 自动验证：
  - `make verify-fast`：通过。
  - Java：9 个 reactor project 全部 `BUILD SUCCESS`，Java/Maven Enforcer 通过。
  - Frontend：lint、typecheck、Vite production build 全部通过且无构建警告。
  - OpenAPI：Redocly recommended lint 通过且无警告。
  - Compose：`docker compose config --quiet` 通过。
  - `git diff --check`：通过。
- 内置浏览器手测：
  - 打开 `http://localhost:3000/data/connections`，确认白色侧栏、浅色内容区、蓝色主色和中文信息架构。
  - 点击“收起导航”与“展开导航”，两种状态均正确。
  - 点击“通知”，确认抽屉打开并可关闭。
  - 点击“AIP 助手”，确认 480px 上下文抽屉打开且输入在 P13 前明确禁用。
  - 点击“用户菜单”，确认个人资料、偏好设置和退出登录项可见。
  - 点击“数据连接”导航，确认 URL 与页面标题保持 `/data/connections` / “数据连接”。
  - 首轮发现 Ant Design `Card bordered` 弃用告警，改为 `variant` 后使用全新页签复测；最终 console warning/error 为 0。
- 边界说明：P00 只交付工程与全局外壳；导航中后续功能保持禁用并明确标注交付 Phase，未引入 P01 基础设施或虚假 API。

## P01 — 默认认证的 Compose 基座

- 完成时间：2026-07-20 16:00 CST
- Commit：`feat(platform): add authenticated compose foundation`
- 范围：
  - 以精确 tag + OCI index digest 锁定 APISIX、Flink、HugeGraph、Java、Keycloak、Maven、MinIO、Nginx、Node.js、OpenSearch、OpenTelemetry Collector、Postgres、Pulsar 和 SkyWalking 镜像。
  - 构建 `apps`、`auth`、`compute`、`core`、`gateway`、`maintenance`、`observability` profiles，以及私有网络、独立持久卷、Docker secrets、健康依赖和幂等 bootstrap。
  - 构建自定义 Flink 镜像，在构建阶段下载并校验 JDBC/Kafka/Pulsar 连接器、MySQL/PostgreSQL driver 与 S3 Hadoop plugin，禁止运行时下载未锁制品。
  - 以 Postgres 17 控制面、私有 MinIO buckets、显式 Pulsar tenant/namespace/topic、HugeGraph、OpenSearch 和 Flink JobManager/TaskManager 建立数据平台底座。
  - 以 OpenSearch 独立 `skywalking` namespace 启动 SkyWalking OAP/UI，并通过 OTel Collector 与 SkyWalking Java Agent 建立可观测基线。
  - 配置 APISIX standalone 静态路由、Keycloak realm/组/角色/demo identities、Portal Authorization Code + PKCE，以及三个 Java Web 服务的 OAuth2 Resource Server 默认保护。
  - 仅在显式 `VITE_DEV_INSECURE=true` 时允许带常驻告警条的模拟身份；默认构建和文档均使用 Keycloak。
  - 新增 Compose build/up/down、健康、兼容与 `E:platform-foundation` 脚本，并补充镜像证据和启动/恢复 runbook。
- 自动验证：
  - `make verify-fast`：通过；9 个 Maven reactor project、frontend lint/typecheck/build、OpenAPI lint 和全 profile Compose config 均通过。
  - `make compose-build`：7 个项目镜像全部成功；SkyWalking agent SHA-512 与六个 Flink 制品 SHA-256 均在构建或运行时复核。
  - `make compose-up`：17 个长期服务健康，Maintenance/Projection 非 Web 进程保持运行，MinIO/Pulsar bootstrap 重复执行均 exit 0。
  - `make e2e-platform-foundation`：通过；验证 arm64 镜像解析、OIDC discovery、APISIX portal/API 路由、无 token 的 401、7 个私有 buckets 和 7 个显式 partitioned topics。
  - `git diff --check`：通过。
- 内置浏览器手测：
  - 从全新页签打开 `http://localhost:9080/data/connections`，确认显示 OIDC + PKCE 登录页。
  - 点击“使用 Keycloak 登录”，确认跳转包含 `response_type=code`、S256 `code_challenge` 和预期 callback。
  - 输入本机 demo Admin 凭据并点击 Sign In，确认返回 `/data/connections`、页面显示“平台 管理员”且 P01 认证基座内容可见。
  - 点击“用户菜单”与“退出登录”，确认 Keycloak 会话退出并返回产品登录页。
  - 在最终全量镜像重建后的新页签再次执行登录/退出；全程捕获的 console warning/error 为 0。
- 发现并修复：
  - 发现本机旧 `postgres-data` 卷由 PostgreSQL 16 初始化；保留旧卷不破坏数据，PostgreSQL 17 改用独立 `postgres17-data`，并在 runbook 记录 dump/restore 迁移边界。
  - 修复 OpenSearch 安全插件禁用参数重复、APISIX/SkyWalking 镜像缺少 curl、SkyWalking 10.4 实际健康端点为 `/healthcheck`、Frontend Nginx 缺失 SPA root、Keycloak demo identity 缺少 email 和 Compose 单 profile 验证依赖缺失。
  - 完整可观测 profile 首启触发内存压力；锁定 Pulsar、OpenSearch、Keycloak、SkyWalking 和项目 Java JVM 上限，并将 Flink 调整为单机 8 GiB 基线。
  - Frontend 重建后 APISIX worker 曾保留旧容器地址；为上游健康依赖启用 Compose restart 传播，并在最终全量重建中验证网关自动重启和 portal 路由恢复。
  - Java 镜像构建改用 Maven/SkyWalking BuildKit cache 与独立 agent stage，避免后续 Phase 重复下载完整依赖树和 agent archive。
- 兼容/风险证据：
  - Docker Engine 29.6.1、Linux arm64 实测通过；所有外部锁均解析为 arm64 镜像。
  - SkyWalking OAP 10.4.0 识别 OpenSearch 3.7 并成功创建 `skywalking_*` 模板；业务对象索引仍留给 P02，遥测隔离的最终安全门禁属于 P16。
  - MinIO 使用 D-012 冻结的公共镜像，保持 Compose 私网且不暴露 API/Console；其维护时效风险记录在 P01 evidence/runbook。
  - OpenSearch 安全插件仅在 P01 私有网络关闭，禁止公网暴露；生产硬化属于 P16。
- 下一恢复点：从干净工作树开始 P02，只实现控制面 Flyway、事件/Mutation 契约、Projection Worker、双存储投影、幂等/retry/DLQ/重建和 `E:storage-e2e`，不得提前实现 P03 数据连接页面。

## 后续记录模板

每完成一个 Phase，在同一 Phase commit 中追加：完成时间、Commit subject、范围、自动门禁、内置浏览器点击步骤（若涉及前端）、发现并修复的问题、依赖/兼容证据、下一恢复点。
