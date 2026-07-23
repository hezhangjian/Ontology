# Ontology implementation timeline

> 目标：严格按 `docs/plan.md` 的 P00—P17 顺序交付整个系统。每个 Phase 只对应一个 signed-off Conventional Commit；涉及前端的 Phase 必须在 Codex 内置浏览器中完成真实点击测试。

## 恢复点

- 当前状态：P04 已完成并准备提交。
- 下一阶段：按用户指令跳过 P05、P06，从 P07 `feat(modeling): deliver ontology management page` 继续。
- 继续规则：确认 P04 提交存在且工作树干净，再只领取 P07；P07 涉及前端，完成自动门禁后必须使用内置浏览器真实点击测试。P05、P06 仍未实施，除非用户以后明确恢复，不得在本轮补做。

## Phase 状态

| Phase | 状态 | Commit | 验证摘要 |
|---|---|---|---|
| P00 | 已完成 | `build(platform): establish project baseline`（本记录与 Phase 同一提交） | J/F/O/C + 内置浏览器点击通过 |
| P01 | 已完成 | `feat(platform): add authenticated compose foundation`（本记录与 Phase 同一提交） | J/F/O/C + `E:platform-foundation` + 内置浏览器 PKCE 点击通过 |
| P02 | 已完成 | `feat(storage): add ontology projection foundation`（本记录与 Phase 同一提交） | J/O/C + `E:storage-e2e`；无前端变更 |
| P03 | 已完成 | `feat(connections): deliver data connections page`（本记录与 Phase 同一提交） | J/F/O + `E:connections-page` + 内置浏览器全流程点击通过 |
| P04 | 已完成 | `feat(pipelines): deliver pipeline builder page`（本记录与 Phase 同一提交） | J/F/O/C + `E:pipelines-page` + 内置浏览器全流程点击通过 |
| P05 | 本轮跳过 | `feat(quality): deliver data quality page` | 按用户指令未实施、未提交 |
| P06 | 本轮跳过 | `feat(lineage): deliver data lineage page` | 按用户指令未实施、未提交 |
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
  - 纳入最新 `docs/plan.md` 并建立本 timeline。
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
  - 历史 `E:platform-foundation`：通过；验证 arm64 镜像解析、OIDC discovery、APISIX portal/API 路由、无 token 的 401、7 个私有 buckets 和 7 个显式 partitioned topics。
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
  - `make compose-up` + `docker compose --profile '*' -f docker/docker-compose.yml ps`：所有声明健康检查的长期服务 healthy；Maintenance Runner 与无 Web 端口的 Projection Worker 保持 running；两个 bootstrap 服务 completed。
  - 历史 `E:storage-e2e`：在完整镜像重建后重复通过，验证 event/batch 幂等、v2 覆盖与 v1 stale、三 edit 原子事务、关系双写、tombstone、敏感字段过滤、永久错误 DLQ、搜索恢复、alias 重建和 Postgres 数据所有权。
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
  - 历史 `E:connections-page`：最终通过；真实验证 MinIO、加密/secret hygiene、测试令牌、发现、Schema、预览、duplicate rollback、乐观锁、Viewer/Builder/Admin、停用/恢复/删除和审计。
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

## P04 — Flink 管道构建完整纵切

- 完成时间：2026-07-20 21:43 CST
- Commit：`feat(pipelines): deliver pipeline builder page`
- 范围：
  - 在唯一 OpenAPI 中交付管道列表、草稿、校验、预览、不可变版本、变更提议、发布/回滚、批流运行、savepoint、offset reset、调度、事件流、Projection ack 和内部工作负载凭据契约。
  - 以 Flyway V3—V5 建立 pipeline draft/version/dependency/proposal/run/stage/event/checkpoint/schedule/preview/grant/projection batch 控制面；发布版本不可修改，运行固定引用精确版本。
  - 实现完整 DAG 模型、循环/端口/模式/Schema/输出校验、字段传播、下游失效提示、ETag 乐观锁、自动保存、变更影响和版本化 Pipeline IR/Job Spec。
  - 构建受控 Flink 通用作业，支持 MinIO/S3 CSV、MySQL、PostgreSQL、Kafka 和外部 Pulsar 源；批流共用 IR 与转换路径，checkpoint 提交消费位置，正式输出只写平台 Pulsar。
  - 预览使用同一 Flink 作业有界执行，限制行数与 1 MiB、短期缓存、可取消并根据源字段与本体敏感属性脱敏，不写 Pulsar/HugeGraph/OpenSearch。
  - 运行凭据通过内网 Broker 按 service token、run、签名、状态、scope 和 TTL 交换；IR、program args、API、审计和日志不含明文凭据。
  - 批任务异步提交并同步 Flink 状态，Flink 结束后进入 PROJECTING，只有 correlation-scoped Projection ledger 全部回执才完成；支持 SSE durable replay、取消、重试、DLQ 重放和流任务 stop-with-savepoint。
  - 交付紧凑管道表格、URL 筛选、全屏模板向导、React Flow DAG 编辑器、节点库/配置/底部面板、审核/历史和九阶段运行详情。
