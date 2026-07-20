# Ontology implementation timeline

> 目标：严格按 `plan.md` 的 P00—P17 顺序交付整个系统。每个 Phase 只对应一个 signed-off Conventional Commit；涉及前端的 Phase 必须在 Codex 内置浏览器中完成真实点击测试。

## 恢复点

- 当前状态：P03 已完成并准备提交。
- 下一阶段：P04 `feat(pipelines): deliver pipeline builder page`。
- 继续规则：确认 P03 提交存在且工作树干净，再只领取 P04；P04 涉及前端，完成自动门禁后必须使用内置浏览器真实点击测试。

## Phase 状态

| Phase | 状态 | Commit | 验证摘要 |
|---|---|---|---|
| P00 | 已完成 | `build(platform): establish project baseline`（本记录与 Phase 同一提交） | J/F/O/C + 内置浏览器点击通过 |
| P01 | 已完成 | `feat(platform): add authenticated compose foundation`（本记录与 Phase 同一提交） | J/F/O/C + `E:platform-foundation` + 内置浏览器 PKCE 点击通过 |
| P02 | 已完成 | `feat(storage): add ontology projection foundation`（本记录与 Phase 同一提交） | J/O/C + `E:storage-e2e`；无前端变更 |
| P03 | 已完成 | `feat(connections): deliver data connections page`（本记录与 Phase 同一提交） | J/F/O + `E:connections-page` + 内置浏览器全流程点击通过 |
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

## P02 — 本体投影与存储基础

- 完成时间：2026-07-20 17:10 CST
- Commit：`feat(storage): add ontology projection foundation`
- 范围：
  - 为 Ontology Core 增加 Flyway PostgreSQL 控制面，建立不可变本体 revision/type/property/relation 元数据、event/operation ledger、failure 和 index rebuild job；从 V1 起不创建对象正文表。
  - 在 `platform-contracts` 冻结 snake_case 的 ontology event、source lineage、typed mutation edit/batch 和 index rebuild command，并增加序列化契约测试。
  - 实现独立 Projection Worker 的 Pulsar Key_Shared 消费、revision/类型/属性校验、事件幂等、版本乱序拒绝、graph checkpoint、negative ack 重试、安全 failure ledger 和显式 DLQ。
  - HugeGraph 保存完整对象/关系 payload 和事实版本；OpenSearch 只保存 searchable 且非 sensitive 字段，使用隔离的 `platform-ontology-*` template/index/alias 命名，不触碰既有同名 legacy 数据。
  - Mutation 小批次先全量校验，以调用方幂等键生成稳定 event ID，在一个 HugeGraph Gremlin 事务中提交最多 100 个混合 object/relation edit，再逐项投影搜索；重试从 graph checkpoint 续跑。
  - 实现 tombstone 删除、从 HugeGraph 全量读取并重建 OpenSearch timestamped index、成功后原子切换 alias，以及 rebuild command 幂等。
  - 新增真实 Pulsar/HugeGraph/OpenSearch/PostgreSQL `E:storage-e2e`、固定 fixtures、架构说明、验证证据和故障/DLQ/重建 runbook。
- 自动验证：
  - `make verify-fast`：通过；9 个 Maven reactor project、5 个 Java tests、frontend lint/typecheck/build、OpenAPI lint 和全 profile Compose config 全部通过。
  - `make compose-build`：Agent Runtime、BFF、Flink、Maintenance Runner、Ontology Core、Portal 和 Projection Worker 镜像全部成功构建。
  - `make compose-up` + `docker/scripts/healthcheck.sh`：所有声明健康检查的长期服务 healthy；Maintenance Runner 与无 Web 端口的 Projection Worker 保持 running；两个 bootstrap 服务 completed。
  - `make e2e-storage`：在完整镜像重建后重复通过，验证 event/batch 幂等、v2 覆盖与 v1 stale、三 edit 原子事务、关系双写、tombstone、敏感字段过滤、永久错误 DLQ、搜索恢复、alias 重建和 Postgres 数据所有权。
  - `git diff --check`、两个 shell 脚本 `sh -n`：通过。