- 自动验证：
  - `make verify-fast`：通过；9 个 Maven reactor project、18 个 Java tests、frontend lint/typecheck/build、OpenAPI recommended lint 和全 profile Compose config 全部通过。
  - 历史 `E:pipelines-page`：通过；真实验证 MinIO CSV → 有界 Flink preview → 不可变发布 → 正式 Flink → Pulsar → Projection ack，并覆盖 masking、ETag 409、提议审批、v2、回滚、SSE 重放、Viewer 403、审计和 secret hygiene。
  - Ontology Core/Flink Job/Portal 镜像构建成功，完整依赖服务健康；`git diff --check` 与脚本语法检查通过。
- 内置浏览器手测：
  - 从 `http://localhost:9080` 使用 Builder OIDC 登录，点击侧栏“管道构建”，确认 7 条 E2E 管道、生命周期/运行状态分列、模式、调度和负责人均正确；直接访问 frontend 调试端口不作为平台入口。
  - 点击“新建管道”，填写名称与说明，依次选择真实 MinIO 连接和 `p04-e2e/employees.csv`，点击“创建并打开编辑器”，确认草稿与 DAG 编辑器恢复。
  - 点击“校验”，确认未配置字段和对象输出被两条 Error 阻止发布；节点库、React Flow 控件、Schema/预览/校验/日志面板、运行与调度设置可见。
  - 点击“历史”核对空草稿历史；再打开已发布 E2E 管道，确认 v1/v2 不可变版本和 v1 完成运行。
  - 打开运行详情并点击“刷新”，确认 SUBMITTED 至 COMPLETED 九阶段、读/写 2、拒绝 0、Projection 100%、Flink Job ID、correlation ID、事件时间线和脱敏日志；console warning/error 为 0。
  - 手测创建并保留本地“浏览器验收草稿”作为可追溯证据；Keycloak 首次登录要求补齐 demo Builder email，已填写 realm 预期的 `builder@ontology.local`。
- 发现并修复：
  - Flink 容器挂载配置覆盖镜像默认 Java 17 module opens，导致 `ExecutionContextEnvironment` 初始化失败；补齐官方 `env.java.opts.all` 后预览和正式作业均通过。
  - 内部回调 POST 被 CSRF 拒绝；只对 `/internal/v1/**` 忽略 CSRF，同时保留 service token 校验。
  - workload grant 查询中 `g.status` 与 `r.status` 同名，运行状态错误解析为 `ACTIVE` 并拒绝凭据；显式使用 `run_status` alias 后正式运行通过。
  - proposal insert 仅为 `impact` cast JSONB，`validation` 导致 PostgreSQL 500；两列均显式 cast 后提议/审批/v2 发布通过。
  - 预览只依据源推断敏感字段，未遮蔽本体 contract 中的 email；合并源敏感标记和对象属性敏感定义后复测为 `••••••`。
  - stale draft ETag 原映射为 422；统一 pipeline conflict 为 409，并由 E2E 固定回归。
  - 本机旧 Pulsar BookKeeper 数据卷已损坏且无法恢复启动；明确只删除 `ontology-platform_pulsar-data` 并由幂等 bootstrap 重建，未触碰其他卷。
- 兼容/安全证据：
  - Flink Job 模块以 Java 17 bytecode 构建并运行在 Flink 1.20，平台服务仍由 Java 21 Enforcer 管理；通用 Job JAR 在镜像构建期产生并由 Core 按哈希上传。
  - E2E 检查 Pipeline IR、Job Spec、Core/JobManager/TaskManager 日志均无 MinIO 明文密码；grant 终态撤销且凭据只在 Flink 内存使用。
  - PostgreSQL 只保存 DAG/版本/运行/审计等控制面，业务对象正文仍只进入 HugeGraph，OpenSearch 仍是可重建投影。
- 下一恢复点：P04 commit 后按用户指令跳过 P05 数据质量和 P06 数据血缘，从干净工作树开始 P07 本体管理；本轮做到 P09 后停止，不得继续 P10。

## P07 — 本体管理完整纵切

- 完成时间：2026-07-20 22:27 CST
- Commit：`feat(modeling): deliver ontology management page`
- 范围：
  - 在唯一 OpenAPI 中交付本体概览/搜索、对象类型、稳定属性、关系类型、Interface、Action、Function、多资源 Proposal、发布部署、健康问题和不可变历史契约。
  - 以 Flyway V6 建立稳定资源身份与不可变版本、共享全局 revision、对象/属性/关系/Interface/Action/Function 类型表，以及 Proposal、审核、评论、发布 Saga、步骤和健康控制面；对象正文仍不进入 PostgreSQL。
  - 规范资源使用稳定逻辑 ID、API 名和不可变物理键；属性键跨 revision 稳定，主键/标题/敏感/可搜索约束由后端统一校验。
  - Interface 仅表达可复用的属性/关系槽位与实现绑定，不建立独立对象存储；关系快照在对象类型后发布并保留双向遍历元数据。
  - Action 使用声明式写入规则、参数和提交/审批条件，Preview 返回安全 diff 与短期 token；Function 使用类型化只读 DSL、调用者权限作用域和精确不可变版本绑定。
  - 发布采用持久化 Saga：锁定 Proposal、生成契约、真实探测 HugeGraph schema、真实探测 OpenSearch alias、原子激活全局 revision、写审计；失败保留旧 ACTIVE revision 和可诊断步骤，重试创建新 deployment。
  - 交付共享本体概览、搜索、完整内部导航、资源列表、四步对象向导、资源详情/多 Tab、属性目录、Proposal 审核/发布、健康和历史页面；Viewer 可读，Builder 可起草/提交，Admin 批准/发布。
- 自动验证：
  - `make verify-fast`：通过；9 个 Maven reactor project、22 个 Java tests、frontend lint/typecheck/build、OpenAPI recommended lint 和全 profile Compose config 全部通过。
  - 历史 `E:modeling-page`：通过；真实验证五类规范资源、多资源审核、Viewer/Builder/Admin、主键与敏感字段规则、Action Preview、Function 权限/版本绑定、OpenSearch 故障下 Saga 保旧 revision、恢复重试、精确 revision Pulsar 投影、敏感字段过滤和审计。
  - Ontology Core/Portal 镜像重建成功，Flyway V6 已应用且完整依赖服务健康；`git diff --check` 与历史 E2E 脚本语法检查通过。
- 内置浏览器手测：
  - 使用 Builder OIDC 从全局侧栏进入“本体管理”，核对 Revision 4 概览、资源统计、最近资源、模型关系图和完整内部导航。
  - 点击“新建对象类型”，逐步填写 `BrowserAcceptanceAsset`、来源、主键/标题属性并在复核页确认所有跨步骤字段，保存为 DRAFT 后检查稳定 API 名、物理键、属性、数据映射、索引投影和不可变版本。
  - 从对象详情创建变更提议，点击校验并确认 LOW 风险和目标 revision 5，提交审核后确认 Builder 不显示批准按钮。
  - 逐项点击属性目录、关系类型、Interface、Action、Function、健康问题和变更历史；Action Preview 返回声明式 UPDATE diff 与审批要求，Function 测试返回 v1 精确绑定、只读 DSL 和调用者权限生效。
  - 退出 Builder 并以 Admin 登录，打开同一提议，点击批准和“发布 Revision”，确认六个 Saga 步骤全部 SUCCEEDED；历史页显示 Revision 5 ACTIVE、Revision 4 RETIRED，浏览器日志为空。
- 浏览器/E2E 发现并修复：
  - 四步向导复核页最初只读取当前挂载字段，导致先前步骤显示为空；改用保留字段的完整表单值，并在最终 Portal 镜像重建后从头点击复测。
  - SQL 可选列表过滤直接推断 nullable 参数类型失败；改为显式 `varchar` 判空。
  - 多资源快照按字母顺序先写关系再写新对象，触发契约外键失败；改为对象类型优先、其余资源第二遍写入，故障部署与健康证据仍按设计保留。
  - Frontend Proposal 资源选择器移除间接组件 shim，直接使用 Ant Design `Select`，生产构建与浏览器交互通过。