- 前端/内置浏览器：P02 没有新增或修改用户界面，按用户规则无需浏览器点击；P00/P01 的认证外壳未改变。
- 真实故障恢复证据：
  - 停止 OpenSearch 后发布新对象，ledger 达到 `DEGRADED` 且 HugeGraph graph ID 已落库；恢复 OpenSearch 和 consumer 后同一事件以 attempt 2 达到 `PROJECTED`，没有重复图写入。
  - 永久 unknown property 事件同时增加 `CONTRACT_INVALID` failure 和 partitioned DLQ counter；有效事件耗尽重试时保留 `DLQ` 和每次安全失败记录。
  - 重建 job 成功扫描 5 个当前图对象，创建 `platform-ontology-objects-rebuild-*`，数据库 `target_index` 与 alias 实际目标一致。
- 发现并修复：
  - HugeGraph 默认 Gremlin 只监听 loopback，增加私网 launcher 绑定 `0.0.0.0:8182`，端口不发布到宿主机；同时将默认 3.3 GiB heap 限制为 768 MiB，避免完整栈内存压力杀死 Java child 而 PID 1 仍存活。
  - HugeGraph PRIMARY_KEY 顶点不能再建同字段二级索引；改由 vertex label ID 和稳定 primary key 直接定位单对象，关系 key 保留合法 secondary index。
  - HugeGraph 批事务要求创建 edge 时一次提供全部 non-null properties，并禁止重写 primary/sort key；Gremlin batch builder 已分别处理 create/update/delete。
  - HugeGraph 列表响应强制 gzip，即使请求 identity；Storage HTTP client 改为按 `Content-Encoding` 解压，索引重建随后通过。
  - PostgreSQL driver 不能直接推断 `Instant` 参数；rebuild requested time 显式转换为 `Timestamp`。
  - Pulsar CLI 的 `--messages` 会按逗号拆分 JSON；全部 E2E 改用容器内文件与 `--files`，并在证据文档固定这一约束。
  - 发现本机 OpenSearch 卷已有用户/legacy `ontology-objects` index；新投影统一使用 `platform-ontology-*` namespace，删除仅由本次误探创建的空 template，未删除或覆盖 legacy index。
- 下一恢复点：P02 commit 后从干净工作树开始 P03，以“数据连接”完整 vertical slice 交付 OpenAPI、Flyway、加密凭据、连接测试/资产发现/Schema 预览、列表/向导/详情、权限、审计、测试和浏览器手测；不得提前实施 P04。

## P03 — 数据连接完整纵切

- 完成时间：2026-07-20 18:25 CST
- Commit：`feat(connections): deliver data connections page`
- 范围：
  - 在唯一 OpenAPI 中交付数据连接、凭据元数据、测试、发现、资产、Schema、预览、使用情况、关联管道/运行、轮换和生命周期契约。
  - 以 Flyway V2 建立 connection secret/data source/test/asset/field/discovery/pipeline/run/audit 控制面表；连接只保存 `secret_ref`，不保存明文凭据。
  - 实现 AES-256-GCM 托管凭据、Docker Secret/已有凭据引用、HMAC 限时测试令牌、递归 canonical fingerprint、SSRF/私网/端口/平台 Pulsar 策略和安全诊断。
  - 实现 MinIO/S3 CSV、MySQL、PostgreSQL、Kafka、外部 Pulsar 五类真实只读适配器；MinIO 路径包含真实资产发现、四字段 CSV 推断与 50/100 行、1 MiB 预览。
  - 实现创建前复核、目标配置原子测试并保存、乐观锁、候选凭据测试并轮换、停用/恢复/删除约束和脱敏审计。
  - 交付紧凑列表、URL 筛选、四步向导、五 Tab 详情、资产深链抽屉、编辑页、轮换表单、影响确认与 Admin 删除二次确认。
  - Viewer 隐藏导航并返回 403；Builder 可管理但不可删除；Admin 可使用 Docker Secret 和永久删除。
  - OIDC 前端增加 access-token 自动续期和过期存储会话恢复，角色从 access token `realm_access` 解析。