- 兼容/安全证据：
  - 真实停止 OpenSearch 后，deployment 固定失败在 `MIGRATE_OPENSEARCH`，旧 revision 保持 ACTIVE；恢复后 retry 生成新 deployment 并完成原子激活，失败候选 revision 不被篡改或复用。
  - Projection Worker 只接受事件中的精确 ACTIVE revision 合同；OpenSearch 只投影 searchable 且非 sensitive 属性，HugeGraph 继续保存对象正文。
  - 公开 API、审计、发布步骤和浏览器响应均不返回凭据；浏览器仍只经 APISIX 平台 API 访问，不直连 HugeGraph、OpenSearch 或模型提供商。
- 下一恢复点：P07 commit 后从干净工作树开始 P08，只实现“对象探索”完整 vertical slice；P05/P06 继续按用户指令跳过，本轮 P09 完成后停止，不得继续 P10。

## P08 — 对象探索完整纵切

- 完成时间：2026-07-20 23:24 CST
- Commit：`feat(explorer): deliver object exploration page`
- 范围：
  - 在唯一 OpenAPI 中交付 Object Set 查询、全局搜索、Facet、稳定 Cursor、对象详情/关系、能力、活动、来源、比较、保存探索/清单、选择令牌、导出和 Action 门禁契约。
  - 以 Flyway V7 建立保存探索、仅引用对象清单、短期选择令牌账本、导出和 Action job 控制面；PostgreSQL 不保存业务对象正文。
  - 实现有界 Object Set AST 校验，所有搜索/排序/Facet/分页由 OpenSearch 执行并在存储查询中注入 visibility token；Cursor 绑定调用者、规范查询、排序与过期时间并进行 HMAC 签名。
  - HugeGraph 权威读取对象详情和一跳关系，服务端二次执行敏感字段遮蔽；OpenSearch 故障时搜索显式 503，但详情和关系仍可用。
  - 选择令牌绑定 owner/query/purpose/TTL 并由可撤销账本支撑；安全导出写入私有 MinIO 对象并记录 SHA-256、所有者和 24 小时过期；Action 使用声明式 Preview token 与选择令牌双门禁创建审计 job，不提供任意 CRUD。
  - 交付探索首页、全局搜索、最近/收藏/保存探索/对象清单、对象类型工作区、筛选器、Table/Card/快速分析/关系图/比较、Panel 与 Full 两类详情及属性/关系/Action/Function/活动/来源 Tab。
- 自动验证：
  - `make verify-fast`：通过；Maven reactor、Java tests、frontend lint/typecheck/build、OpenAPI recommended lint 和全 profile Compose config 全部通过。
  - 历史 `E:explorer-page`：通过；真实验证 storage-authorized search/Facet/cursor、HugeGraph detail/relation/degradation、redaction、compare、saved references、signed selection、MinIO export、Action gate、RBAC 和审计。
  - Ontology Core/Portal 最终镜像重建成功，Flyway V7 已应用且完整依赖服务健康；`git diff --check` 与历史 E2E 脚本语法检查通过。
- 内置浏览器手测：
  - 使用 Viewer OIDC 从全局侧栏进入“对象探索”，核对 5 个对象类型和 HEALTHY 能力；全局搜索精确找到 E2E Employee 且 email 未泄露。
  - 打开 Full 详情，逐项点击属性、关系、Action、Function、活动和来源，确认 Viewer 不显示可执行 Action、投影活动和 revision 5 字段血缘可见。
  - 返回 Employee 工作区，逐项点击 Card、Panel、快速分析和关系图；Panel 可关闭，Facet 显示 Research/Operations 各 25，关系图展示 12/200 节点。
  - 在 Table 全选 50 个对象，创建动态“P08 浏览器手测探索”和静态“P08 浏览器手测清单”，再提交 CSV 私有安全导出，确认 50 行与 24 小时过期提示。
  - 最终 Portal 镜像下所有上述请求均通过 APISIX `/api/ontology/v1`，console warning/error 为 0。
- 浏览器/E2E 发现并修复：
  - Explorer frontend 最初遗漏平台 `/api` 前缀，导致经 APISIX 请求返回 HTML 并触发 JSON 解析错误；统一 service 与 Action preview 路径后重建镜像并复测。
  - OpenSearch `_score` 排序请求形态无效；改为原生 `_score` 加稳定 `object_id` tie-breaker。
  - Cursor 查询指纹最初包含 Cursor 本身导致下一页永远不匹配；改为规范化无 Cursor 的查询身份并增加回归测试。
  - PostgreSQL provenance 字段名、`Instant` 绑定、UUID 数组 cast 分别导致运行时错误；修正为真实列名、`Timestamp` 和显式 `uuid[]` cast。
  - Spring 未映射错误 HTTP method，安全问题响应误为 500；补齐 405 映射且记录意外异常。
  - E2E fixture 改为带时间戳对象和确定性已发布 Employee Action，并按 JWT subject 校验审计，避免重复运行污染断言。
- 兼容/安全证据：
  - 搜索、Facet、数量和 Cursor 均在 visibility predicate 之后计算；浏览器不能提交 native OpenSearch DSL、Gremlin、SQL 或任意 ID 批量操作。
  - 保存清单只保存稳定对象引用，导出正文只进入 MinIO；对象与关系正文继续只由 HugeGraph 权威保存，OpenSearch 仍可重建。
  - Cursor、选择和 Action preview token 均签名、限时且绑定调用者/查询；敏感字段在 OpenSearch contract 和 HugeGraph 响应两层收紧。
- 下一恢复点：P08 commit 后从干净工作树开始 P09，只实现“分析看板”完整 vertical slice；P05/P06 继续按用户指令跳过，P09 完成后停止，不得继续 P10。

## P09 — 分析看板完整纵切

- 完成时间：2026-07-21 00:07 CST
- Commit：`feat(dashboards): deliver analytics dashboards`
- 范围：
  - 在唯一 OpenAPI 中交付看板列表/详情、草稿、编辑租约、验证、发布、不可变版本/差异/恢复草稿、权限、收藏、健康、使用量、Query Plan、批执行、筛选候选和下钻 token 契约。
  - 以 Flyway V8 建立 dashboard identity、规范化 draft/version page/source/widget/filter/binding/dependency、permission、favorite、edit lock、immutable query plan、query run、health 和 audit 控制面；PostgreSQL 不保存对象正文或完整查询结果。
  - 实现 ETag 乐观锁、15 分钟单编辑者租约、旧发布版本保护、Owner/Editor/Viewer 门禁、复制、空草稿删除、归档/恢复和历史版本创建新草稿语义。
  - 发布时冻结本体 revision 与稳定资源 ID，生成不可变 Dashboard Query Plan，使用发布者权限受限样例执行后原子切换版本；运行时复用 P08 Object Set，以当前调用者权限重查。
  - 执行缓存按调用者安全上下文隔离，默认小群体阈值 5；筛选显式绑定稳定属性 ID，被抑制分组不可下钻，合法下钻使用短期签名 caller-bound token。
  - 交付紧凑看板表格、查看/全屏/版本/固定版本路由、24 列三栏全屏编辑器、页面导航、数据源、指标/柱状图/饼图/对象表格/受限 Markdown/分节标题组件、检查器、自动保存、验证和发布。
- 自动验证：
  - `make verify-fast`：通过；Maven reactor、26 个 Java tests、frontend lint/typecheck/build、OpenAPI recommended lint 和全 profile Compose config 全部通过。
  - 历史 `E:dashboards-page`：通过；真实验证 ETag 草稿/租约、v1/v2 不可变发布、失败候选保旧版本、权限作用域查询/缓存、筛选、小群体抑制、下钻、收藏、复制、归档/恢复和审计。
  - Ontology Core/Portal 最终镜像重建成功，Flyway V8 已应用且完整依赖服务健康；`git diff --check` 与历史 E2E 脚本语法检查通过。
- 内置浏览器手测：
  - 使用 Builder OIDC 从全局侧栏进入“分析看板”，确认 E2E v2 看板的状态、页面/组件数量、组织可见范围、刷新策略和健康状态，并创建“P09 浏览器手测看板”。
  - 在全屏编辑器选择 Employee revision 5 Object Set，添加指标卡、部门柱状图、对象表格和受限 Markdown；新增并重命名第二页、添加指标，等待 2 秒自动保存后点击“验证”和“发布”。
  - 查看页确认 v1 `SUCCEEDED`，对象总数 336，Research/Operations 聚合可见，小于 5 的 Platform/Engineering/Flight Research 显示“已抑制”，对象表格不含 email。
  - 点击收藏、明细页面、全屏和版本；固定版本页明确标注“历史定义，不是历史数据”，手动刷新后以当前权限重新查询并成功。
  - 最终生产 Portal 镜像下 console warning/error 为 0，浏览器请求均经 APISIX 平台 API。
- 浏览器/E2E 发现并修复：
  - 全屏编辑器 `z-index` 高于 Ant Design Modal/Select，导致数据源弹窗存在但被画布遮挡且无法点击；降低编辑器层级、重建 Portal 镜像后弹窗和下拉均可见可操作，并从建板到发布完整复测。
  - `dashboard_drafts.status` 首次插入遗漏，PostgreSQL 非空约束使事务回滚；两条创建草稿路径显式写入 `DRAFT` 后完整 E2E 通过。