- 自动验证：
  - `make verify-fast`：通过；9 个 Maven reactor project、11 个 Java tests、frontend lint/typecheck/build、OpenAPI recommended lint 和全 profile Compose config 全部通过。
  - `make e2e-connections`：最终通过；真实验证 MinIO、加密/secret hygiene、测试令牌、发现、Schema、预览、duplicate rollback、乐观锁、Viewer/Builder/Admin、停用/恢复/删除和审计。
  - Ontology Core/Portal 最终镜像构建成功，完整依赖服务健康；`git diff --check` 与脚本语法检查通过。
- 内置浏览器手测：
  - 使用 Admin OIDC 会话从空列表进入 `/data/connections/new`，逐步点击选择 MinIO/S3、填写 Bucket/Prefix 与 Docker Secret 引用、执行五阶段真实测试、确认并创建。
  - 点击详情“概览 / 资产 / 同步任务 / 运行记录 / 设置”五个 Tab，逐一确认 URL 同步；打开 CSV 资产深链，核对 `order_id/customer/total/created_at` Schema 与订单 `1001` 的 50 行预览，关闭后回到 `?tab=assets`。
  - 编辑 Prefix 后主按钮切换为“测试并保存”，真实测试通过且说明/配置更新；从设置页填写新 Docker Secret 引用并完成测试轮换。
  - 点击停用影响确认、恢复确认、恢复后的 `UNTESTED`、重新测试、再次停用和 Admin 永久删除二次确认；最终清理所有浏览器/E2E 临时连接。
  - 验证 URL 列表筛选、Viewer 无导航/路由、恢复前禁用发现/预览/创建管道，以及最终 console warning/error 为 0。
- 浏览器发现并修复：
  - Keycloak realm roles 位于 access token 而非 `oidc-client-ts` profile，首轮 Admin 被错误识别为 Viewer；改为安全解码 access-token claim，并补充自动续期。
  - Keycloak lightweight access token 可省略 `sub`，导致测试令牌中的 `null` 字符串与创建请求主体不等；稳定主体改为 `sub → preferred_username → client_id` 并增加回归测试。
  - 测试令牌原来只绑定配置；改为同时绑定凭据 fingerprint，且递归排序嵌套 Map，避免字段顺序差异和测试后替换凭据。
  - 编辑页原来只允许基本信息；补齐五类目标字段和后端事务内真实 test-and-save，失败不修改当前有效配置。
  - 设置页原来只描述轮换；补齐 Managed/Existing/Docker Secret 轮换表单和候选测试后原子替换。
  - 列表/详情危险操作原来无完整二次确认，且列表菜单点击会冒泡进入详情；补齐影响文案、删除确认和菜单事件隔离。
  - 恢复后的 `UNTESTED` 连接原可创建管道；收紧为只有 `HEALTHY/HEALTHY_RESTRICTED` 可发现、预览或创建管道。
- 兼容/安全证据：
  - 连接 master key 必须 base64 解码为 32 bytes，Compose 只以只读 Secret 文件挂载；key version 与轮换边界记录在 runbook。
  - E2E 对 API、审计、config、数据库密文和近期服务日志执行明文凭据检查；浏览器和 API 均不返回 Password、Token、Access Key、Secret Key 或可逆密文。
  - 外部 Pulsar 的 URL/tenant 策略与平台内部事件总线隔离；Kafka/Pulsar 预览不提交 offset 或创建持久订阅。
- 下一恢复点：P03 commit 后从干净工作树开始 P04，只实现 Flink 唯一计算引擎的管道列表、新建、DAG 编辑、配置/发布/运行/调度、运行详情、权限、审计、测试和浏览器手测；不得提前实施 P05。

## 后续记录模板

每完成一个 Phase，在同一 Phase commit 中追加：完成时间、Commit subject、范围、自动门禁、内置浏览器点击步骤（若涉及前端）、发现并修复的问题、依赖/兼容证据、下一恢复点。