- 兼容/安全证据：
  - 看板不能提交 SQL、Gremlin、PPL、原生 OpenSearch DSL、任意 JavaScript/HTML 或 Action；静态说明仅渲染受限 Markdown。
  - 分享看板定义不授予底层数据权限；运行、缓存和下钻都绑定当前调用者，敏感属性和小群体值不进入结果、token、日志或审计正文。
  - HugeGraph 继续保存对象真相，OpenSearch 继续保存可重建投影，PostgreSQL 只保存规范化控制面和有界运行元数据。
- 下一恢复点：P09 commit 后按用户明确指令停止；P05/P06 已跳过，不进入 P10。如未来继续，应先从干净工作树核对 `docs/plan.md` 与本记录。

## 后续记录模板

## 2026-07-21 — CSV 到部门 Token 看板浏览器演示

- 数据与浏览器操作：使用内置浏览器以 Admin 登录，从 `/Users/dijkstra/project/06-shixi/data` 的两个原始 CSV 创建 `Token 消耗演示 CSV` MinIO 连接，刷新发现 5 个资产；在 UI 中创建、校验、预览、发布并运行完整事实与部门汇总管道。
- 本体操作：在 UI 中创建并发布 `TokenUsage`（13 属性，Revision 6）和 `TokenDepartmentSummary`（7 属性，Revision 7）；两次发布 Saga 的 HugeGraph、OpenSearch、Revision 激活和审计步骤全部成功。
- 看板操作：在 UI 中创建并发布 `部门与团队 Token 消耗`，绑定 `TokenDepartmentSummary` Object Set，添加一级部门与三级组两张 `sum(totalToken)` 柱状图。
- 浏览器验收：发布查看页成功显示 4 个一级部门和 5 个三级组；产品研发中心 `16,124,098`、数据平台组 `7,587,487`，两种分组总计均为 `47,127,118`，与 6000 行源 CSV 的命令行独立聚合一致。
- 安全验证：保留每组至少 5 个对象的小群体抑制；汇总数据为每个组织路径增加 4 个零值占位对象，满足门槛且不改变 Token 合计。清理的 5,807 条消息仅是已取消演示运行在 `ontology-projection-v1` 的待处理积压。
- 浏览器发现并修复：React Flow 节点尺寸/MiniMap 遮挡、尺寸事件导致自动保存饥饿、发布前未保存、Flink revision 硬编码、CSV 数值未类型化、新本体 API/物理键查询不一致、PROJECTING 无法取消，以及看板缺少分组 SUM。
- 已知改进项：完整 6000 行 Projection 在本机逐条写图和搜索，吞吐不足以现场等待；幂等重跑中的 5 条更新没有计入新 batch ack。演示使用准确的物化汇总，生产应实现 Projection 批处理和 correlation-aware 幂等回执。
- 演示步骤与启动/登录说明见 `docs/runbooks/token-consumption-demo.md`。

## 2026-07-21 — 全资源无资格限制删除

- 范围：数据连接、管道、本体对象/关系/接口/Action/Function、分析看板均提供永久删除入口；删除不再要求 Admin、Owner、停用、空草稿、未发布、无引用或无运行记录。
- 级联行为：删除服务按实际 PostgreSQL 外键顺序清理连接资产与凭据授权、管道版本/运行/调度/提议/映射、本体版本/属性/提议/探索记录/直接依赖资源，以及看板草稿/版本/查询计划/运行/权限/收藏/锁和健康记录。
- 权限回归：Data Connection 与 Pipeline MVC 测试明确验证 Viewer 可执行 DELETE；本体和看板删除继承 Viewer/Builder/Admin 访问范围，Portal 删除入口不再依赖 `canBuild` 或 `isAdmin`。
- 自动验证：Java 21 Docker 中完整 Maven reactor 通过，Ontology Core 27 项测试通过；Portal lint、typecheck、production build 通过；OpenAPI 有效并保留既有 46 条非阻断警告；全 profile Compose config 与 `git diff --check` 通过。
- 浏览器验收：重建并启动 `ontology-core`、`frontend` 和 APISIX 后，经 `http://localhost:9080` 登录；使用 `Codex 删除验证 1784599023` 一次性资源确认已发布/健康连接和管道仍显示永久删除，连接确认框明确显示关联管道数量并成功级联删除。
- 清理核对：一次性数据连接、关联管道、独立管道来源、本体资源、看板和连接凭据在 PostgreSQL 中均为 0 条残留；APISIX、Frontend、Keycloak、Ontology Core、PostgreSQL 均保持 healthy。

## 2026-07-21 — Dataset-first 通用平台与 Token 面板验收

- 范围：依据 `docs/dataset-first.md` 增加通用 Dataset 控制面、Pipeline `DATASET_OUTPUT`、Dataset 列表/详情/字段映射、Dataset 驱动对象向导和可配置分析面板；Portal 主导航聚焦数据、本体、探索和应用主流程。
- 内置浏览器数据操作：从 `/Users/dijkstra/project/06-shixi/data` 手动选择并上传 `demo-employee-leaders.csv` 与 `demo-token-usage.csv`，创建 `Token 消耗 Dataset-first 验收` 连接（`ff64aed9-0dfd-401f-8af1-91a21138156c`）；从连接详情点击创建 `Token 使用明细宽表管道`（`f3e5e7f2-205e-4d14-aa6a-8407ab2c9921`）。
- Pipeline 与 Dataset：在 DAG 中依次配置员工工号关联和组长姓名关联，输出稳定字段 `employee_id`、`leader_group_id`、`leader_group_name`、`month`、`total_tokens` 及五级组织字段；点击“生成数据集”得到 `Token 使用组织宽表`（`61eadd47-d22c-456c-ba68-290319fe22e4`），共 6,000 行、18 字段。
- 本体操作：从同一 Dataset 逐项预览并发布 `人员`（100 个对象、5,900 重复行、0 冲突，Revision 15）、`部门`（5 个对象，Revision 16）、`组长小组`（8 个对象，Revision 17）；发布 `人员属于部门（Dataset）`（Revision 18）和 `人员属于组长小组（Group）`（Revision 19）两条多对一关系。
- 面板验收：从 Dataset 详情点击“制作看板”，默认识别稳定字段并扫描 6,000 行；显示 80 个部门×月份组合、8 个组长小组，以及“部门月份 Token 消耗”“部门月份人均 Token 消耗”“各小组 Token 月均消耗”三组结果。一级部门合计为产品研发中心 `16,124,098`、市场与销售中心 `9,437,020`、运营中心 `10,337,318`、财务中心 `11,228,682`，总计 `47,127,118`；小组月均最高为张子涵组 `309,537.05`，并点击“查看每月”确认 20 个月明细。
- 浏览器发现并修复：Pipeline 新增节点改为自动插入当前选中节点之前并重连边；Dataset 对象向导预览后表单项卸载导致名称和主键丢失，改为从保留的完整表单值创建；关系技术名冲突在 UI 中通过唯一稳定技术名避免。最终面板浏览器控制台日志为空。
- 自动门禁：`make verify-fast` 通过；Maven reactor（Ontology Core 29 项测试）、Portal lint/typecheck/production build、OpenAPI 有效性和全 profile Compose 配置全部通过；OpenAPI 保留 49 条既有风格类非阻断警告。`git diff --check` 通过，本轮未创建 commit。

### 2026-07-21 复核更正：页面职责通过，平台链路未通过

- 页面职责修正：Dataset 详情仅保留预览、字段和来源，不再提供“创建业务对象”或“制作看板”；对象创建入口和三步向导归属 `/ontology/object-types/new/from-dataset`；看板创建入口和 Dataset 选择归属 `/apps/dashboards/new/from-dataset`。
- 内置浏览器复核：经 APISIX `http://localhost:9080` 验证本体与看板各自的面包屑、返回路径、Dataset 选择器和独立页面；选择 `Token 使用组织宽表` 后仍可扫描 6,000 行并展示部门月份合计、部门月份人均和 8 个组长小组月均结果；Dataset 详情文本中不存在两个跨模块创建入口。
- 基础设施审计：`control.dataset_rows` 实际保存 6,000 条业务数据；该 Pipeline 没有对应 Flink run；MinIO 只有导入暂存 CSV，没有 Dataset Parquet 正文；OpenSearch 没有 Dataset 查询副本。因此此前“Dataset 主路径验收通过”的结论无效，现仅判定 Portal 信息架构和指标展示通过。
- 组件职责结论：组件不需要在每个请求中机械地全部经过，但完整 Dataset 主路径应为 MinIO 保存正文、Flink 执行物化、OpenSearch 提供分析查询、PostgreSQL 只保存控制面；对象实例路径再经 Pulsar 和 Projection Worker 写入 HugeGraph/OpenSearch。当前实现未满足这一边界，不能称为通用平台链路验收通过。

### 2026-07-21 最终复核：真实 Flink、对象语义、通用图表与删除生命周期

- Dataset 主链路已改为真实 Flink：`DATASET_OUTPUT` 由 Flink 读取 6,000 行并经 Pulsar 交给 Dataset consumer，正文写 MinIO NDJSON、查询副本写 OpenSearch、PostgreSQL 只保留 Dataset 控制面和投影账本；当前 Dataset 为 `f060e65c-dc2f-4963-bd41-de8b8fd96ecb`，状态 READY、6,000 行、18 字段。此前“平台链路未通过”的结论由本条替代。
- 内置浏览器在本体页面分别创建并发布 `人员`、`部门`、`组长小组`；属性边界为人员 4 个自身属性、部门 2 个组织属性、小组 2 个身份属性。对应 Flink 映射读取均为 6,000 行，去重写出 100、5、8 个对象，Projection 全部 COMPLETED；人员 Job `230cb06605548ad1726c360b85971ac8`，部门 Job `ec55ab4e7f56e16a7f938af78af31bcf`，小组 Job `49f0672bef1cbb95bacb7738ee5c4999`。
- 对象探索页确认探索的是业务对象实例而非对象定义，显示人员 100/4 属性、部门 5/2 属性、组长小组 8/2 属性，并提供各类型“探索实例”入口。
- 内置浏览器在分析看板页面创建 `Token 使用组织分析`，手动添加 Dataset 数据源并从组件库选择图表；编辑器保留 Dataset、图表类型、维度、聚合、指标字段的可编辑配置。发布页实时渲染 `部门月份 Token 消耗` 堆叠柱状图、`部门月份人均 Token 消耗` 折线图和 `各小组 Token 月度消耗` 柱状图，没有写死专用 Dashboard 页面。
- 删除生命周期验收：用临时 Dataset 从列表确认框永久删除成功；复制临时管道后，未归档时永久删除为禁用，归档后入口启用并永久删除成功。修复管道更多菜单仅悬停触发的问题，现按钮明确使用 click 触发。最终 Dataset 被已发布看板引用时，删除被阻止并显示“Dataset 正被分析看板使用，请先移除对应数据源”，资源未误删。
- 稳定性修复：连续 Flink 作业暴露 TaskManager `768m` 在 Flink 1.20 JVM 最小内存分配下无法重启，调整为 `1024m`；重启后恢复 1 个 TaskManager、2 个可用 slot，小组映射重跑完成。
- 最终门禁：`make verify-fast` 通过；Maven reactor（Flink 4 项、Ontology Core 29 项、Projection Worker 4 项测试）、Portal lint/typecheck/production build、OpenAPI 有效性、全 profile Compose 配置和 `git diff --check` 均通过。OpenAPI 保留 49 条既有风格类非阻断警告。

每完成一个 Phase，在同一 Phase commit 中追加：完成时间、Commit subject、范围、自动门禁、内置浏览器点击步骤（若涉及前端）、发现并修复的问题、依赖/兼容证据、下一恢复点。

## 2026-07-21 — Dataset 自助式通用图表配置

- 范围：看板检查器改为直接从 Dataset 原始字段配置横轴、日/周/月/季度/年时间粒度、系列、最多四个独立指标和图表内筛选；未引入需要预维护的语义字段或指标目录。
- 查询执行：Dataset 查询增加显式维度规格和服务端时间分桶；同一查询支持多指标、`SUM_PER_DISTINCT` 人均口径及字段筛选，返回统一的维度、指标和行结果；旧 `dimensionPropertyIds + aggregation` 配置继续兼容。
- 渲染：柱状图可按系列分组，堆叠柱状图按系列堆叠，折线图/面积图支持系列和多指标，透视表动态显示全部指标，指标卡可展示多个值。
- 自动验证：新增 Dataset 月度分桶、多指标、人均和字段筛选测试，以及看板多指标配置校验测试；Java 21 Maven 完整 reactor 43 项测试、Portal lint/typecheck/production build、OpenAPI 有效性、全 profile Compose config 和 `git diff --check` 全部通过。OpenAPI 保留 49 条既有非阻断警告；宿主机没有本地 Java 21，因此 Maven 门禁使用仓库已有的 Java 21 Maven 镜像等价执行。
- 浏览器验证：按用户明确要求未使用内置浏览器；由用户手工验证编辑器交互和最终图表显示。
